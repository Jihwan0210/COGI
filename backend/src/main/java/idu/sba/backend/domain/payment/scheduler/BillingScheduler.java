package idu.sba.backend.domain.payment.scheduler;

import idu.sba.backend.domain.payment.entity.Subscription;
import idu.sba.backend.domain.payment.entity.SubscriptionStatus;
import idu.sba.backend.domain.payment.repository.PlanRepository;
import idu.sba.backend.domain.payment.repository.SubscriptionRepository;
import lombok.RequiredArgsConstructor;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

@Component
@RequiredArgsConstructor
public class BillingScheduler {

    private final SubscriptionRepository subscriptionRepository;
    private final PlanRepository planRepository;
    private final BillingProcessor billingProcessor; // 별도 빈 주입 → 프록시 경유해야 @Transactional 적용됨

    @Scheduled(cron = "0 0 0 * * *") // 매일 자정
    // 다중 인스턴스 중복 청구 방지 — 한 인스턴스만 실행
    @SchedulerLock(name = "runBilling", lockAtMostFor = "PT30M", lockAtLeastFor = "PT1M")
    public void runBilling() {
        Long freeId = planRepository.findByName("FREE").orElseThrow().getId();
        var due = subscriptionRepository.findByStatusAndExpiresAtLessThanEqual(
                SubscriptionStatus.ACTIVE, LocalDateTime.now());

        for (Subscription sub : due) {
            try {
                billingProcessor.processOne(sub.getId(), freeId); // id만 넘김 (안에서 재조회)
            } catch (Exception e) {
                // 정기결제 실패 → FREE 강등 (별도 트랜잭션)
                // 한 건 실패가 배치 전체를 죽이면 안 됨
                try {
                    billingProcessor.handlePaymentFailure(sub.getId(), freeId);
                } catch (Exception ignore) {
                    // log.error("downgrade after billing failure failed subId={}", sub.getId(), ignore);
                }
                // log.error("billing failed subId={}", sub.getId(), e);
            }
        }
    }
}
