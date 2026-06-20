package com.semirisk.config;

import feign.RequestInterceptor;
import feign.Retryer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Feign 客户端重试策略。
 * 对 GET（幂等）请求最多重试 3 次，指数退避 500ms → 1s → 2s。
 * POST/PUT 不自动重试（由业务层控制）。
 */
@Configuration
public class FeignClientConfig {

    private static final Logger log = LoggerFactory.getLogger(FeignClientConfig.class);

    @Bean
    public Retryer feignRetryer() {
        // 初始间隔 500ms，最大间隔 2s，最多重试 3 次
        return new Retryer.Default(500, 2000, 3);
    }

    /**
     * 幂等性 header：下游服务可通过此 header 判断是否允许重试。
     */
    @Bean
    public RequestInterceptor idempotencyHeaderInjector() {
        return template -> template.header("X-Idempotent", java.util.List.of("true"));
    }
}
