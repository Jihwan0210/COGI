package idu.sba.backend.domain.admin.service;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;

@Component
@RequiredArgsConstructor
public class NoticeMailTaskWriter {
    private static final String INSERT = """
            insert into notice_mail_tasks
                (notice_id, email, status, next_attempt_at, attempt_count)
            values (?, ?, 'PENDING', ?, 0)
            """;

    private final JdbcTemplate jdbcTemplate;

    // 공지 트랜잭션에 참여한다. IDENTITY 엔티티를 한 건씩 저장하는 대신 DB batch로 기록한다.
    public void enqueue(Long noticeId, List<String> emails) {
        Timestamp readyAt = Timestamp.valueOf(LocalDateTime.now());
        jdbcTemplate.batchUpdate(INSERT, emails, 500, (statement, email) -> {
            statement.setLong(1, noticeId);
            statement.setString(2, email);
            statement.setTimestamp(3, readyAt);
        });
    }
}
