package idu.sba.backend.domain.payment.scheduler;


import idu.sba.backend.domain.payment.client.TossPaymentClient;
import idu.sba.backend.domain.payment.entity.PaymentMethod;
import idu.sba.backend.domain.payment.entity.Plan;
import idu.sba.backend.domain.payment.entity.Subscription;
import idu.sba.backend.domain.payment.entity.SubscriptionStatus;
import idu.sba.backend.domain.payment.entity.PaymentAttempt;
import idu.sba.backend.domain.payment.repository.PaymentAttemptRepository;
import idu.sba.backend.domain.payment.repository.PaymentMethodRepository;
import idu.sba.backend.domain.payment.repository.PlanRepository;
import idu.sba.backend.domain.payment.repository.SubscriptionHistoryRepository;
import idu.sba.backend.domain.payment.repository.SubscriptionRepository;
import idu.sba.backend.domain.user.repository.UserRepository;
import idu.sba.backend.global.mail.HtmlMailSender;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.client.ResourceAccessException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class BillingSchedulerTest {


    @Mock
    private SubscriptionRepository subscriptionRepository;

    @Mock
    private PlanRepository planRepository;

    @Mock
    private BillingProcessor billingProcessor;

    @Mock
    private PaymentAttemptRepository attemptRepository;


    @InjectMocks //실제 스케줄러에 위 가짜 객체들을 넣어줌
    private BillingScheduler billingScheduler;


    @Test
    void continuesToNextSubscriptionWhenFirstFails() {
        // Free 요금제와 처리할 구독 두 건
        Plan freePlan = mock(Plan.class);
        when(freePlan.getId()).thenReturn(1L);  // when(어떤 메서드를 호출하면).thenReturn(이 값을 반환)
        when(planRepository.findByName("FREE")).thenReturn(Optional.of(freePlan)); // FREE메소드 호출시 freeplan 반환 findByName()의 반환타입이 Optional


        Subscription first = mock(Subscription.class); //첫번째 구독
        Subscription second = mock(Subscription.class); //두번째 구독

        when(first.getId()).thenReturn(10L); //10번
        when(second.getId()).thenReturn(20L); //20번


        when(subscriptionRepository.findByStatusAndExpiresAtLessThanEqual(
                eq(SubscriptionStatus.ACTIVE), //ACTIVE 상태여야함  다른 상태를 조회할 수 있기 때문
                any(LocalDateTime.class) // 시간이 어떤 값이든
        )).thenReturn(List.of(first, second));


        // 첫 번째 구독 처리에서 예외가 발생하도록 설정
        doThrow(new RuntimeException("테스트용 처리 오류")) //예외를 던짐
                .when(billingProcessor) //billingProceesor  스케줄러 안에
                .processOne(10L, 1L); // 구독 ID는 10L , 플랜 ID는 1L(FREE)


        // 실행
        billingScheduler.runBilling(); //스케줄러 로직 실행

        // 호출 순서 검증
        InOrder order = inOrder(billingProcessor); // 호출 순서를 검사함
        order.verify(billingProcessor).processOne(10L, 1L); //10번 처리 (예외 발생)
        order.verify(billingProcessor).processOne(20L, 1L); // 그래도 20번 처리 확인
        // 스케줄러는 예외를 로그만 남기고 강등하지 않는다 (결제 판정은 processOne 안에서)





    }

    /**
     * 타임아웃 시 강등하지 않는지 확인 (수정 전에는 버그 재현 테스트였음)
     * 수정 전: "토스가 타임아웃 나면, 실제로는 승인됐을 수도 있는데 FREE로 강등한다."
     * 수정 후: "결과를 모르면 강등하지 않고 시도 기록을 남겨 복구 조회로 확정한다."
     * 앞 테스트와 차이점:
     * - 앞 테스트: BillingProcessor 자체가 가짜(@Mock) → "무엇이 호출됐나"만 확인
     * - 이 테스트: BillingProcessor를 진짜로 생성 → 안쪽 로직(토스 호출, 강등)이 실제로 돈다
     */
    @Test
    void keepsSubscriptionWhenTossTimesOut() {

        // ===== 1. 진짜 BillingProcessor 만들기 =====
        // BillingProcessor는 생성자로 부품 9개를 받는다 (@RequiredArgsConstructor).
        // subscriptionRepository, planRepository는 클래스 위 @Mock 필드를 재사용하고,
        // 나머지 5개는 여기서 가짜로 만든다.
        PaymentMethodRepository paymentMethodRepository = mock(PaymentMethodRepository.class); // 빌링키 조회용
        UserRepository userRepository = mock(UserRepository.class);                            // 강등 시 사용자 요금제 변경용
        SubscriptionHistoryRepository historyRepository = mock(SubscriptionHistoryRepository.class); // 변경 이력 저장용
        TossPaymentClient tossPaymentClient = mock(TossPaymentClient.class);                   // 토스 대신 쓸 가짜 → 타임아웃을 흉내낸다
        HtmlMailSender htmlMailSender = mock(HtmlMailSender.class);                            // 테스트 중 진짜 메일이 나가지 않게
        PlatformTransactionManager txManager = mock(PlatformTransactionManager.class);         // 트랜잭션 경계만 흉내 (DB 없음)

        // new로 직접 생성: @InjectMocks를 쓰면 스케줄러에 "가짜" processor가 들어가서
        // 안쪽 로직이 실행되지 않는다. 그래서 진짜 processor를 손으로 만들어 넣는다.
        // 인자 순서 = BillingProcessor 필드 선언 순서 (Lombok이 그 순서로 생성자를 만든다)
        BillingProcessor realProcessor = new BillingProcessor(
                subscriptionRepository, paymentMethodRepository, planRepository,
                userRepository, historyRepository, tossPaymentClient, htmlMailSender,
                attemptRepository, txManager);

        // 스케줄러도 진짜 processor를 넣어 직접 생성
        BillingScheduler scheduler = new BillingScheduler(
                subscriptionRepository, planRepository, realProcessor, attemptRepository);


        // ===== 2. 요금제 준비 =====
        // 스케줄러는 시작할 때 findByName("FREE")로 FREE 요금제 ID를 구한다 → 1번
        Plan freePlan = mock(Plan.class);
        when(freePlan.getId()).thenReturn(1L);
        when(planRepository.findByName("FREE")).thenReturn(Optional.of(freePlan));

        // processOne은 구독의 요금제를 findById로 조회해서 가격·이름으로 결제 요청을 만든다 → 2번 PRO
        Plan proPlan = mock(Plan.class);
        when(proPlan.getPrice()).thenReturn(9900);  // 결제 금액
        when(proPlan.getName()).thenReturn("PRO");  // 주문명 "PRO 정기결제"에 쓰임
        when(planRepository.findById(2L)).thenReturn(Optional.of(proPlan));


        // ===== 3. 만료된 구독 한 건 준비 =====
        Subscription sub = mock(Subscription.class);
        when(sub.getId()).thenReturn(10L);             // 구독 ID (주문번호 SUB-10-... 에도 쓰임)
        when(sub.getPlanId()).thenReturn(2L);          // PRO 요금제를 쓰는 구독
        when(sub.getPaymentMethodId()).thenReturn(5L); // 5번 결제수단으로 결제
        when(sub.getExpiresAt()).thenReturn(LocalDateTime.of(2026, 10, 1, 0, 0)); // 주문번호(멱등키) SUB-10-20261001
        // getCancelledAt()은 설정 안 함 → mock 기본값 null → "해지 예약 없음"
        // → processOne이 해지 강등 분기를 건너뛰고 정기결제 분기로 간다 (우리가 원하는 경로)

        // 스케줄러가 "만료된 ACTIVE 구독 목록"을 조회하면 이 한 건만 돌려준다
        when(subscriptionRepository.findByStatusAndExpiresAtLessThanEqual(any(), any()))
                .thenReturn(List.of(sub));
        // processOne은 ID로 구독을 다시 조회한다 → 같은 sub를 돌려준다
        when(subscriptionRepository.findById(10L)).thenReturn(Optional.of(sub));


        // ===== 4. 결제수단, 사용자 준비 =====
        // 빌링키·고객키는 설정 안 함 → null. 토스가 가짜라 값이 무엇이든 상관없다.
        PaymentMethod pm = mock(PaymentMethod.class);
        when(paymentMethodRepository.findById(5L)).thenReturn(Optional.of(pm));

        // 결제 시도 기록: 이번 회차 기록은 아직 없고, 저장하면 ID 1번이 붙는다
        when(attemptRepository.findByOrderId("SUB-10-20261001")).thenReturn(Optional.empty());
        when(attemptRepository.save(any())).thenAnswer(a -> {
            PaymentAttempt saved = a.getArgument(0);
            ReflectionTestUtils.setField(saved, "id", 1L);
            return saved;
        });


        // ===== 5. 핵심: 토스 타임아웃 흉내 =====
        // 실제 상황: 토스는 결제를 승인했는데 응답이 늦게 와서 우리 쪽 RestClient가 포기한 경우.
        // RestClient는 이때 ResourceAccessException을 던진다.
        // any()/anyInt(): 어떤 빌링키·금액·주문번호로 호출하든 무조건 예외
        when(tossPaymentClient.confirmBilling(any(), any(), anyInt(), any(), any()))
                .thenThrow(new ResourceAccessException("Read timed out"));


        // ===== 실행 =====
        // 흐름: processOne → 시도 기록 저장 → 토스 호출 → 타임아웃 → 결과 불명으로 보고 대기
        scheduler.runBilling();


        // ===== 검증 =====
        // 결제 요청은 토스로 나갔다 (= 승인됐을 수 있다)
        verify(tossPaymentClient).confirmBilling(any(), any(), anyInt(), eq("SUB-10-20261001"), any());
        // 수정 전: 여기서 expire() + updatePlanId(1L)로 FREE 강등됐다 (버그)
        // 수정 후: 결과를 모르니 강등하지 않는다
        verify(sub, never()).expire();
        verify(userRepository, never()).findById(any());
        // 이번 회차 시도 기록은 REQUESTED로 남아 복구 스케줄러가 조회로 확정한다
    }
}
