package idu.sba.backend.domain.payment.scheduler;

import idu.sba.backend.domain.payment.client.TossPaymentClient;
import idu.sba.backend.domain.payment.dto.TossPaymentApproveResponseDTO;
import idu.sba.backend.domain.payment.entity.*;
import idu.sba.backend.domain.payment.repository.PaymentAttemptRepository;
import idu.sba.backend.domain.payment.repository.PaymentMethodRepository;
import idu.sba.backend.domain.payment.repository.PlanRepository;
import idu.sba.backend.domain.payment.repository.SubscriptionHistoryRepository;
import idu.sba.backend.domain.payment.repository.SubscriptionRepository;
import idu.sba.backend.domain.user.repository.UserRepository;
import idu.sba.backend.global.mail.HtmlMailSender;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.HttpClientErrorException;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

// 정기결제 1건 처리. 토스 호출은 DB 트랜잭션 밖에서 한다:
//   [트랜잭션 1] 시도 기록(REQUESTED) 커밋 → [밖] 토스 호출 → [트랜잭션 2] 결과 반영
// 토스 호출이 트랜잭션 안에 있으면 타임아웃 시 "요청을 보냈다"는 기록까지 롤백돼 복구할 근거가 사라진다.
// 같은 클래스 안 메서드 호출은 @Transactional 프록시를 안 타므로 TransactionTemplate으로 경계를 직접 잡는다.
@Service
@RequiredArgsConstructor
@Slf4j
public class BillingProcessor {

    // 이 시간 안에 확정 못 한 시도는 사람이 확인
    static final Duration MANUAL_REVIEW_AFTER = Duration.ofHours(24);

    //어떤 사용자의 어떤 구독인지
    private final SubscriptionRepository subscriptionRepository;
    //결제에 사용할 빌링키,  고객키 조회
    private final PaymentMethodRepository paymentMethodRepository;
    //구독 요금제의 이름 가격 조회
    private final PlanRepository planRepository;
    //사용제 요금제 변경, 이메일 주소 조회
    private final UserRepository userRepository;
    //갱신 취소 등의 구독 변경 이력 저장
    private final SubscriptionHistoryRepository historyRepository;
    //토스에 HTTP로 결제 승인 요청
    private final TossPaymentClient tossPaymentClient;
    //사용자에게 결제 안내메일 발송
    private final HtmlMailSender htmlMailSender;
    //회차별 결제 시도 기록 (멱등키 + 복구 근거)
    private final PaymentAttemptRepository attemptRepository;
    //트랜잭션 경계를 직접 나누기 위해 사용
    private final PlatformTransactionManager transactionManager;

    public void processOne(Long subId, Long freeId) { //구독 한건을 처리하는 메서드
        // [트랜잭션 1] 해지 만료 강등 또는 이번 회차 시도 기록 저장
        PaymentAttempt attempt = tx().execute(status -> {
            Subscription sub = subscriptionRepository.findById(subId).orElseThrow();
            // 해지 예약 + 기간 만료 → FREE 강등 (CANCEL 이력 남김)
            if (sub.getCancelledAt() != null) {
                downgradeToFree(sub, freeId);
                return null;
            }
            String orderId = orderIdOf(sub);
            // 이번 회차 시도가 이미 있으면 결과 미확정 건 → 재청구하지 않고 복구 스케줄러에 맡긴다
            if (attemptRepository.findByOrderId(orderId).isPresent()) return null;
            Plan plan = planRepository.findById(sub.getPlanId()).orElseThrow();
            return attemptRepository.save(new PaymentAttempt(orderId, subId, plan.getPrice()));
        });
        if (attempt != null) charge(attempt, freeId);
    }

    // 복구: 결과 미확정(REQUESTED) 시도를 토스 조회로 확정. 복구 스케줄러가 호출
    public void recover(Long attemptId, Long freeId) {
        PaymentAttempt attempt = attemptRepository.findById(attemptId).orElseThrow();
        if (attempt.getStatus() != PaymentAttempt.Status.REQUESTED) return;

        if (attempt.getCreatedAt().isBefore(LocalDateTime.now().minus(MANUAL_REVIEW_AFTER))) {
            tx().executeWithoutResult(status ->
                    attemptRepository.findById(attemptId).ifPresent(PaymentAttempt::manualReview));
            log.error("정기결제 장기 미확정 → 수동 확인 필요 orderId={}", attempt.getOrderId());
            return;
        }

        TossPaymentApproveResponseDTO res;
        try {
            res = tossPaymentClient.findByOrderId(attempt.getOrderId());
        } catch (HttpClientErrorException.NotFound e) {
            // 토스에 주문 없음 = 승인 전에 유실 → 같은 주문번호로 다시 청구 (멱등키라 중복 승인 없음)
            charge(attempt, freeId);
            return;
        }
        // 조회 자체가 타임아웃/5xx면 예외가 스케줄러로 올라가 REQUESTED 유지 → 다음 주기에 재조회

        switch (res.status()) {
            case "DONE" -> markPaid(attemptId);                     // 승인돼 있었음 → 청구 없이 연장
            case "ABORTED", "EXPIRED" -> markFailed(attemptId, freeId); // 승인 실패로 확정
            default -> log.info("정기결제 처리 중 orderId={} status={}", attempt.getOrderId(), res.status());
        }
    }

    // [트랜잭션 밖] 토스 호출 → 결과에 따라 반영
    private void charge(PaymentAttempt attempt, Long freeId) {
        Subscription sub = subscriptionRepository.findById(attempt.getSubscriptionId()).orElseThrow();
        Plan plan = planRepository.findById(sub.getPlanId()).orElseThrow(); //어떤 요금제를 결제할지 pro max
        PaymentMethod pm = paymentMethodRepository.findById(sub.getPaymentMethodId()).orElseThrow(); //빌링키등
        try {
            tossPaymentClient.confirmBilling(
                    pm.getBillingKey(), pm.getCustomerKey(), attempt.getAmount(),
                    attempt.getOrderId(), plan.getName() + " 정기결제");
        } catch (HttpClientErrorException e) {
            if (isDuplicateOrder(e)) {
                // 같은 주문번호가 이미 처리됨 = 앞선 요청이 승인됐을 수 있음 → 조회로 확정
                log.warn("정기결제 중복 주문 응답, 복구 조회 대기 orderId={}", attempt.getOrderId());
                return;
            }
            // 4xx = 토스가 명확히 거절 (한도 초과 등) → 강등
            log.warn("정기결제 거절 orderId={}: {}", attempt.getOrderId(), e.getResponseBodyAsString());
            markFailed(attempt.getId(), freeId);
            return;
        } catch (Exception e) {
            // 타임아웃/5xx = 승인됐는지 모름 → 강등하지 않고 REQUESTED 유지, 복구 조회로 확정
            log.warn("정기결제 결과 불명, 복구 조회 대기 orderId={}: {}", attempt.getOrderId(), e.getMessage());
            return;
        }
        // 여기서 DB 반영이 실패해도 시도 기록은 REQUESTED로 남아 복구 조회 대상이 된다
        markPaid(attempt.getId());
    }

    // [트랜잭션 2] 승인 반영: 연장 + RENEWAL 이력 + 시도 DONE
    private void markPaid(Long attemptId) {
        Long subId = tx().execute(status -> {
            PaymentAttempt attempt = attemptRepository.findById(attemptId).orElseThrow();
            if (attempt.getStatus() != PaymentAttempt.Status.REQUESTED) return null; // 이미 반영됨 (중복 연장 방지)
            Subscription sub = subscriptionRepository.findById(attempt.getSubscriptionId()).orElseThrow();
            // 정기결제 = RENEWAL (기존엔 UPGRADE로 잘못 기록)
            saveHistory(sub.getId(), sub.getPlanId(), sub.getPlanId(), ChangeType.RENEWAL, attempt.getAmount());
            sub.extend(); // 다음 달로 (더티체킹으로 저장)
            attempt.done();
            return sub.getId();
        });
        if (subId != null) notifyPaid(subId);
    }

    // [트랜잭션 2] 거절 반영: 시도 FAILED + FREE 강등
    private void markFailed(Long attemptId, Long freeId) {
        tx().executeWithoutResult(status -> {
            PaymentAttempt attempt = attemptRepository.findById(attemptId).orElseThrow();
            if (attempt.getStatus() != PaymentAttempt.Status.REQUESTED) return;
            attempt.fail();
            downgradeToFree(subscriptionRepository.findById(attempt.getSubscriptionId()).orElseThrow(), freeId);
        });
    }

    // 회차 = 구독 + 만료일. 재시도해도 같은 값, 연장되면 다음 회차 값 (멱등키)
    static String orderIdOf(Subscription sub) {
        return "SUB-" + sub.getId() + "-" + sub.getExpiresAt().format(DateTimeFormatter.BASIC_ISO_DATE);
    }

    // 응답 본문 문자열로 판별. 토스 에러 코드가 바뀌면 여기만 수정
    private static boolean isDuplicateOrder(HttpClientErrorException e) {
        String body = e.getResponseBodyAsString();
        return body.contains("ALREADY_PROCESSED_PAYMENT") || body.contains("DUPLICATED_ORDER_ID");
    }

    private TransactionTemplate tx() {
        return new TransactionTemplate(transactionManager);
    }

    // FREE 강등 공통 처리 (해지 만료 / 결제 거절 둘 다) + CANCEL 이력
    private void downgradeToFree(Subscription sub, Long freeId) {
        Long previousPlanId = sub.getPlanId();
        sub.expire();
        userRepository.findById(sub.getUserId())
                .ifPresent(u -> u.updatePlanId(freeId));
        saveHistory(sub.getId(), previousPlanId, freeId, ChangeType.CANCEL, 0);
    }

    private void saveHistory(Long subId, Long prevPlanId, Long newPlanId, ChangeType type, Integer amount) {
        SubscriptionHistory h = new SubscriptionHistory();
        h.record(subId, prevPlanId, newPlanId, type, amount);
        historyRepository.save(h);
    }

    // 정기결제 청구 메일 (실패해도 결제/연장은 유지)
    private void notifyPaid(Long subId) {
        try {
            Subscription sub = subscriptionRepository.findById(subId).orElseThrow();
            Plan plan = planRepository.findById(sub.getPlanId()).orElseThrow();
            PaymentMethod pm = paymentMethodRepository.findById(sub.getPaymentMethodId()).orElseThrow();
            userRepository.findById(sub.getUserId()).ifPresent(u ->
                    sendBillingMail(u.getEmail(), plan.getName(), plan.getPrice(), pm.getCardMaskedNumber()));
        } catch (Exception e) {
            log.warn("정기결제 청구 메일 발송 실패 subId={}: {}", subId, e.getMessage());
        }
    }

    // 결제 청구 메일 본문
    private void sendBillingMail(String to, String planName, int amount, String cardMasked) {
        String inner = """
            <p style="margin:0 0 8px;color:#1b2a4a;font-size:17px;font-weight:bold;">결제 완료</p>
            <p style="margin:0 0 20px;color:#40507a;font-size:13px;">정기 구독 결제가 정상 처리되었습니다.</p>
            <div style="background:#fdfcf7;border:3px solid #1b2a4a;padding:16px;color:#1b2a4a;font-size:14px;">
              <p style="margin:0 0 6px;">플랜: <b>%s</b></p>
              <p style="margin:0 0 6px;">금액: <b>%,d원</b></p>
              <p style="margin:0;">결제수단: <b>%s</b></p>
            </div>
            """.formatted(planName, amount, cardMasked);
        htmlMailSender.send(to, "[COGI] 정기결제가 완료되었습니다", inner);
    }

}
