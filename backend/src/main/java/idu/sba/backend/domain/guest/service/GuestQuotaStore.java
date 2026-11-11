package idu.sba.backend.domain.guest.service;

import java.time.Duration;
import java.util.Optional;

// 두 저장소가 동일하게 지켜야 하는 체험 정책
public interface GuestQuotaStore {
    int TRIAL_LIMIT = 3;
    Duration TRIAL_WINDOW = Duration.ofHours(24);

    // 확보 성공: 해당 기간의 정보. 한도 초과: Optional.empty()
    Optional<Reservation> tryAcquire(String guestToken);

    // 모든 AI 모델이 실패하면 한 번만 호출. 이전 기간의 반환은 무시한다.
    void refund(Reservation reservation);

    // 토큰만으로 반환하면, 만료 전 요청이 새 기간의 횟수를 차감할 수 있다.
    record Reservation(String guestToken, String windowId) { }
}