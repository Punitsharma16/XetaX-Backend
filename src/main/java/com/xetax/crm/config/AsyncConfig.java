package com.xetax.crm.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Executors for work that must never sit inside an HTTP request thread.
 *
 * <p>indexingExecutor is deliberately SINGLE-threaded: knowledge indexing is
 * delete-then-add per knowledgeId, so running two reindexes of the same
 * entity in parallel could interleave into duplicate/stale vectors. One
 * worker preserves submission order; the bounded queue absorbs bursts and
 * CallerRunsPolicy degrades to the old synchronous behavior instead of
 * dropping work when the queue is full.
 */
@Configuration
@EnableAsync
public class AsyncConfig {

    @Bean(name = "indexingExecutor")
    public ThreadPoolTaskExecutor indexingExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(500);
        executor.setThreadNamePrefix("knowledge-index-");
        executor.setRejectedExecutionHandler(new java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }

    @Bean(name = "whatsappExecutor")
    public ThreadPoolTaskExecutor whatsappExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(1000);
        executor.setThreadNamePrefix("whatsapp-");
        executor.setRejectedExecutionHandler(new java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }

    /**
     * Bulk email campaign sends when Kafka is unavailable. Sized like the
     * WhatsApp pool but separate from it, so a 5,000-recipient email blast
     * never starves WhatsApp replies (and vice versa).
     */
    @Bean(name = "emailExecutor")
    public ThreadPoolTaskExecutor emailExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(1000);
        executor.setThreadNamePrefix("email-");
        executor.setRejectedExecutionHandler(new java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }

    /**
     * Bot conversations (LLM turns for WhatsApp/website chats). Bounded so a
     * slow model never backs up the webhook thread; overflow is logged and
     * dropped — the customer can always message again.
     */
    @Bean(name = "botExecutor")
    public ThreadPoolTaskExecutor botExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(6);
        executor.setQueueCapacity(2000);
        executor.setThreadNamePrefix("bot-");
        executor.setRejectedExecutionHandler((r, e) ->
                org.slf4j.LoggerFactory.getLogger(AsyncConfig.class)
                        .warn("botExecutor saturated — dropping one bot turn"));
        executor.initialize();
        return executor;
    }

    @Bean(name = "automationExecutor")
    public ThreadPoolTaskExecutor automationExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(1000);
        executor.setThreadNamePrefix("automation-");
        executor.setRejectedExecutionHandler(new java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }
}
