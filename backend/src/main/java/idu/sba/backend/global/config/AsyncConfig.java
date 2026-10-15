package idu.sba.backend.global.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

// PR 웹훅 리뷰(API-024)를 웹훅 응답과 분리해서 처리하기 위한 비동기 실행기.
// GitHub는 웹훅 응답을 짧은 시간 내에 기다리는데, AI 호출(최대 60초 read timeout)을 동기로 하면
// GitHub가 타임아웃으로 보고 재전송할 수 있고 그러면 크레딧이 중복 소모될 위험이 있다.
// 알려진 한계: 인메모리 큐라 JVM이 죽으면 유실됨 — 실제 메시지 큐가 필요하지만 이번 스택엔 브로커가 없어 범위 밖.
@Configuration
@EnableAsync
public class AsyncConfig {

    @Bean(name = "prReviewExecutor")
    public Executor prReviewExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("pr-review-");
        executor.initialize();
        return executor;
    }

}
