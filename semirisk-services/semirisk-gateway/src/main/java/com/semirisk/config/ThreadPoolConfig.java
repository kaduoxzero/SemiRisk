package com.semirisk.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 业务隔离线程池配置。
 * 替代 Executors.newSingleThreadExecutor / newFixedThreadPool，
 * 让线程池受 Spring 生命周期管理，支持优雅关闭和指标采集。
 */
@Configuration
@EnableAsync
public class ThreadPoolConfig {

    private static final Logger log = LoggerFactory.getLogger(ThreadPoolConfig.class);

    /** 共享 HttpClient 单例，供所有 Service 复用，避免资源泄漏。 */
    private static final HttpClient SHARED_HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8))
            .build();

    /** 获取共享 HttpClient（默认 8s 连接超时）。 */
    public static HttpClient sharedHttpClient() {
        return SHARED_HTTP_CLIENT;
    }

    /** 获取带自定义连接超时的 HttpClient。 */
    public static HttpClient httpClient(Duration connectTimeout) {
        return HttpClient.newBuilder()
                .connectTimeout(connectTimeout)
                .build();
    }

    @Bean("semiriskCrawlerPool")
    public ThreadPoolTaskExecutor crawlerExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("semirisk-crawler-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        log.info("Initialized crawler thread pool: core=4, max=8, queue=100, rejection=CallerRuns");
        return executor;
    }

    @Bean("semiriskAiPool")
    public ThreadPoolTaskExecutor aiExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("semirisk-ai-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(60);
        executor.initialize();
        log.info("Initialized AI thread pool: core=2, max=4, queue=50, rejection=CallerRuns");
        return executor;
    }

    @Bean("semiriskQueryPool")
    public ThreadPoolTaskExecutor queryExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(8);
        executor.setMaxPoolSize(16);
        executor.setQueueCapacity(200);
        executor.setThreadNamePrefix("semirisk-query-");
        // Query pool: discard oldest to keep responses fresh under pressure
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.DiscardOldestPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        log.info("Initialized query thread pool: core=8, max=16, queue=200, rejection=DiscardOldest");
        return executor;
    }

    @Bean("semiriskReportPool")
    public ThreadPoolTaskExecutor reportExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(20);
        executor.setThreadNamePrefix("semirisk-report-");
        // Report pool: discard silently to avoid queue buildup
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.DiscardPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(120);
        executor.initialize();
        log.info("Initialized report thread pool: core=2, max=4, queue=20, rejection=Discard");
        return executor;
    }

    /** 上传任务专用线程池：文件处理、MinIO 操作、AI 评估。 */
    @Bean("semiriskUploadPool")
    public ThreadPoolTaskExecutor uploadExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("semirisk-upload-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(60);
        executor.initialize();
        log.info("Initialized upload thread pool: core=2, max=4, queue=50, rejection=CallerRuns");
        return executor;
    }

    /** 告警处理专用线程池：告警分发、通知发送。 */
    @Bean("semiriskAlertPool")
    public ThreadPoolTaskExecutor alertExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("semirisk-alert-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        log.info("Initialized alert thread pool: core=2, max=4, queue=100, rejection=CallerRuns");
        return executor;
    }
}
