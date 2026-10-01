package idu.sba.backend.domain.payment.repository;

import idu.sba.backend.domain.payment.entity.PaymentAttempt;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface PaymentAttemptRepository extends JpaRepository<PaymentAttempt, Long> {
    // 이번 회차 시도가 이미 있는지 (재청구 방지)
    Optional<PaymentAttempt> findByOrderId(String orderId);

    // 복구 대상: 결과 미확정 + 일정 시간 지난 시도
    List<PaymentAttempt> findByStatusAndCreatedAtLessThanEqual(PaymentAttempt.Status status, LocalDateTime dateTime);
}
