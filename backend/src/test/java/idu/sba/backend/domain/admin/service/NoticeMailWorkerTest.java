package idu.sba.backend.domain.admin.service;

import idu.sba.backend.domain.admin.entity.Notice;
import idu.sba.backend.domain.admin.entity.NoticeMailTask;
import idu.sba.backend.domain.admin.entity.NoticeMailTask.Status;
import idu.sba.backend.domain.admin.entity.NoticeStatus;
import idu.sba.backend.domain.admin.repository.NoticeMailTaskRepository;
import idu.sba.backend.domain.admin.repository.NoticeRepository;
import idu.sba.backend.global.mail.HtmlMailSender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class NoticeMailWorkerTest {
    @Mock NoticeMailTaskRepository taskRepository;
    @Mock NoticeRepository noticeRepository;
    @Mock HtmlMailSender mailSender;
    @InjectMocks NoticeMailWorker worker;

    private NoticeMailTask task;
    private Notice notice;

    @BeforeEach
    void setUp() {
        task = mock(NoticeMailTask.class);
        notice = mock(Notice.class);
    }

    private void claimedTask(int attemptCount) {
        when(taskRepository.claim(eq(7L), eq(Status.PENDING), eq(Status.IN_PROGRESS),
                any(LocalDateTime.class), any(LocalDateTime.class), anyString())).thenReturn(1);
        when(taskRepository.findById(7L)).thenReturn(Optional.of(task));
        when(task.getNoticeId()).thenReturn(5L);
        when(task.getAttemptCount()).thenReturn(attemptCount);
        when(noticeRepository.findById(5L)).thenReturn(Optional.of(notice));
        when(task.getEmail()).thenReturn("user@example.com");
        when(notice.getSubject()).thenReturn("긴급");
        when(notice.getContent()).thenReturn("안내");
    }

    @Test
    void failedMailRemainsInDatabaseForRetry() {
        claimedTask(1);
        doThrow(new IllegalStateException("SMTP unavailable"))
                .when(mailSender).send(anyString(), anyString(), anyString());

        worker.process(7L);

        verify(taskRepository).markFailure(eq(7L), eq(Status.IN_PROGRESS), eq(Status.PENDING),
                any(LocalDateTime.class), anyString());
        verify(taskRepository, never()).markSent(anyLong(), any(), any(), anyString());
    }

    @Test
    void thirdFailureStopsAutomaticRetry() {
        claimedTask(3);
        doThrow(new IllegalStateException("SMTP unavailable"))
                .when(mailSender).send(anyString(), anyString(), anyString());

        worker.process(7L);

        verify(taskRepository).markFailure(eq(7L), eq(Status.IN_PROGRESS), eq(Status.FAILED),
                any(LocalDateTime.class), anyString());
    }

    @Test
    void completedTasksFinalizeNoticeCounts() {
        when(taskRepository.countByNoticeIdAndStatus(5L, Status.SENT)).thenReturn(2L);
        when(taskRepository.countByNoticeIdAndStatus(5L, Status.FAILED)).thenReturn(1L);
        when(noticeRepository.findById(5L)).thenReturn(Optional.of(notice));
        when(notice.getStatus()).thenReturn(NoticeStatus.SENDING);
        when(notice.getRecipientCount()).thenReturn(3);

        worker.finishNoticeIfComplete(5L);

        verify(notice).markSent(2, 1);
        verify(noticeRepository).save(notice);
    }
}
