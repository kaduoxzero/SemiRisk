package com.semirisk.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Redis 验证码服务。
 *
 * <p>6 位数字验证码，5 分钟 TTL，每个邮箱最多尝试 2 次。</p>
 */
@Service
public class VerificationCodeService {

    private static final Logger log = LoggerFactory.getLogger(VerificationCodeService.class);
    private static final int CODE_LENGTH = 6;
    private static final Duration CODE_TTL = Duration.ofMinutes(5);
    private static final int MAX_ATTEMPTS = 2;

    private final StringRedisTemplate redisTemplate;
    /** 每邮箱的失败计数（内存兜底，Redis 不可用时仍可工作）。 */
    private final ConcurrentHashMap<String, Integer> attemptCounts = new ConcurrentHashMap<>();

    public VerificationCodeService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /** 生成并存储验证码，返回生成的代码。 */
    public String generate(String email) {
        String code = generateRandomCode();
        String key = key(email);
        try {
            redisTemplate.delete(key);
            redisTemplate.opsForValue().set(key, code, CODE_TTL);
        } catch (Exception ex) {
            log.warn("Failed to store verification code in Redis for email={}", email, ex);
        }
        attemptCounts.put(email, 0);
        return code;
    }

    /** 校验验证码。成功返回 true 并清除；失败递增计数，超限返回 false。 */
    public boolean verify(String email, String code) {
        String key = key(email);
        String stored;
        try {
            stored = redisTemplate.opsForValue().get(key);
        } catch (Exception ex) {
            log.warn("Failed to read verification code from Redis for email={}", email, ex);
            return false;
        }
        if (stored == null) {
            return false;
        }
        if (!stored.equals(code)) {
            int attempts = attemptCounts.merge(email, 1, Integer::sum);
            if (attempts >= MAX_ATTEMPTS) {
                redisTemplate.delete(key);
                attemptCounts.remove(email);
                return false;
            }
            return false;
        }
        redisTemplate.delete(key);
        attemptCounts.remove(email);
        return true;
    }

    /** 检查验证码是否还有剩余尝试次数。 */
    public boolean hasRemainingAttempts(String email) {
        return attemptCounts.getOrDefault(email, 0) < MAX_ATTEMPTS;
    }

    private String generateRandomCode() {
        SecureRandom random = new SecureRandom();
        int code = 100000 + random.nextInt(900000);
        return String.valueOf(code);
    }

    private String key(String email) {
        return "semirisk:verify:" + email.toLowerCase();
    }
}
