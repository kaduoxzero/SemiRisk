package com.semirisk.config;

import jakarta.servlet.Filter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;

/**
 * 幂等性与限流过滤器注册。
 * 仅在 StringRedisTemplate 可用时激活（Redis 可达时启用完整功能，
 * Redis 不可用时自动跳过，不影响网关启动）。
 */
@Configuration
@ConditionalOnBean(StringRedisTemplate.class)
public class IdempotencyConfig {

    @Bean
    public IdempotencyFilter idempotencyFilter(StringRedisTemplate redisTemplate) {
        return new IdempotencyFilter(redisTemplate);
    }

    @Bean
    public FilterRegistrationBean<Filter> idempotencyFilterRegistration(IdempotencyFilter filter) {
        FilterRegistrationBean<Filter> registrationBean = new FilterRegistrationBean<>();
        registrationBean.setFilter(filter);
        registrationBean.setOrder(Ordered.HIGHEST_PRECEDENCE + 1);
        registrationBean.setUrlPatterns(List.of("/api/*"));
        return registrationBean;
    }

    @Bean
    public RateLimitingFilter rateLimitingFilter(StringRedisTemplate redisTemplate) {
        return new RateLimitingFilter(redisTemplate);
    }

    @Bean
    public FilterRegistrationBean<Filter> rateLimitingFilterRegistration(RateLimitingFilter filter) {
        FilterRegistrationBean<Filter> registrationBean = new FilterRegistrationBean<>();
        registrationBean.setFilter(filter);
        registrationBean.setOrder(Ordered.HIGHEST_PRECEDENCE);
        registrationBean.setUrlPatterns(List.of("/api/*"));
        return registrationBean;
    }
}
