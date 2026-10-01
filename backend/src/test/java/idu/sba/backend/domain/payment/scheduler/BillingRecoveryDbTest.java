package idu.sba.backend.domain.payment.scheduler;

import idu.sba.backend.domain.payment.client.TossPaymentClient;
import idu.sba.backend.domain.payment.dto.TossPaymentApproveResponseDTO;
import idu.sba.backend.domain.payment.entity.*;
import idu.sba.backend.domain.payment.repository.*;
import idu.sba.backend.domain.user.repository.UserRepository;
import idu.sba.backend.global.mail.HtmlMailSender;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.ResourceAccessException;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 실제 테스트 DB(cogi_billing_test)로 트랜잭션 분리·롤백·복구를 검증한다. 토스와 메일만 가짜.
 * 실행 조건: 환경변수 COGI_TEST_DB_USERNAME, COGI_TEST_DB_PASSWORD (없으면 스킵)
 */
@SpringBootTest
@ActiveProfiles("test")
class BillingRecoveryDbTest {

    static final long FREE_ID = 1L; // DataInitializer 시드
    static final long PRO_ID = 2L;

    @BeforeAll
    static void requireTestDb() {
        assumeTrue(System.getenv("COGI_TEST_DB_USERNAME") != null, "테스트 DB 환경변수 없음 → 스킵");
    }

    @DynamicPropertySource
    static void fillMissingEnv(DynamicPropertyRegistry registry) {
        String[] keys = {"CLAUDE_API_KEY", "CLAUDE_ADMIN_KEY", "CLAUDE_WORKSPACE_ID", "GEMINI_API_KEY", "GROQ_API_KEY",
                "OPENAI_API_KEY", "OPENAI_ADMIN_KEY", "OPENAI_API_KEY_IDS", "GITHUB_CLIENT_ID", "GITHUB_CLIENT_SECRET",
                "GITHUB_LINK_CLIENT_ID", "GITHUB_LINK_CLIENT_SECRET", "GITHUB_WEBHOOK_SECRET", "KAKAO_CLIENT_ID",
                "KAKAO_CLIENT_SECRET", "TOSS_SECRET_KEY", "MAIL_USERNAME", "MAIL_PASSWORD"};
        for (String k : keys) {
            if (System.getenv(k) == null) registry.add(k, () -> "test");
        }
        if (System.getenv("JWT_SECRET") == null)
            registry.add("JWT_SECRET", () -> "test-jwt-secret-for-context-load-only-min-32-bytes-0123456789");
    }

    @MockitoBean TossPaymentClient toss;
    @MockitoBean HtmlMailSender mailSender;
    @MockitoSpyBean SubscriptionHistoryRepository historyRepository;

    @Autowired BillingProcessor processor;
    @Autowired SubscriptionRepository subscriptionRepository;
    @Autowired PaymentMethodRepository paymentMethodRepository;
    @Autowired PaymentAttemptRepository attemptRepository;
    @Autowired UserRepository userRepository;
    @PersistenceContext EntityManager em;

    // 만료된 PRO 구독 1건 (시드 사용자에게 붙임)
    Subscription dueSubscription() {
        Long userId = userRepository.findAll().get(0).getId();
        PaymentMethod pm = paymentMethodRepository.save(new PaymentMethod(userId, "ck", "bk", "1234-****"));
        Subscription sub = new Subscription();
        sub.start(userId, PRO_ID, pm.getId());
        ReflectionTestUtils.setField(sub, "expiresAt", LocalDateTime.now().minusMinutes(1).truncatedTo(ChronoUnit.SECONDS));
        return subscriptionRepository.save(sub);
    }

    PaymentAttempt attemptOf(Subscription sub) {
        return attemptRepository.findByOrderId(BillingProcessor.orderIdOf(sub)).orElseThrow();
    }

    TossPaymentApproveResponseDTO done(String orderId) {
        return new TossPaymentApproveResponseDTO("pk", orderId, "DONE", 30000);
    }

    @Test
    void timeout_attemptIsCommittedAndRecovered() {
        Subscription sub = dueSubscription();
        LocalDateTime before = sub.getExpiresAt();
        when(toss.confirmBilling(any(), any(), anyInt(), any(), any()))
                .thenThrow(new ResourceAccessException("Read timed out"));

        processor.processOne(sub.getId(), FREE_ID);

        // 토스 예외에도 시도 기록은 커밋돼 남는다 (수정 전에는 롤백으로 사라졌다)
        PaymentAttempt attempt = attemptOf(sub);
        assertThat(attempt.getStatus()).isEqualTo(PaymentAttempt.Status.REQUESTED);
        Subscription reloaded = subscriptionRepository.findById(sub.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(SubscriptionStatus.ACTIVE);
        assertThat(reloaded.getExpiresAt()).isEqualTo(before);

        // 복구: 토스 조회 결과 승인됨 → 청구 없이 연장
        when(toss.findByOrderId(attempt.getOrderId())).thenReturn(done(attempt.getOrderId()));
        processor.recover(attempt.getId(), FREE_ID);

        assertThat(attemptRepository.findById(attempt.getId()).orElseThrow().getStatus())
                .isEqualTo(PaymentAttempt.Status.DONE);
        assertThat(subscriptionRepository.findById(sub.getId()).orElseThrow().getExpiresAt())
                .isEqualTo(before.plusMonths(1));
        assertThat(historyRepository.findBySubscriptionId(sub.getId()))
                .extracting(SubscriptionHistory::getChangeType).containsExactly(ChangeType.RENEWAL);
        verify(toss, times(1)).confirmBilling(any(), any(), anyInt(), any(), any());
    }

    @Test
    void dbFailureAfterApproval_rolledBackThenRecovered() {
        Subscription sub = dueSubscription();
        LocalDateTime before = sub.getExpiresAt();
        when(toss.confirmBilling(any(), any(), anyInt(), any(), any()))
                .thenAnswer(a -> done(a.getArgument(3)));
        // 이력 INSERT는 실제로 실행한 뒤 예외 → 트랜잭션 2 전체가 롤백돼야 한다
        // (저장소는 인터페이스 프록시라 callRealMethod 불가 → 같은 트랜잭션의 EntityManager로 직접 INSERT)
        doAnswer(a -> { em.persist(a.getArgument(0)); throw new RuntimeException("DB 반영 실패"); })
                .doAnswer(a -> { em.persist(a.getArgument(0)); return a.getArgument(0); })
                .when(historyRepository).save(any());

        try {
            processor.processOne(sub.getId(), FREE_ID);
        } catch (RuntimeException ignored) { } // 스케줄러는 로그만 남긴다

        PaymentAttempt attempt = attemptOf(sub);
        assertThat(attempt.getStatus()).isEqualTo(PaymentAttempt.Status.REQUESTED);
        assertThat(subscriptionRepository.findById(sub.getId()).orElseThrow().getExpiresAt()).isEqualTo(before);
        assertThat(historyRepository.findBySubscriptionId(sub.getId())).isEmpty(); // 실행된 INSERT도 롤백됨

        when(toss.findByOrderId(attempt.getOrderId())).thenReturn(done(attempt.getOrderId()));
        processor.recover(attempt.getId(), FREE_ID);

        assertThat(attemptRepository.findById(attempt.getId()).orElseThrow().getStatus())
                .isEqualTo(PaymentAttempt.Status.DONE);
        assertThat(subscriptionRepository.findById(sub.getId()).orElseThrow().getExpiresAt())
                .isEqualTo(before.plusMonths(1));
        assertThat(historyRepository.findBySubscriptionId(sub.getId())).hasSize(1);
        verify(toss, times(1)).confirmBilling(any(), any(), anyInt(), any(), any()); // 재청구 없음
    }
}
