package com.semirisk.security;

import com.semirisk.model.LoginState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;

@Service
public class RedisLoginGuardService {

    private static final Logger log = LoggerFactory.getLogger(RedisLoginGuardService.class);
    private static final Duration FAILURE_WINDOW = Duration.ofMinutes(5);
    private static final Duration LOCK_WINDOW = Duration.ofMinutes(30);
    private static final int MAX_FAILURES = 5;

    private final StringRedisTemplate redisTemplate;

    public RedisLoginGuardService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public LoginState loginState(String username) {
        try {
            String lockValue = redisTemplate.opsForValue().get(lockKey(username));
            if (lockValue != null) {
                return new LoginState(true, MAX_FAILURES, Instant.parse(lockValue));
            }
            String failureValue = redisTemplate.opsForValue().get(failureKey(username));
            int failures = failureValue == null ? 0 : Integer.parseInt(failureValue);
            return new LoginState(false, failures, null);
        } catch (Exception ex) {
            log.warn("Redis unavailable for login state check, returning default state for user={}", username, ex);
            return new LoginState(false, 0, null);
        }
    }

    public LoginState recordFailure(String username) {
        try {
            String lockValue = redisTemplate.opsForValue().get(lockKey(username));
            if (lockValue != null) {
                return new LoginState(true, MAX_FAILURES, Instant.parse(lockValue));
            }
            Long failures = redisTemplate.opsForValue().increment(failureKey(username));
            if (failures != null && failures == 1L) {
                redisTemplate.expire(failureKey(username), FAILURE_WINDOW);
            }
            int count = failures == null ? 1 : failures.intValue();
            if (count >= MAX_FAILURES) {
                Instant lockedUntil = Instant.now().plus(LOCK_WINDOW);
                redisTemplate.opsForValue().set(lockKey(username), lockedUntil.toString(), LOCK_WINDOW);
                return new LoginState(true, count, lockedUntil);
            }
            return new LoginState(false, count, null);
        } catch (Exception ex) {
            log.warn("Redis unavailable for recording login failure for user={}", username, ex);
            return new LoginState(false, 0, null);
        }
    }

    public void clear(String username) {
        try {
            redisTemplate.delete(failureKey(username));
            redisTemplate.delete(lockKey(username));
        } catch (Exception ex) {
            log.warn("Redis unavailable for clearing login state for user={}", username, ex);
        }
    }

    private String failureKey(String username) {
        return "semirisk:auth:fail:" + username;
    }

    private String lockKey(String username) {
        return "semirisk:auth:lock:" + username;
    }
}
