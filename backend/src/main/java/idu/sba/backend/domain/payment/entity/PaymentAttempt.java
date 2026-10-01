package idu.sba.backend.domain.payment.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "payment_attempts")
@Getter
@NoArgsConstructor
public class PaymentAttempt { // 정기결제 1회차 시도 기록 — 토스 호출 전에 먼저 커밋해서 "요청을 보냈다"는 흔적을 남긴다

    public enum Status {
        REQUESTED,     // 토스 결과 미확정 (호출 전/타임아웃/5xx/승인 후 DB 반영 실패) → 복구 스케줄러가 조회로 확정
        DONE,          // 승인 + 구독 연장 완료
        FAILED,        // 토스가 명확히 거절 → FREE 강등
        MANUAL_REVIEW  // 오래 확정되지 않음 → 사람이 토스 콘솔에서 확인
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_id", nullable = false, unique = true, length = 64)
    private String orderId; // 멱등키. 같은 구독의 같은 회차는 항상 같은 값 (UNIQUE로 중복 기록도 차단)

    @Column(name = "subscription_id", nullable = false)
    private Long subscriptionId;

    @Column(nullable = false)
    private Integer amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "resolved_at")
    private LocalDateTime resolvedAt; // 결과 확정 시각 (created_at과의 차이 = 복구 시간)

    @Version
    private Long version; // 같은 시도를 동시에 확정하려 하면 한쪽은 커밋 실패 → 중복 연장 방지

    public PaymentAttempt(String orderId, Long subscriptionId, Integer amount) {
        this.orderId = orderId;
        this.subscriptionId = subscriptionId;
        this.amount = amount;
        this.status = Status.REQUESTED;
        this.createdAt = LocalDateTime.now();
    }

    public void done() { resolve(Status.DONE); }

    public void fail() { resolve(Status.FAILED); }

    public void manualReview() { resolve(Status.MANUAL_REVIEW); }

    private void resolve(Status status) {
        this.status = status;
        this.resolvedAt = LocalDateTime.now();
    }
}
