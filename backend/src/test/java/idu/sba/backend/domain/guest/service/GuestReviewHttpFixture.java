package idu.sba.backend.domain.guest.service;

import idu.sba.backend.domain.guest.controller.GuestReviewController;
import idu.sba.backend.domain.review.repository.AiUsageLogRepository;
import idu.sba.backend.domain.review.repository.ReviewIssueRepository;
import idu.sba.backend.domain.review.repository.ReviewRepository;
import idu.sba.backend.domain.review.service.PromptBuilder;
import idu.sba.backend.global.ai.*;
import idu.sba.backend.global.exception.BusinessException;
import idu.sba.backend.global.exception.ErrorCode;
import idu.sba.backend.global.exception.GlobalExceptionHandler;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.servlet.context.AnnotationConfigServletWebServerApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.web.servlet.DispatcherServlet;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import tools.jackson.databind.ObjectMapper;

import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;


// HTTP 서버, 실제 Redis, 가짜 AI를 준비하고 정리하는 테스트 전용 도구.
// 애플리케이션 전체/DB/보안 필터는 실행하지 않는다.
final class GuestReviewHttpFixture implements AutoCloseable {
    private final AnnotationConfigServletWebServerApplicationContext context =
            new AnnotationConfigServletWebServerApplicationContext();
    private final List<CompletableFuture<HttpResponse<String>>> pending = new ArrayList<>();
    private final GuestQuotaTestFixture quota;
    private final String token;

    private final StringRedisTemplate redis;
    private final ControlledAi ai;
    private final ObjectMapper mapper;
    private final URI endpoint;
    private final HttpClient client;

    GuestReviewHttpFixture(String backend) {
        quota = new GuestQuotaTestFixture(backend);
        token = quota.token();
        context.registerBean(GuestQuotaStore.class, () -> quota.store);
        context.register(TestServer.class);
        try {
            context.refresh();
            redis = context.getBean(StringRedisTemplate.class);
            ai = context.getBean(ControlledAi.class);
            mapper = context.getBean(ObjectMapper.class);
            endpoint = URI.create("http://127.0.0.1:" + context.getWebServer().getPort()
                    + "/api/guest/local-review");

            client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                    .connectTimeout(Duration.ofSeconds(3)).build();
        } catch (RuntimeException | Error e) {
            context.close();
            quota.close();
            throw e;
        }
    }

    void startThreeRequests() throws InterruptedException {
        for (String code : List.of("fail", "hold-1", "hold-2")) {
            pending.add(client.sendAsync(request(code), HttpResponse.BodyHandlers.ofString()));
        }
        assertThat(ai.entered.await(15, TimeUnit.SECONDS))
                .as("첫 요청 3건이 모두 AI 처리에 진입해야 함").isTrue();
    }

    int post(String code) throws Exception {
        return client.send(request(code), HttpResponse.BodyHandlers.ofString()).statusCode();
    }

    int failOneRequest() throws Exception {
        ai.failRelease.countDown();
        return pending.getFirst().get(15, TimeUnit.SECONDS).statusCode();
    }

    List<Integer> finishOtherRequests() throws Exception {
        ai.successRelease.countDown();
        return List.of(pending.get(1).get(15, TimeUnit.SECONDS).statusCode(),
                pending.get(2).get(15, TimeUnit.SECONDS).statusCode());
    }

    long count() { return quota.count(token); }
    int aiCalls() { return ai.calls.get(); }

    private HttpRequest request(String code) {
        return HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(45))
                .header("Content-Type", "application/json").header("Cookie", "guest_token=" + token)
                .POST(HttpRequest.BodyPublishers.ofString(
                        mapper.writeValueAsString(Map.of("code", code, "level", "BEGINNER"))))
                .build();
    }

    @Override
    public void close() {
        ai.failRelease.countDown();
        ai.successRelease.countDown();
        try {
            for (var response : pending) {
                try { response.get(15, TimeUnit.SECONDS); }
                catch (Exception ignored) { /* 실패한 테스트도 정리는 수행 */ }
            }
            List<String> keys = new ArrayList<>();

            try (var cursor = redis.scan(ScanOptions.scanOptions()
                    .match("guest:review:" + token + ":*").count(100).build())) {
                cursor.forEachRemaining(keys::add);
            }
            if (!keys.isEmpty()) redis.delete(keys);
        } finally {
            try { client.close(); }
            finally { try { quota.close(); } finally { context.close(); } }
        }
    }

    static class ControlledAi implements AiReviewClient {
        final CountDownLatch entered = new CountDownLatch(3);
        final CountDownLatch failRelease = new CountDownLatch(1);
        final CountDownLatch successRelease = new CountDownLatch(1);
        final AtomicInteger calls = new AtomicInteger();

        @Override
        public AiReviewResult review(AiModel model, String prompt, String code, String language,
                                     AiInputType inputType) {
            calls.incrementAndGet();
            if (model == AiModel.GEMINI_FLASH && (code.equals("fail") || code.startsWith("hold-"))) {
                entered.countDown();
            }
            if (code.equals("fail")) {
                await(failRelease);
                throw new BusinessException(ErrorCode.AI_MODEL_CALL_FAILED);
            }
            if (code.startsWith("hold-")) await(successRelease);
            return new AiReviewResult("Test review", List.of(), 0, 0, 0, true);
        }

        private static void await(CountDownLatch latch) {
            try {
                if (!latch.await(40, TimeUnit.SECONDS)) throw new IllegalStateException("AI gate timed out");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }

        @Override
        public AiGenerationResult generate(AiModel model, String prompt, String content) {
            throw new UnsupportedOperationException("Not used by this experiment");
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    @EnableWebMvc
    static class TestServer {
        @Bean
        TomcatServletWebServerFactory webServerFactory() throws Exception {
            var factory = new TomcatServletWebServerFactory(0);
            factory.setAddress(InetAddress.getByName("127.0.0.1"));
            return factory;
        }

        @Bean DispatcherServlet dispatcherServlet() { return new DispatcherServlet(); }
        @Bean ObjectMapper objectMapper() { return new ObjectMapper(); }
        @Bean ControlledAi aiClient() { return new ControlledAi(); }
        @Bean GlobalExceptionHandler exceptionHandler() { return new GlobalExceptionHandler(); }

        @Bean
        LettuceConnectionFactory redisConnectionFactory() {
            return new LettuceConnectionFactory(new RedisStandaloneConfiguration("127.0.0.1", 16379),
                    LettuceClientConfiguration.builder().commandTimeout(Duration.ofSeconds(3)).build());
        }

        @Bean
        StringRedisTemplate redisTemplate(LettuceConnectionFactory factory) {
            return new StringRedisTemplate(factory);
        }

        @Bean
        GuestReviewService guestReviewService(StringRedisTemplate redis, ObjectMapper mapper, ControlledAi ai, GuestQuotaStore quota) {
            PromptBuilder prompts = mock(PromptBuilder.class);
            when(prompts.build(any(), eq("FREE"))).thenReturn("Test prompt");
            when(prompts.readGroqLanguageRule()).thenReturn("Test fallback prompt");
            return new GuestReviewService(redis, mapper, ai, mock(ReviewRepository.class),
                    mock(ReviewIssueRepository.class), prompts, mock(AiUsageLogRepository.class), quota);
        }

        @Bean
        GuestReviewController guestReviewController(GuestReviewService service) {
            return new GuestReviewController(service);
        }
    }
}
