package com.semirisk.config;

import feign.RequestInterceptor;
import feign.Retryer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Feign 客户端重试器。
 * 默认重试 3 次，初始间隔 100ms，最大间隔 1s。
 */
@Configuration
public class FeignClientRetryer {

    @Bean
    public Retryer feignRetryer() {
        return new Retryer.Default(100, 1000, 3);
    }

    @Bean
    public RequestInterceptor requestInterceptor() {
        return template -> {
            // 透传 Authorization header 到下游服务
            java.util.Collection<String> authHeaders = template.headers().get("Authorization");
            if (authHeaders != null && !authHeaders.isEmpty()) {
                template.header("Authorization", authHeaders.iterator().next());
            }
        };
    }
}
