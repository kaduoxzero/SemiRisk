package com.semirisk.security;

import com.semirisk.repository.PreparedRiskRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 持久化密码重置令牌服务。
 *
 * <p>令牌同时存储在 MySQL（事实源）和 Redis（快速读取 + TTL 自动过期）中。</p>
 */
@Service
public class PasswordResetTokenService {

    private static final Logger log = LoggerFactory.getLogger(PasswordResetTokenService.class);
    private static final Duration TOKEN_TTL = Duration.ofMinutes(15);

    private final StringRedisTemplate redisTemplate;
    private final PreparedRiskRepository repository;

    public PasswordResetTokenService(StringRedisTemplate redisTemplate, PreparedRiskRepository repository) {
        this.redisTemplate = redisTemplate;
        this.repository = repository;
    }

    /** 为新密码重置生成令牌，返回令牌字符串。 */
    public String createToken(String email) {
        String token = UUID.randomUUID().toString().replace("-", "");
        Instant now = Instant.now();
        Instant expiresAt = now.plus(TOKEN_TTL);
        try {
            repository.insertResetToken(token, email, expiresAt);
        } catch (Exception ex) {
            log.error("Failed to persist reset token to MySQL for email={}", email, ex);
        }
        try {
            redisTemplate.opsForValue().set("semirisk:reset:" + token, email, TOKEN_TTL);
        } catch (Exception ex) {
            log.warn("Failed to cache reset token in Redis for email={}", email, ex);
        }
        return token;
    }

    /** 验证并消费令牌。返回对应的邮箱，无效则返回 empty。 */
    public Optional<String> validateAndConsume(String token) {
        String email = redisTemplate.opsForValue().get("semirisk:reset:" + token);
        if (email == null) {
            // Redis 不可达时回退到 DB 查询
            try {
                Optional<Map<String, Object>> row = repository.findActiveResetToken(token);
                if (row.isPresent()) {
                    email = (String) row.get().get("email");
                }
            } catch (Exception ex) {
                log.debug("Failed to validate reset token from DB: {}", ex.getMessage());
            }
        }
        if (email == null) {
            return Optional.empty();
        }
        // 标记为已消耗
        try {
            repository.markResetTokenConsumed(token);
        } catch (Exception ex) {
            log.warn("Failed to mark reset token as consumed, token={}", token, ex);
        }
        redisTemplate.delete("semirisk:reset:" + token);
        return Optional.of(email);
    }

    /** 根据邮箱查找最近的有效重置令牌。 */
    public Optional<String> findTokenByEmail(String email) {
        try {
            return repository.findActiveResetTokenByEmail(email)
                    .map(row -> (String) row.get("token"));
        } catch (Exception ex) {
            log.warn("Failed to find reset token by email={}", email, ex);
            return Optional.empty();
        }
    }

    private record MapEntry(String email) {}
}
