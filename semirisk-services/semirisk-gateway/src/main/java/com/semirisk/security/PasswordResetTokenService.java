package com.semirisk.security;

import com.semirisk.repository.PreparedRiskRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
public class PasswordResetTokenService {

    private static final Logger log = LoggerFactory.getLogger(PasswordResetTokenService.class);
    private static final Duration TOKEN_TTL = Duration.ofMinutes(15);

    private final StringRedisTemplate redisTemplate;
    private final PreparedRiskRepository repository;
    private final boolean redisDisabled;

    public PasswordResetTokenService(StringRedisTemplate redisTemplate,
                                     PreparedRiskRepository repository,
                                     @Value("${semirisk.redis.disabled:false}") boolean redisDisabled) {
        this.redisTemplate = redisTemplate;
        this.repository = repository;
        this.redisDisabled = redisDisabled;
    }

    public String createToken(String email) {
        String token = UUID.randomUUID().toString().replace("-", "");
        Instant expiresAt = Instant.now().plus(TOKEN_TTL);
        try {
            repository.insertResetToken(token, email, expiresAt);
        } catch (Exception ex) {
            log.error("Failed to persist reset token to MySQL for email={}", email, ex);
        }
        if (!redisDisabled) {
            try {
                redisTemplate.opsForValue().set("semirisk:reset:" + token, email, TOKEN_TTL);
            } catch (Exception ex) {
                log.warn("Failed to cache reset token in Redis for email={}", email, ex);
            }
        }
        return token;
    }

    public Optional<String> validateAndConsume(String token) {
        String email = null;
        if (!redisDisabled) {
            try {
                email = redisTemplate.opsForValue().get("semirisk:reset:" + token);
            } catch (Exception ex) {
                log.warn("Failed to read reset token from Redis, falling back to DB, token={}", token, ex);
            }
        }
        if (email == null) {
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
        try {
            repository.markResetTokenConsumed(token);
        } catch (Exception ex) {
            log.warn("Failed to mark reset token as consumed, token={}", token, ex);
        }
        if (!redisDisabled) {
            try {
                redisTemplate.delete("semirisk:reset:" + token);
            } catch (Exception ex) {
                log.warn("Failed to delete reset token from Redis, token={}", token, ex);
            }
        }
        return Optional.of(email);
    }

    public Optional<String> findTokenByEmail(String email) {
        try {
            return repository.findActiveResetTokenByEmail(email)
                    .map(row -> (String) row.get("token"));
        } catch (Exception ex) {
            log.warn("Failed to find reset token by email={}", email, ex);
            return Optional.empty();
        }
    }
}
