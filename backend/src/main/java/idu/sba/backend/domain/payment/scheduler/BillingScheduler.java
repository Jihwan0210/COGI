package idu.sba.backend.domain.payment.scheduler;

import idu.sba.backend.domain.payment.entity.PaymentAttempt;
import idu.sba.backend.domain.payment.entity.Subscription;
import idu.sba.backend.domain.payment.entity.SubscriptionStatus;
import idu.sba.backend.domain.payment.repository.PaymentAttemptRepository;
import idu.sba.backend.domain.payment.repository.PlanRepository;
import idu.sba.backend.domain.payment.repository.SubscriptionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;

@Component
@RequiredArgsConstructor
@Slf4j
public class BillingScheduler {

    // 복구 주기. 복구 시간(p95)은 대부분 이 값으로 결정된다
    static final long RECOVERY_INTERVAL_SEC = 10;
    // 토스가 아직 처리 중일 수 있어 미확정 시도는 이만큼 지난 뒤부터 조회
    static final long LOOKUP_DELAY_SEC = 5;

    private final SubscriptionRepository subscriptionRepository;
    private final PlanRepository planRepository;
    private final BillingProcessor billingProcessor; // 별도 빈 주입 → 프록시 경유
    private final PaymentAttemptRepository attemptRepository;

    // 매일 자정 만료 구독을 일괄 청구
    @Scheduled(cron = "0 0 0 * * *") // 매일 자정
    public void runBilling() {
        Long freeId = planRepository.findByName("FREE").orElseThrow().getId();
        var due = subscriptionRepository.findByStatusAndExpiresAtLessThanEqual(
                SubscriptionStatus.ACTIVE, LocalDateTime.now());

        for (Subscription sub : due) {
            try {
                billingProcessor.processOne(sub.getId(), freeId); // id만 넘김 (안에서 재조회)
            } catch (Exception e) {
                // 한 건 실패가 배치 전체를 죽이면 안 됨.
                // 강등하지 않는다: 결제 결과는 processOne 안에서 판정하고, 미확정 건은 복구 스케줄러가 확정
                log.error("정기결제 처리 실패 subId={}", sub.getId(), e);
            }
        }
    }

    // 결과 미확정 시도를 토스 조회로 확정 (응답 유실 / 승인 후 DB 반영 실패 복구)
    @Scheduled(fixedDelay = RECOVERY_INTERVAL_SEC, timeUnit = TimeUnit.SECONDS)
    public void recoverPending() {
        var pending = attemptRepository.findByStatusAndCreatedAtLessThanEqual(
                PaymentAttempt.Status.REQUESTED, LocalDateTime.now().minusSeconds(LOOKUP_DELAY_SEC));
        if (pending.isEmpty()) return;

        Long freeId = planRepository.findByName("FREE").orElseThrow().getId();
        for (PaymentAttempt attempt : pending) {
            try {
                billingProcessor.recover(attempt.getId(), freeId);
            } catch (Exception e) {
                // 조회 실패 → REQUESTED 유지, 다음 주기에 재시도
                log.warn("정기결제 복구 조회 실패 orderId={}: {}", attempt.getOrderId(), e.getMessage());
            }
        }
    }
}
