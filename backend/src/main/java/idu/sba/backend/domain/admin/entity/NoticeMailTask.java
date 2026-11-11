package idu.sba.backend.domain.admin.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "notice_mail_tasks", indexes = {
        @Index(name = "idx_notice_mail_ready", columnList = "status,next_attempt_at"),
        @Index(name = "idx_notice_mail_notice", columnList = "notice_id")
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class NoticeMailTask {
    public enum Status { PENDING, IN_PROGRESS, SENT, FAILED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "notice_id", nullable = false)
    private Long noticeId;

    @Column(nullable = false)
    private String email;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.PENDING;

    @Column(name = "next_attempt_at", nullable = false)
    private LocalDateTime nextAttemptAt;

    private LocalDateTime leaseUntil;
    private String claimToken;

    @Column(nullable = false)
    private int attemptCount;

    public static NoticeMailTask of(Long noticeId, String email) {
        NoticeMailTask task = new NoticeMailTask();
        task.noticeId = noticeId;
        task.email = email;
        task.nextAttemptAt = LocalDateTime.now();
        return task;
    }
}
