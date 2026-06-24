package com.semirisk.config;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

@Configuration
@EnableCaching
public class RedisCacheConfig {

    @Bean
    @ConditionalOnProperty(name = "semirisk.redis.disabled", havingValue = "false")
    public CacheManager redisCacheManager(RedisConnectionFactory connectionFactory) {
        // Default cache configuration: 60-second TTL, JSON serialization
        RedisCacheConfiguration defaultConfig = RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(Duration.ofSeconds(60))
                .serializeKeysWith(RedisSerializationContext.SerializationPair.fromSerializer(new StringRedisSerializer()))
                .serializeValuesWith(RedisSerializationContext.SerializationPair.fromSerializer(new GenericJackson2JsonRedisSerializer()))
                .disableCachingNullValues();

        // Per-cache TTL overrides
        Map<String, RedisCacheConfiguration> cacheConfigurations = new HashMap<>();
        cacheConfigurations.put("dashboard:overview", defaultConfig.entryTtl(Duration.ofSeconds(60)));
        cacheConfigurations.put("enterprise", defaultConfig.entryTtl(Duration.ofSeconds(300)));
        cacheConfigurations.put("risk:snapshot", defaultConfig.entryTtl(Duration.ofSeconds(30)));
        cacheConfigurations.put("knowledge", defaultConfig.entryTtl(Duration.ofSeconds(120)));

        return RedisCacheManager.builder(connectionFactory)
                .cacheDefaults(defaultConfig)
                .withInitialCacheConfigurations(cacheConfigurations)
                .build();
    }

    @Bean
    @ConditionalOnProperty(name = "semirisk.redis.disabled", havingValue = "true", matchIfMissing = true)
    public CacheManager localCacheManager() {
        return new ConcurrentMapCacheManager(
                "dashboard:overview",
                "enterprise",
                "risk:snapshot",
                "knowledge"
        );
    }
}
