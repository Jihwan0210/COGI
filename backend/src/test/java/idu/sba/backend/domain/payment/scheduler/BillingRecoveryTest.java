package idu.sba.backend.domain.payment.scheduler;

import idu.sba.backend.domain.payment.client.TossPaymentClient;
import idu.sba.backend.domain.payment.dto.TossPaymentApproveResponseDTO;
import idu.sba.backend.domain.payment.entity.*;
import idu.sba.backend.domain.payment.repository.*;
import idu.sba.backend.domain.user.entity.User;
import idu.sba.backend.domain.user.repository.UserRepository;
import idu.sba.backend.global.mail.HtmlMailSender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.BeanUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 정기결제 장애 복구 테스트.
 * DB 대신 메모리 Map, 토스 대신 "승인 장부"를 가진 가짜 토스로 실제 흐름(청구 → 장애 → 복구)을 돌린다.
 * 장부(FakeToss.approved)가 정답: 여기 있으면 실제로 돈이 빠진 것.
 * 한계: 진짜 트랜잭션 롤백은 없다. 롤백까지 검증하려면 테스트 DB 통합 테스트가 필요하다.
 */
class BillingRecoveryTest {

    static final long FREE_ID = 1L;
    static final long PRO_ID = 2L;
    static final int PRICE = 9900;

    // 토스가 이번 주문을 어떻게 처리할지
    enum Fault {
        NONE,                // 정상 승인
        LOST_AFTER_APPROVE,  // 승인했는데 응답 유실 (돈은 빠짐)
        LOST_BEFORE_APPROVE, // 승인 전에 유실 (돈 안 빠짐)
        REJECT               // 카드 거절
    }

    static class FakeToss extends TossPaymentClient {
        final Set<String> approved = new HashSet<>(); // 토스 쪽 승인 장부 = 정답
        final Set<String> seen = new HashSet<>();
        Fault fault = Fault.NONE;
        int confirmCalls, lookupCalls;

        FakeToss() { super(null); }

        @Override
        public TossPaymentApproveResponseDTO confirmBilling(String billingKey, String customerKey, int amount,
                                                            String orderId, String orderName) {
            confirmCalls++;
            if (approved.contains(orderId)) throw error(HttpStatus.BAD_REQUEST, "ALREADY_PROCESSED_PAYMENT"); // 같은 주문 중복 승인 차단
            boolean first = seen.add(orderId); // 장애는 주문별 첫 호출에만 발생
            switch (fault) {
                case REJECT -> throw error(HttpStatus.BAD_REQUEST, "REJECT_CARD_PAYMENT");
                case LOST_BEFORE_APPROVE -> {
                    if (first) throw new ResourceAccessException("Read timed out");
                }
                case LOST_AFTER_APPROVE -> {
                    approved.add(orderId); // 돈 빠짐
                    if (first) throw new ResourceAccessException("Read timed out"); // 응답만 유실
                }
                case NONE -> { }
            }
            approved.add(orderId);
            return new TossPaymentApproveResponseDTO("pk-" + orderId, orderId, "DONE", amount);
        }

        @Override
        public TossPaymentApproveResponseDTO findByOrderId(String orderId) {
            lookupCalls++;
            if (!approved.contains(orderId)) throw error(HttpStatus.NOT_FOUND, "NOT_FOUND_PAYMENT");
            return new TossPaymentApproveResponseDTO("pk-" + orderId, orderId, "DONE", PRICE);
        }

        // 구독별 승인 건수 (2 이상 = 이중 청구)
        long approvalsOf(long subId) {
            return approved.stream().filter(o -> o.startsWith("SUB-" + subId + "-")).count();
        }

        static HttpClientErrorException error(HttpStatus status, String code) {
            byte[] body = ("{\"code\":\"" + code + "\"}").getBytes(StandardCharsets.UTF_8);
            return HttpClientErrorException.create(status, status.getReasonPhrase(), HttpHeaders.EMPTY, body, StandardCharsets.UTF_8);
        }
    }

    final Map<Long, Subscription> subs = new HashMap<>();
    final Map<Long, User> users = new HashMap<>();
    final Map<Long, PaymentAttempt> attempts = new HashMap<>();
    final AtomicLong attemptSeq = new AtomicLong();
    final FakeToss toss = new FakeToss();
    final SubscriptionHistoryRepository historyRepository = mock(SubscriptionHistoryRepository.class);
    BillingProcessor processor;
    BillingScheduler scheduler;

    @BeforeEach
    void setUp() {
        SubscriptionRepository subRepo = mock(SubscriptionRepository.class);
        when(subRepo.findById(anyLong())).thenAnswer(a -> Optional.ofNullable(subs.get(a.<Long>getArgument(0))));
        when(subRepo.findByStatusAndExpiresAtLessThanEqual(any(), any())).thenAnswer(a -> subs.values().stream()
                .filter(s -> s.getStatus() == a.getArgument(0) && !s.getExpiresAt().isAfter(a.getArgument(1)))
                .toList());

        PaymentAttemptRepository attemptRepo = mock(PaymentAttemptRepository.class);
        when(attemptRepo.save(any())).thenAnswer(a -> {
            PaymentAttempt at = a.getArgument(0);
            if (at.getId() == null) ReflectionTestUtils.setField(at, "id", attemptSeq.incrementAndGet());
            attempts.put(at.getId(), at);
            return at;
        });
        when(attemptRepo.findById(anyLong())).thenAnswer(a -> Optional.ofNullable(attempts.get(a.<Long>getArgument(0))));
        when(attemptRepo.findByOrderId(any())).thenAnswer(a -> attempts.values().stream()
                .filter(at -> at.getOrderId().equals(a.getArgument(0))).findFirst());
        when(attemptRepo.findByStatusAndCreatedAtLessThanEqual(any(), any())).thenAnswer(a -> attempts.values().stream()
                .filter(at -> at.getStatus() == a.getArgument(0) && !at.getCreatedAt().isAfter(a.getArgument(1)))
                .toList());

        PlanRepository planRepo = mock(PlanRepository.class);
        Plan free = mock(Plan.class);
        when(free.getId()).thenReturn(FREE_ID);
        Plan pro = mock(Plan.class);
        when(pro.getPrice()).thenReturn(PRICE);
        when(pro.getName()).thenReturn("PRO");
        when(planRepo.findByName("FREE")).thenReturn(Optional.of(free));
        when(planRepo.findById(PRO_ID)).thenReturn(Optional.of(pro));

        PaymentMethodRepository pmRepo = mock(PaymentMethodRepository.class);
        when(pmRepo.findById(anyLong())).thenReturn(Optional.of(mock(PaymentMethod.class)));
        UserRepository userRepo = mock(UserRepository.class);
        when(userRepo.findById(anyLong())).thenAnswer(a -> Optional.ofNullable(users.get(a.<Long>getArgument(0))));

        processor = new BillingProcessor(subRepo, pmRepo, planRepo, userRepo, historyRepository, toss,
                mock(HtmlMailSender.class), attemptRepo, mock(PlatformTransactionManager.class));
        scheduler = new BillingScheduler(subRepo, planRepo, processor, attemptRepo);
    }

    // 만료된 PRO 구독 n건 (구독 i = 사용자 i)
    void givenDueSubscriptions(int n) {
        for (long i = 1; i <= n; i++) {
            Subscription s = new Subscription();
            ReflectionTestUtils.setField(s, "id", i);
            ReflectionTestUtils.setField(s, "userId", i);
            ReflectionTestUtils.setField(s, "planId", PRO_ID);
            ReflectionTestUtils.setField(s, "paymentMethodId", 5L);
            ReflectionTestUtils.setField(s, "status", SubscriptionStatus.ACTIVE);
            ReflectionTestUtils.setField(s, "expiresAt", LocalDateTime.now().minusMinutes(1));
            subs.put(i, s);
            User u = BeanUtils.instantiateClass(User.class);
            u.updatePlanId(PRO_ID);
            users.put(i, u);
        }
    }

    PaymentAttempt onlyAttempt() {
        assertThat(attempts).hasSize(1);
        return attempts.values().iterator().next();
    }

    void recoverAll() {
        new ArrayList<>(attempts.keySet()).forEach(id -> processor.recover(id, FREE_ID));
    }

    @Test
    void approvedButResponseLost_recoveredWithoutCharging() {
        givenDueSubscriptions(1);
        LocalDateTime before = subs.get(1L).getExpiresAt();
        toss.fault = Fault.LOST_AFTER_APPROVE;

        scheduler.runBilling();
        assertThat(subs.get(1L).getStatus()).isEqualTo(SubscriptionStatus.ACTIVE); // 강등 안 함
        assertThat(onlyAttempt().getStatus()).isEqualTo(PaymentAttempt.Status.REQUESTED);

        recoverAll();
        assertThat(onlyAttempt().getStatus()).isEqualTo(PaymentAttempt.Status.DONE);
        assertThat(subs.get(1L).getExpiresAt()).isEqualTo(before.plusMonths(1)); // 연장
        assertThat(toss.confirmCalls).isEqualTo(1); // 재청구 없음
        assertThat(toss.approvalsOf(1)).isEqualTo(1);
    }

    @Test
    void lostBeforeApproval_rechargedOnce() {
        givenDueSubscriptions(1);
        toss.fault = Fault.LOST_BEFORE_APPROVE;

        scheduler.runBilling();
        recoverAll(); // 조회 404 → 같은 주문번호로 재청구

        assertThat(onlyAttempt().getStatus()).isEqualTo(PaymentAttempt.Status.DONE);
        assertThat(toss.approvalsOf(1)).isEqualTo(1);
    }

    @Test
    void rejected_downgradedToFree() {
        givenDueSubscriptions(1);
        toss.fault = Fault.REJECT;

        scheduler.runBilling();

        assertThat(onlyAttempt().getStatus()).isEqualTo(PaymentAttempt.Status.FAILED);
        assertThat(subs.get(1L).getStatus()).isEqualTo(SubscriptionStatus.CANCELLED);
        assertThat(users.get(1L).getPlanId()).isEqualTo(FREE_ID);
    }

    @Test
    void dbFailureAfterApproval_recovered() {
        givenDueSubscriptions(1);
        LocalDateTime before = subs.get(1L).getExpiresAt();
        when(historyRepository.save(any()))
                .thenThrow(new RuntimeException("DB 연결 끊김"))
                .thenAnswer(a -> a.getArgument(0));

        scheduler.runBilling(); // 승인은 됐는데 반영 실패
        assertThat(onlyAttempt().getStatus()).isEqualTo(PaymentAttempt.Status.REQUESTED);
        assertThat(subs.get(1L).getExpiresAt()).isEqualTo(before);

        recoverAll(); // 조회 DONE → 반영
        assertThat(onlyAttempt().getStatus()).isEqualTo(PaymentAttempt.Status.DONE);
        assertThat(subs.get(1L).getExpiresAt()).isEqualTo(before.plusMonths(1));
        assertThat(toss.approvalsOf(1)).isEqualTo(1);
    }

    @Test
    void nextDailyRun_doesNotChargeAgain() {
        givenDueSubscriptions(1);
        toss.fault = Fault.LOST_AFTER_APPROVE;

        scheduler.runBilling();
        scheduler.runBilling(); // 복구 전에 다음 날 배치가 또 돌아도

        assertThat(toss.confirmCalls).isEqualTo(1);
        assertThat(attempts).hasSize(1);
    }

    @Test
    void unresolvedFor24Hours_goesToManualReview() {
        givenDueSubscriptions(1);
        toss.fault = Fault.LOST_AFTER_APPROVE;
        scheduler.runBilling();
        ReflectionTestUtils.setField(onlyAttempt(), "createdAt", LocalDateTime.now().minusHours(25));

        recoverAll();

        assertThat(onlyAttempt().getStatus()).isEqualTo(PaymentAttempt.Status.MANUAL_REVIEW);
        assertThat(toss.lookupCalls).isZero();
        assertThat(subs.get(1L).getStatus()).isEqualTo(SubscriptionStatus.ACTIVE);
    }

    /**
     * 실험: 승인 후 응답 유실 N건 주입 → 복구 스케줄러를 실제 주기(RECOVERY_INTERVAL_SEC)로 돌려 측정.
     * 실제 시간으로 기다리므로 약 RECOVERY_INTERVAL_SEC초 걸린다.
     * 복구 시간 = 시도 기록 생성 ~ DONE 확정. 대부분 복구 주기 설정값으로 결정된다.
     */
    @Test
    void experiment_responseLostAfterApproval() throws InterruptedException {
        int n = 100;
        givenDueSubscriptions(n);
        toss.fault = Fault.LOST_AFTER_APPROVE;

        scheduler.runBilling();
        scheduler.runBilling(); // 복구 전 재실행 (중복 청구 유발 시도)
        for (int tick = 0; tick < 5 && attempts.values().stream()
                .anyMatch(a -> a.getStatus() == PaymentAttempt.Status.REQUESTED); tick++) {
            Thread.sleep(Duration.ofSeconds(BillingScheduler.RECOVERY_INTERVAL_SEC));
            scheduler.recoverPending();
        }

        long wrongFree = subs.values().stream()
                .filter(s -> toss.approvalsOf(s.getId()) > 0 && s.getStatus() == SubscriptionStatus.CANCELLED).count();
        long doubleCharged = subs.keySet().stream().filter(id -> toss.approvalsOf(id) > 1).count();
        long unresolved = attempts.values().stream().filter(a -> a.getStatus() != PaymentAttempt.Status.DONE).count();
        List<Long> recoveryMs = attempts.values().stream().filter(a -> a.getResolvedAt() != null)
                .map(a -> Duration.between(a.getCreatedAt(), a.getResolvedAt()).toMillis()).sorted().toList();
        long p95 = recoveryMs.get((int) Math.ceil(recoveryMs.size() * 0.95) - 1);

        System.out.printf("[실험] 응답 유실 %d건 | 복구 주기 %ds, 조회 대기 %ds%n",
                n, BillingScheduler.RECOVERY_INTERVAL_SEC, BillingScheduler.LOOKUP_DELAY_SEC);
        System.out.printf("[실험] 잘못된 FREE 전환 %d건, 이중 청구 %d건, 미확정 %d건%n", wrongFree, doubleCharged, unresolved);
        System.out.printf("[실험] 청구 호출 %d회, 조회 호출 %d회, 복구 시간 p95 %.1f초%n",
                toss.confirmCalls, toss.lookupCalls, p95 / 1000.0);

        assertThat(wrongFree).isZero();
        assertThat(doubleCharged).isZero();
        assertThat(unresolved).isZero();
    }
}
