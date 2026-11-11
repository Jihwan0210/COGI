package idu.sba.backend.domain.guest.service;

import idu.sba.backend.domain.guest.repository.GuestQuotaRepository;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import java.time.Duration;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

// 전용 컨테이너만 사용한다. 실제 앱 설정이나 기존 DB는 로드하지 않는다.
final class GuestQuotaTestFixture implements AutoCloseable {
    final GuestQuotaStore store;
    private final LettuceConnectionFactory connection;
    private final StringRedisTemplate redis;
    private final JdbcTemplate jdbc;
    private final HikariDataSource dataSource;
    private final Set<String> tokens = new HashSet<>();

    GuestQuotaTestFixture(String backend) {
        if (backend.equals("redis")) {
            dataSource = null;
            connection = new LettuceConnectionFactory("127.0.0.1", 16379);
            connection.afterPropertiesSet();
            connection.start();
            redis = new StringRedisTemplate(connection);
            jdbc = null;
            store = new RedisGuestQuotaStore(redis);
        } else if (backend.equals("db")) {
            connection = null;
            redis = null;
            var config = new HikariConfig();
            config.setJdbcUrl("jdbc:mariadb://127.0.0.1:13307/guest_quota_test");
            config.setUsername("root");
            config.setPassword("quota-test-only");
            config.setMaximumPoolSize(20);
            config.setMinimumIdle(10);
            dataSource = new HikariDataSource(config);
            jdbc = new JdbcTemplate(dataSource);
            jdbc.execute("""
                    CREATE TABLE IF NOT EXISTS guest_quotas (
                        guest_token VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin PRIMARY KEY,
                        used_count INT NOT NULL,
                        window_id VARCHAR(36) NOT NULL,
                        expires_at DATETIME(6) NOT NULL
                    ) ENGINE=InnoDB
                    """);
            store = new DbGuestQuotaStore(new GuestQuotaRepository(dataSource),
                    new DataSourceTransactionManager(dataSource));
        } else {
            throw new IllegalArgumentException("backend must be redis or db: " + backend);
        }
    }

    String token() {
        String token = "quota-test-" + UUID.randomUUID();
        tokens.add(token);
        return token;
    }

    long count(String token) {
        if (redis != null) {
            Object value = redis.opsForHash().get(RedisGuestQuotaStore.key(token), "used");
            return value == null ? 0 : Long.parseLong(value.toString());
        }
        return jdbc.queryForObject("SELECT used_count FROM guest_quotas WHERE guest_token = ?",
                Long.class, token);
    }

    long remainingMillis(String token) {
        if (redis != null) return redis.getExpire(RedisGuestQuotaStore.key(token), java.util.concurrent.TimeUnit.MILLISECONDS);
        return jdbc.queryForObject("""
                SELECT TIMESTAMPDIFF(MICROSECOND, CURRENT_TIMESTAMP(6), expires_at) DIV 1000
                FROM guest_quotas WHERE guest_token = ?
                """, Long.class, token);
    }

    void shortenWindow(String token) {
        if (redis != null) redis.expire(RedisGuestQuotaStore.key(token), Duration.ofSeconds(60));
        else jdbc.update("UPDATE guest_quotas SET expires_at = TIMESTAMPADD(SECOND, 60, CURRENT_TIMESTAMP(6)) WHERE guest_token = ?", token);
    }

    void expire(String token) {
        if (redis != null) redis.delete(RedisGuestQuotaStore.key(token));
        else jdbc.update("UPDATE guest_quotas SET expires_at = TIMESTAMPADD(SECOND, -1, CURRENT_TIMESTAMP(6)) WHERE guest_token = ?", token);
    }

    void legacyCounter(String token, int count) {
        redis.opsForValue().set(RedisGuestQuotaStore.key(token), Integer.toString(count), Duration.ofSeconds(60));
    }

    @Override
    public void close() {
        try {
            for (String token : tokens) {
                if (redis != null) redis.delete(RedisGuestQuotaStore.key(token));
                else jdbc.update("DELETE FROM guest_quotas WHERE guest_token = ?", token);
            }
        } finally {
            if (connection != null) connection.destroy();
            if (dataSource != null) dataSource.close();
        }
    }
}
