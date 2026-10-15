package idu.sba.backend.domain.admin.service;

import idu.sba.backend.domain.admin.entity.Notice;
import idu.sba.backend.domain.admin.repository.NoticeMailTaskRepository;
import idu.sba.backend.domain.admin.repository.NoticeRepository;
import idu.sba.backend.domain.notification.service.NotificationService;
import idu.sba.backend.domain.user.entity.User;
import idu.sba.backend.domain.user.entity.UserStatus;
import idu.sba.backend.domain.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AdminNoticeServiceImplTest {
    @Mock UserRepository userRepository;
    @Mock NoticeRepository noticeRepository;
    @Mock NoticeMailTaskRepository taskRepository;
    @Mock NoticeMailTaskWriter taskWriter;
    @Mock NotificationService notificationService;
    @InjectMocks AdminNoticeServiceImpl service;

    @Test
    void urgentNoticePersistsRecipientsBeforeReturning() {
        User active = mock(User.class);
        when(active.getStatus()).thenReturn(UserStatus.ACTIVE);
        when(active.getId()).thenReturn(10L);
        when(active.getEmail()).thenReturn("user@example.com");
        when(userRepository.findAll()).thenReturn(List.of(active));
        Notice saved = mock(Notice.class);
        when(saved.getId()).thenReturn(5L);
        when(noticeRepository.save(any(Notice.class))).thenReturn(saved);

        var response = service.sendNoticeToAll(1L, "긴급", "안내", true);

        assertThat(response.noticeId()).isEqualTo(5L);
        verify(taskWriter).enqueue(5L, List.of("user@example.com"));
        verify(notificationService).broadcast(List.of(10L), "🚨", "긴급", "안내", "/app", 5L);
        verify(saved, never()).markSent(anyInt(), anyInt());
    }
}
