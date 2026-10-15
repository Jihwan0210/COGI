package idu.sba.backend.domain.admin.service;

import idu.sba.backend.domain.admin.entity.Notice;
import idu.sba.backend.domain.admin.entity.NoticeMailTask;
import idu.sba.backend.domain.admin.entity.NoticeMailTask.Status;
import idu.sba.backend.domain.admin.entity.NoticeStatus;
import idu.sba.backend.domain.admin.repository.NoticeMailTaskRepository;
import idu.sba.backend.domain.admin.repository.NoticeRepository;
import idu.sba.backend.global.mail.HtmlMailSender;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class NoticeMailWorker {
    private static final int BATCH_SIZE = 50;
    private static final int MAX_ATTEMPTS = 3;
    private static final int LEASE_MINUTES = 5;

    private final NoticeMailTaskRepository taskRepository;
    private final NoticeRepository noticeRepository;
    private final HtmlMailSender mailSender;

    // DB 작업은 서버 재시작 뒤에도 남는다. 만료된 lease는 다시 가져와 처리한다.
    @Scheduled(fixedDelayString = "${notice.mail.poll-delay-ms:5000}")
    public void runPending() {
        LocalDateTime now = LocalDateTime.now();
        for (Long id : taskRepository.findReadyIds(Status.PENDING, Status.IN_PROGRESS, now,
                PageRequest.of(0, BATCH_SIZE))) {
            try {
                process(id);
            } catch (Exception e) {
                log.error("공지 메일 작업 처리 오류 taskId={}", id, e);
            }
        }
        // 마지막 발송 뒤 결과 집계 전에 종료됐더라도 다음 실행에서 이력을 확정한다.
        for (Notice notice : noticeRepository.findByStatusAndUrgentTrue(NoticeStatus.SENDING)) {
            finishNoticeIfComplete(notice.getId());
        }
    }

    void process(Long id) {
        LocalDateTime now = LocalDateTime.now();
        String token = UUID.randomUUID().toString();
        if (taskRepository.claim(id, Status.PENDING, Status.IN_PROGRESS, now,
                now.plusMinutes(LEASE_MINUTES), token) == 0) return;

        NoticeMailTask task = taskRepository.findById(id).orElse(null);
        if (task == null) return; // 공지가 삭제되면서 작업도 삭제됨
        Notice notice = noticeRepository.findById(task.getNoticeId()).orElse(null);
        if (notice == null || task.getAttemptCount() > MAX_ATTEMPTS) {
            taskRepository.markFailure(id, Status.IN_PROGRESS, Status.FAILED, now, token);
            return;
        }

        try {
            mailSender.send(task.getEmail(), "[COGI 공지] " + notice.getSubject(),
                    NoticeMailContent.render(notice.getSubject(), notice.getContent()));
        } catch (Exception e) {
            Status next = task.getAttemptCount() >= MAX_ATTEMPTS ? Status.FAILED : Status.PENDING;
            LocalDateTime retryAt = now.plusMinutes(task.getAttemptCount());
            taskRepository.markFailure(id, Status.IN_PROGRESS, next, retryAt, token);
            log.warn("공지 메일 발송 실패 noticeId={}, taskId={}, attempt={}: {}",
                    task.getNoticeId(), id, task.getAttemptCount(), e.getMessage());
            return;
        }
        // SMTP 수락 후 이 DB 쓰기가 실패하면 재시도 시 중복 메일 가능성이 있다.
        taskRepository.markSent(id, Status.IN_PROGRESS, Status.SENT, token);
    }

    void finishNoticeIfComplete(Long noticeId) {
        long sent = taskRepository.countByNoticeIdAndStatus(noticeId, Status.SENT);
        long failed = taskRepository.countByNoticeIdAndStatus(noticeId, Status.FAILED);
        noticeRepository.findById(noticeId).ifPresent(notice -> {
            if (notice.getStatus() == NoticeStatus.SENDING && sent + failed == notice.getRecipientCount()) {
                notice.markSent(Math.toIntExact(sent), Math.toIntExact(failed));
                noticeRepository.save(notice);
            }
        });
    }
}
