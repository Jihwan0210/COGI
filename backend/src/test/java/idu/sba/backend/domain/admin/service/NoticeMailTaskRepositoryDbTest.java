package idu.sba.backend.domain.admin.service;

import idu.sba.backend.domain.admin.entity.Notice;
import idu.sba.backend.domain.admin.entity.NoticeMailTask;
import idu.sba.backend.domain.admin.entity.NoticeMailTask.Status;
import idu.sba.backend.domain.admin.repository.NoticeMailTaskRepository;
import idu.sba.backend.domain.admin.repository.NoticeRepository;
import idu.sba.backend.domain.notification.service.NotificationService;
import idu.sba.backend.domain.user.entity.User;
import idu.sba.backend.domain.user.entity.UserStatus;
import idu.sba.backend.domain.user.repository.UserRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@SpringBootTest(classes = NoticeMailTaskRepositoryDbTest.JpaTestConfig.class)
@ActiveProfiles("test")
@Transactional
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:mariadb://127.0.0.1:13308/cogi_notice_test",
        "spring.datasource.username=root",
        "spring.datasource.password=notice_test_only",
        "spring.datasource.driver-class-name=org.mariadb.jdbc.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "notice.mail.poll-delay-ms=3600000"
})
@EnabledIfEnvironmentVariable(named = "COGI_NOTICE_DB_TEST", matches = "true")
@Import({AdminNoticeServiceImpl.class, NoticeMailTaskWriter.class})
class NoticeMailTaskRepositoryDbTest {
    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan(basePackageClasses = {Notice.class, NoticeMailTask.class})
    @EnableJpaRepositories(basePackageClasses = {NoticeRepository.class, NoticeMailTaskRepository.class})
    static class JpaTestConfig {}

    @DynamicPropertySource
    static void fillMissingEnv(DynamicPropertyRegistry registry) {
        String[] keys = {"CLAUDE_API_KEY", "CLAUDE_ADMIN_KEY", "CLAUDE_WORKSPACE_ID", "GEMINI_API_KEY", "GROQ_API_KEY",
                "OPENAI_API_KEY", "OPENAI_ADMIN_KEY", "OPENAI_API_KEY_IDS", "GITHUB_CLIENT_ID", "GITHUB_CLIENT_SECRET",
                "GITHUB_LINK_CLIENT_ID", "GITHUB_LINK_CLIENT_SECRET", "GITHUB_WEBHOOK_SECRET", "KAKAO_CLIENT_ID",
                "KAKAO_CLIENT_SECRET", "TOSS_SECRET_KEY", "MAIL_USERNAME", "MAIL_PASSWORD"};
        for (String key : keys) {
            if (System.getenv(key) == null) registry.add(key, () -> "test");
        }
        if (System.getenv("JWT_SECRET") == null)
            registry.add("JWT_SECRET", () -> "test-jwt-secret-for-context-load-only-min-32-bytes-0123456789");
    }

    @Autowired NoticeMailTaskRepository tasks;
    @Autowired NoticeRepository notices;
    @Autowired EntityManager em;
    @Autowired AdminNoticeServiceImpl service;
    @MockitoBean UserRepository users;
    @MockitoBean NotificationService notifications;

    @Test
    void persistedTasksCanBeClaimedOnlyOnce() {
        Notice notice = notices.save(Notice.create(1L, "긴급", "안내", 2, true));
        tasks.saveAll(List.of(
                NoticeMailTask.of(notice.getId(), "a@example.com"),
                NoticeMailTask.of(notice.getId(), "b@example.com")));
        em.flush();
        em.clear(); // 새 작업자가 DB에서 다시 읽는 상황

        LocalDateTime now = LocalDateTime.now();
        List<Long> ready = tasks.findReadyIds(Status.PENDING, Status.IN_PROGRESS,
                now, PageRequest.of(0, 50));
        assertThat(ready).hasSize(2);

        Long id = ready.getFirst();
        assertThat(tasks.claim(id, Status.PENDING, Status.IN_PROGRESS,
                now, now.plusMinutes(5), "worker-a")).isEqualTo(1);
        assertThat(tasks.claim(id, Status.PENDING, Status.IN_PROGRESS,
                now, now.plusMinutes(5), "worker-b")).isZero();
        em.clear();
        assertThat(tasks.findById(id).orElseThrow().getStatus()).isEqualTo(Status.IN_PROGRESS);
        assertThat(tasks.markSent(id, Status.IN_PROGRESS, Status.SENT, "worker-a")).isEqualTo(1);
        assertThat(tasks.markSent(id, Status.IN_PROGRESS, Status.SENT, "worker-b")).isZero();

        Long expiredId = ready.get(1);
        assertThat(tasks.claim(expiredId, Status.PENDING, Status.IN_PROGRESS,
                now, now.minusSeconds(1), "stopped-worker")).isEqualTo(1);
        // 서버가 종료돼 lease가 만료된 작업은 다른 작업자가 다시 가져올 수 있다.
        assertThat(tasks.claim(expiredId, Status.PENDING, Status.IN_PROGRESS,
                now, now.plusMinutes(5), "restarted-worker")).isEqualTo(1);
        em.clear();
        assertThat(tasks.findById(expiredId).orElseThrow().getAttemptCount()).isEqualTo(2);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void failedNotificationRollsBackNoticeAndMailTasksTogether() {
        User user = mock(User.class);
        when(user.getStatus()).thenReturn(UserStatus.ACTIVE);
        when(user.getId()).thenReturn(10L);
        when(user.getEmail()).thenReturn("a@example.com");
        when(users.findAll()).thenReturn(List.of(user));
        doThrow(new IllegalStateException("notification DB failure"))
                .when(notifications).broadcast(anyList(), anyString(), anyString(), anyString(), anyString(), any());

        assertThatThrownBy(() -> service.sendNoticeToAll(1L, "긴급", "안내", true))
                .isInstanceOf(IllegalStateException.class);

        assertThat(notices.count()).isZero();
        assertThat(tasks.count()).isZero();
    }
}
