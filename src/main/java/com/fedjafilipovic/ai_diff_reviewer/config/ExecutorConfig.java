package com.fedjafilipovic.ai_diff_reviewer.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

@Configuration
@EnableConfigurationProperties(AppProperties.class)
public class ExecutorConfig {

    /**
     * Bounded to exactly AppLimits.MAX_CONCURRENT_JOBS with an unbounded
     * queue: a 5th+ concurrent submission is accepted and waits, never
     * rejected (LinkedBlockingQueue never throws RejectedExecutionException).
     */
    @Bean(destroyMethod = "shutdown")
    public ExecutorService jobExecutor() {
        return new ThreadPoolExecutor(
                AppLimits.MAX_CONCURRENT_JOBS, AppLimits.MAX_CONCURRENT_JOBS,
                0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>());
    }
}
