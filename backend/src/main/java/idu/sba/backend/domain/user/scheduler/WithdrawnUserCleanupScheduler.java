package idu.sba.backend.domain.user.scheduler;

import idu.sba.backend.domain.user.entity.UserStatus;
import idu.sba.backend.domain.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

// 탈퇴 회원 보관 30일 — 탈퇴는 우선 소프트삭제(익명화)로 두고, 30일이 지나면 users 행 자체를 지운다.
// 리뷰/PR 등 이력 테이블은 userId를 FK 없이 값으로만 갖고 있고 작성자 이름은 이미 nullable 처리라
// (PrListItemResponseDTO.authorName 주석 참고) 행이 사라져도 이력 조회는 그대로 동작한다.
@Component
@RequiredArgsConstructor
@Slf4j
public class WithdrawnUserCleanupScheduler {

    private static final int RETENTION_DAYS = 30;

    private final UserRepository userRepository;

    // 알림 정리(04:30)·정기결제(00:00) 배치와 시간이 겹치지 않게 05:00
    @Scheduled(cron = "0 0 5 * * *", zone = "Asia/Seoul")
    @SchedulerLock(name = "purgeWithdrawnUsers", lockAtMostFor = "PT10M", lockAtLeastFor = "PT30S")
    @Transactional
    public void purgeWithdrawn() {
        long deleted = userRepository.deleteByStatusAndUpdatedAtBefore(
                UserStatus.WITHDRAWN, LocalDateTime.now().minusDays(RETENTION_DAYS));
        if (deleted > 0) log.info("탈퇴 후 {}일 지난 회원 {}건 영구 삭제", RETENTION_DAYS, deleted);
    }
}
