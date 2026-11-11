package idu.sba.backend.domain.guest.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import javax.sql.DataSource;

@Repository
public class GuestQuotaRepository {
    private final JdbcTemplate jdbc;

    public GuestQuotaRepository(DataSource dataSource) { jdbc = new JdbcTemplate(dataSource); }

    // MariaDB UPSERT: 최초 행 생성 경쟁을 기본키로 처리하고, 만료된 행은 재사용한다.
    // expires_at을 마지막에 바꿔야 앞의 IF들도 기존 만료 시각을 기준으로 판단한다.
    public void prepareWindow(String token, String windowId, long seconds) {
        jdbc.update("""
                INSERT INTO guest_quotas (guest_token, used_count, window_id, expires_at)
                VALUES (?, 0, ?, TIMESTAMPADD(SECOND, ?, CURRENT_TIMESTAMP(6)))
                ON DUPLICATE KEY UPDATE
                  window_id = IF(expires_at <= CURRENT_TIMESTAMP(6), VALUES(window_id), window_id),
                  used_count = IF(expires_at <= CURRENT_TIMESTAMP(6), 0, used_count),
                  expires_at = IF(expires_at <= CURRENT_TIMESTAMP(6), VALUES(expires_at), expires_at)
                """, token, windowId, seconds);
    }

    // 읽고 +1 해서 저장하는 대신, 한도 검사와 증가를 하나의 UPDATE로 수행한다.
    public boolean incrementBelowLimit(String token, int limit) {
        return jdbc.update("""
                UPDATE guest_quotas SET used_count = used_count + 1
                WHERE guest_token = ? AND used_count < ?
                """, token, limit) == 1;
    }

    public String windowId(String token) {
        return jdbc.queryForObject("SELECT window_id FROM guest_quotas WHERE guest_token = ?",
                String.class, token);
    }

    public void refund(String token, String windowId) {
        jdbc.update("""
                UPDATE guest_quotas SET used_count = used_count - 1
                WHERE guest_token = ? AND window_id = ? AND used_count > 0
                  AND expires_at > CURRENT_TIMESTAMP(6)
                """, token, windowId);
    }
}
