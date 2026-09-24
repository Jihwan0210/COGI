package idu.sba.backend.domain.payment.scheduler;


import idu.sba.backend.domain.payment.entity.Plan;
import idu.sba.backend.domain.payment.entity.Subscription;
import idu.sba.backend.domain.payment.entity.SubscriptionStatus;
import idu.sba.backend.domain.payment.repository.PlanRepository;
import idu.sba.backend.domain.payment.repository.SubscriptionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

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
        order.verify(billingProcessor).processOne(10L, 1L); //10번 처리
        order.verify(billingProcessor).handlePaymentFailure(10L, 1L); //10번 실패 처리
        order.verify(billingProcessor).processOne(20L, 1L); // 20번 처리 확인

        // 정상 처리된 두 번째 구독에는 실패 처리가 없어야 함
        verify(billingProcessor, never()).handlePaymentFailure(20L, 1L);





    }
}
