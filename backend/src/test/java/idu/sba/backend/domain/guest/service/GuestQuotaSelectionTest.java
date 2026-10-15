package idu.sba.backend.domain.guest.service;

import idu.sba.backend.domain.guest.repository.GuestQuotaRepository;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class GuestQuotaSelectionTest {
    @ParameterizedTest
    @ValueSource(strings = {"default", "redis", "db"})
    void selectsExactlyOneStore(String backend) {
        try (var context = new AnnotationConfigApplicationContext()) {
            if (!backend.equals("default")) context.getEnvironment().getPropertySources()
                    .addFirst(new MapPropertySource("test", Map.of("guest.quota.store", backend)));
            context.registerBean(StringRedisTemplate.class, () -> mock(StringRedisTemplate.class));
            context.registerBean(GuestQuotaRepository.class, () -> mock(GuestQuotaRepository.class));
            context.registerBean(PlatformTransactionManager.class, () -> mock(PlatformTransactionManager.class));
            context.register(RedisGuestQuotaStore.class, DbGuestQuotaStore.class);
            context.refresh();
            assertThat(context.getBeansOfType(GuestQuotaStore.class)).hasSize(1);
            assertThat(context.getBean(GuestQuotaStore.class)).isInstanceOf(
                    backend.equals("db") ? DbGuestQuotaStore.class : RedisGuestQuotaStore.class);
        }
    }
}
