package com.semirisk.config;

import jakarta.servlet.Filter;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;

/**
 * Configuration that registers filters in the Spring filter chain.
 */
@Configuration
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
