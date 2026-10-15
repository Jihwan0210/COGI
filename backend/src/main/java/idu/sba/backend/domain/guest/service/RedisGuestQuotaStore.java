package idu.sba.backend.domain.guest.service;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "guest.quota.store", havingValue = "redis", matchIfMissing = true)
public class RedisGuestQuotaStore implements GuestQuotaStore {
    private final StringRedisTemplate redisTemplate;

    // Lua 실행 중 다른 요청이 끼어들지 못한다. 검사·증가·TTL 설정을 함께 처리한다.
    private static final DefaultRedisScript<String> ACQUIRE = new DefaultRedisScript<>("""
            if redis.call('TYPE', KEYS[1]).ok == 'string' then
                local used = tonumber(redis.call('GET', KEYS[1]))
                local ttl = redis.call('PTTL', KEYS[1])
                redis.call('DEL', KEYS[1])
                redis.call('HSET', KEYS[1], 'used', math.max(0, used), 'window', ARGV[1])
                if ttl < 1 then ttl = tonumber(ARGV[2]) end
                redis.call('PEXPIRE', KEYS[1], ttl)
            end
            if redis.call('EXISTS', KEYS[1]) == 0 then
                redis.call('HSET', KEYS[1], 'used', 1, 'window', ARGV[1])
                redis.call('PEXPIRE', KEYS[1], ARGV[2])
                return ARGV[1]
            end
            local used = tonumber(redis.call('HGET', KEYS[1], 'used'))
            if used >= tonumber(ARGV[3]) then return nil end
            redis.call('HINCRBY', KEYS[1], 'used', 1)
            return redis.call('HGET', KEYS[1], 'window')
            """, String.class);

    // 0 아래로 내리지 않고, 만료된 키도 새로 만들지 않는다. TTL은 유지한다.
    private static final DefaultRedisScript<Long> REFUND = new DefaultRedisScript<>("""
            if redis.call('HGET', KEYS[1], 'window') == ARGV[1] then
                local used = tonumber(redis.call('HGET', KEYS[1], 'used'))
                if used > 0 then return redis.call('HINCRBY', KEYS[1], 'used', -1) end
            end
            return 0
            """, Long.class);

    @Override
    public Optional<Reservation> tryAcquire(String guestToken) {
        String window = redisTemplate.execute(ACQUIRE, List.of(key(guestToken)),
                UUID.randomUUID().toString(), Long.toString(TRIAL_WINDOW.toMillis()),
                Integer.toString(TRIAL_LIMIT));
        return Optional.ofNullable(window).map(id -> new Reservation(guestToken, id));
    }

    @Override
    public void refund(Reservation reservation) {
        redisTemplate.execute(REFUND, List.of(key(reservation.guestToken())), reservation.windowId());
    }

    // 기존 문자열 카운터는 위 스크립트에서 횟수·남은 TTL을 보존한 채 Hash로 전환한다.
    static String key(String token) { return "guest:count:" + token; }
}
