package com.semirisk.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import feign.RequestInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Feign 客户端配置。
 * 注意：Retryer bean 定义在 {@link FeignClientRetryer} 中，此处仅保留幂等性 header 注入。
 */
@Configuration
public class FeignClientConfig {

    private static final Logger log = LoggerFactory.getLogger(FeignClientConfig.class);

    /**
     * 幂等性 header：下游服务可通过此 header 判断是否允许重试。
     */
    @Bean
    public RequestInterceptor idempotencyHeaderInjector() {
        return template -> template.header("X-Idempotent", java.util.List.of("true"));
    }
}
