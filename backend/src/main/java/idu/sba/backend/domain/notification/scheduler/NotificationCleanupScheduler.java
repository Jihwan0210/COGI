package idu.sba.backend.domain.notification.scheduler;

import idu.sba.backend.domain.notification.repository.NotificationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

// 인앱 알림 보관 30일 — 지난 것은 매일 새벽 배치로 삭제(공지 fan-out으로 무한정 쌓이는 것 방지).
@Component
@RequiredArgsConstructor
@Slf4j
public class NotificationCleanupScheduler {

    private static final int RETENTION_DAYS = 30;

    private final NotificationRepository notificationRepository;

    // @Scheduled 호출은 프록시 경유라 @Transactional 적용됨(@Modifying 벌크 삭제에 필요)
    @Scheduled(cron = "0 30 4 * * *") // 매일 04:30 (정기결제 배치와 시간 겹치지 않게)
    @SchedulerLock(name = "purgeExpiredNotifications", lockAtMostFor = "PT10M", lockAtLeastFor = "PT30S")
    @Transactional
    public void purgeExpired() {
        int deleted = notificationRepository.deleteByCreatedAtBefore(
                LocalDateTime.now().minusDays(RETENTION_DAYS));
        if (deleted > 0) log.info("{}일 지난 알림 {}건 삭제", RETENTION_DAYS, deleted);
    }
}
