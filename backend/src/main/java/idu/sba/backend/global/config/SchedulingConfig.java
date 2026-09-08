package idu.sba.backend.global.config;

import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.redis.spring.RedisLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
// 다중 인스턴스로 떠도 스케줄러가 한 번만 돌도록 분산 락 적용
@EnableSchedulerLock(defaultLockAtMostFor = "PT30M")
public class SchedulingConfig {

    // 이미 쓰고 있는 Redis를 락 저장소로 재사용 (별도 인프라 추가 없음)
    @Bean
    public LockProvider lockProvider(RedisConnectionFactory connectionFactory) {
        return new RedisLockProvider(connectionFactory);
    }
}
