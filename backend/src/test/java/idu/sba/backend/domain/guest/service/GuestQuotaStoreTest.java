package idu.sba.backend.domain.guest.service;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.Optional;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.assertThat;

// 동일한 검증을 실제 Redis와 MariaDB 각각에 적용한다.
@Timeout(60)
class GuestQuotaStoreTest {
    @Test
    void preservesExistingRedisCounterAndExpiry() {
        try (var f = new GuestQuotaTestFixture("redis")) {
            String token = f.token();
            f.legacyCounter(token, 2);
            f.store.tryAcquire(token).orElseThrow();
            assertThat(f.count(token)).isEqualTo(3);
            assertThat(f.remainingMillis(token)).isBetween(1L, 60_000L);
            assertThat(f.store.tryAcquire(token)).isEmpty();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"redis", "db"})
    void allowsExactlyThreeOfFortyConcurrentRequests(String backend) throws Exception {
        try (var fixture = new GuestQuotaTestFixture(backend);
             var executor = Executors.newFixedThreadPool(40)) {
            String token = fixture.token();
            var ready = new CountDownLatch(40);
            var start = new CountDownLatch(1);
            var results = new ArrayList<Future<Optional<GuestQuotaStore.Reservation>>>();
            for (int i = 0; i < 40; i++) results.add(executor.submit(() -> {
                ready.countDown();
                if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("start timeout");
                return fixture.store.tryAcquire(token);
            }));
            boolean allReady = ready.await(10, TimeUnit.SECONDS);
            start.countDown();
            assertThat(allReady).isTrue();
            int allowed = 0;
            for (var result : results) if (result.get(20, TimeUnit.SECONDS).isPresent()) allowed++;
            assertThat(allowed).isEqualTo(3);
            assertThat(fixture.count(token)).isEqualTo(3);
            System.out.printf("[%s] 동시 요청 40건: 허용 %d, 거절 %d, 저장 횟수 %d%n",
                    backend, allowed, 40 - allowed, fixture.count(token));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"redis", "db"})
    void refundAllowsRetryAndDoesNotExtendWindow(String backend) {
        try (var f = new GuestQuotaTestFixture(backend)) {
            String token = f.token();
            var first = f.store.tryAcquire(token).orElseThrow();
            assertThat(f.remainingMillis(token)).isBetween(86_390_000L, 86_400_000L);
            f.shortenWindow(token);
            f.store.refund(first);
            assertThat(f.count(token)).isZero();
            assertThat(f.store.tryAcquire(token)).contains(first);
            assertThat(f.remainingMillis(token)).isBetween(1L, 60_000L);
            f.store.tryAcquire(token).orElseThrow();
            f.store.tryAcquire(token).orElseThrow();
            assertThat(f.store.tryAcquire(token)).isEmpty();
            f.store.refund(first); // 테스트상 두 번째 확보 요청의 실패 반환
            assertThat(f.store.tryAcquire(token)).isPresent();
            assertThat(f.count(token)).isEqualTo(3);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"redis", "db"})
    void expiredWindowResetsAndIgnoresLateRefund(String backend) {
        try (var f = new GuestQuotaTestFixture(backend)) {
            String token = f.token();
            var old = f.store.tryAcquire(token).orElseThrow();
            f.expire(token);
            f.store.refund(old); // 만료 후 반환이 새 키나 음수 횟수를 만들면 안 된다.
            var current = f.store.tryAcquire(token).orElseThrow();
            assertThat(current.windowId()).isNotEqualTo(old.windowId());
            f.store.refund(old);
            assertThat(f.count(token)).isEqualTo(1);
            f.store.tryAcquire(token).orElseThrow();
            f.store.tryAcquire(token).orElseThrow();
            assertThat(f.store.tryAcquire(token)).isEmpty();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"redis", "db"})
    void tokensHaveIndependentLimitsAndRefundNeverGoesNegative(String backend) {
        try (var f = new GuestQuotaTestFixture(backend)) {
            String one = f.token();
            String two = f.token();
            var reservation = f.store.tryAcquire(one).orElseThrow();
            f.store.refund(reservation);
            f.store.refund(reservation);
            assertThat(f.count(one)).isZero();
            for (int i = 0; i < 3; i++) f.store.tryAcquire(two).orElseThrow();
            assertThat(f.store.tryAcquire(two)).isEmpty();
            assertThat(f.store.tryAcquire(one)).isPresent();
        }
    }
}
