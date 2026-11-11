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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/** Run prepare and verify in separate Gradle invocations against the same isolated MariaDB. */
@SpringBootTest(classes = NoticeMailTradeoffDbTest.JpaTestConfig.class)
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:mariadb://127.0.0.1:13308/cogi_notice_test",
        "spring.datasource.username=root",
        "spring.datasource.password=notice_test_only",
        "spring.datasource.driver-class-name=org.mariadb.jdbc.Driver",
        "spring.jpa.hibernate.ddl-auto=update"
})
@Import({AdminNoticeServiceImpl.class, NoticeMailTaskWriter.class})
@EnabledIfEnvironmentVariable(named = "COGI_NOTICE_STAGE", matches = "prepare|verify|measure")
class NoticeMailTradeoffDbTest {
    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan(basePackageClasses = {Notice.class, NoticeMailTask.class})
    @EnableJpaRepositories(basePackageClasses = {NoticeRepository.class, NoticeMailTaskRepository.class})
    static class JpaTestConfig {}

    @Autowired NoticeRepository notices;
    @Autowired NoticeMailTaskRepository tasks;
    @Autowired AdminNoticeServiceImpl service;
    @MockitoBean UserRepository users;
    @MockitoBean NotificationService notifications;

    @Test
    @EnabledIfEnvironmentVariable(named = "COGI_NOTICE_STAGE", matches = "prepare")
    void preparePendingTasks() {
        Notice notice = notices.save(Notice.create(1L, "NOTICE_RESTART_PROBE", "본문", 2, true));
        tasks.saveAll(List.of(
                NoticeMailTask.of(notice.getId(), "a@example.com"),
                NoticeMailTask.of(notice.getId(), "b@example.com")));
        assertThat(tasks.countByNoticeIdAndStatus(notice.getId(), Status.PENDING)).isEqualTo(2);
        System.out.println("NOTICE_RESTART_PREPARED noticeId=" + notice.getId() + " pending=2");
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "COGI_NOTICE_STAGE", matches = "verify")
    void verifyPendingTasksAfterNewJvm() {
        Notice notice = notices.findAll().stream()
                .filter(n -> "NOTICE_RESTART_PROBE".equals(n.getSubject()))
                .findFirst().orElseThrow();
        assertThat(tasks.countByNoticeIdAndStatus(notice.getId(), Status.PENDING)).isEqualTo(2);
        System.out.println("NOTICE_RESTART_VERIFIED noticeId=" + notice.getId() + " pending=2");
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "COGI_NOTICE_STAGE", matches = "measure")
    void measureRequestWorkBeforeAndAfterOutbox() {
        List<User> recipients = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            User user = mock(User.class);
            when(user.getStatus()).thenReturn(UserStatus.ACTIVE);
            when(user.getId()).thenReturn((long) i + 1);
            when(user.getEmail()).thenReturn("user" + i + "@example.com");
            recipients.add(user);
        }
        when(users.findAll()).thenReturn(recipients);

        ThreadPoolTaskExecutor oldExecutor = new ThreadPoolTaskExecutor();
        oldExecutor.setCorePoolSize(1);
        oldExecutor.setMaxPoolSize(2);
        oldExecutor.setQueueCapacity(20);
        oldExecutor.initialize();
        try {
            for (int i = 0; i < 5; i++) {
                legacySend(recipients, oldExecutor);
                service.sendNoticeToAll(1L, "긴급", "본문", true);
            }
            List<Double> oldMs = new ArrayList<>();
            List<Double> newMs = new ArrayList<>();
            for (int i = 0; i < 20; i++) {
                if (i % 2 == 0) {
                    oldMs.add(timeMs(() -> legacySend(recipients, oldExecutor)));
                    newMs.add(timeMs(() -> service.sendNoticeToAll(1L, "긴급", "본문", true)));
                } else {
                    newMs.add(timeMs(() -> service.sendNoticeToAll(1L, "긴급", "본문", true)));
                    oldMs.add(timeMs(() -> legacySend(recipients, oldExecutor)));
                }
            }
            System.out.printf("NOTICE_REQUEST_LOCAL 100 recipients, 20 runs each: old p95=%.2fms, outbox p95=%.2fms%n",
                    p95(oldMs), p95(newMs));
        } finally {
            oldExecutor.shutdown();
        }
    }

    private void legacySend(List<User> recipients, ThreadPoolTaskExecutor executor) {
        Notice notice = notices.save(Notice.create(1L, "긴급", "본문", recipients.size(), true));
        List<Long> ids = recipients.stream().map(User::getId).toList();
        notifications.broadcast(ids, "🚨", "긴급", "본문", "/app", notice.getId());
        NoticeMailContent.render("긴급", "본문");
        executor.execute(() -> {});
    }

    private static double timeMs(Runnable work) {
        long start = System.nanoTime();
        work.run();
        return (System.nanoTime() - start) / 1_000_000.0;
    }

    private static double p95(List<Double> ms) {
        ms.sort(Comparator.naturalOrder());
        return ms.get((int) Math.ceil(ms.size() * 0.95) - 1);
    }
}
