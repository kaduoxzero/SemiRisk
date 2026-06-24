package com.semirisk.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class VerificationCodeService {

    private static final Logger log = LoggerFactory.getLogger(VerificationCodeService.class);
    private static final int CODE_LENGTH = 6;
    private static final Duration CODE_TTL = Duration.ofMinutes(5);
    private static final int MAX_ATTEMPTS = 2;

    private final StringRedisTemplate redisTemplate;
    private final boolean redisDisabled;
    private final ConcurrentHashMap<String, CodeEntry> localCodes = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Integer> attemptCounts = new ConcurrentHashMap<>();

    public VerificationCodeService(StringRedisTemplate redisTemplate,
                                   @Value("${semirisk.redis.disabled:false}") boolean redisDisabled) {
        this.redisTemplate = redisTemplate;
        this.redisDisabled = redisDisabled;
    }

    public String generate(String email) {
        String code = generateRandomCode();
        if (redisDisabled) {
            storeLocal(email, code);
            return code;
        }
        String key = key(email);
        try {
            redisTemplate.delete(key);
            redisTemplate.opsForValue().set(key, code, CODE_TTL);
            attemptCounts.put(email, 0);
        } catch (Exception ex) {
            log.warn("Failed to store verification code in Redis for email={}", email, ex);
            storeLocal(email, code);
        }
        return code;
    }

    public boolean verify(String email, String code) {
        if (redisDisabled) {
            return verifyLocal(email, code);
        }
        String key = key(email);
        String stored;
        try {
            stored = redisTemplate.opsForValue().get(key);
        } catch (Exception ex) {
            log.warn("Failed to read verification code from Redis for email={}", email, ex);
            return verifyLocal(email, code);
        }
        if (stored == null) {
            return false;
        }
        if (!stored.equals(code)) {
            int attempts = attemptCounts.merge(email, 1, Integer::sum);
            if (attempts >= MAX_ATTEMPTS) {
                redisTemplate.delete(key);
                attemptCounts.remove(email);
            }
            return false;
        }
        redisTemplate.delete(key);
        attemptCounts.remove(email);
        return true;
    }

    public boolean hasRemainingAttempts(String email) {
        return attemptCounts.getOrDefault(email, 0) < MAX_ATTEMPTS;
    }

    private String generateRandomCode() {
        SecureRandom random = new SecureRandom();
        int code = 100000 + random.nextInt(900000);
        return String.valueOf(code).substring(0, CODE_LENGTH);
    }

    private String key(String email) {
        return "semirisk:verify:" + email.toLowerCase();
    }

    private void storeLocal(String email, String code) {
        localCodes.put(email.toLowerCase(), new CodeEntry(code, Instant.now().plus(CODE_TTL)));
        attemptCounts.put(email, 0);
    }

    private boolean verifyLocal(String email, String code) {
        String normalizedEmail = email.toLowerCase();
        CodeEntry entry = localCodes.get(normalizedEmail);
        if (entry == null || entry.expiresAt().isBefore(Instant.now())) {
            localCodes.remove(normalizedEmail);
            attemptCounts.remove(email);
            return false;
        }
        if (!entry.code().equals(code)) {
            int attempts = attemptCounts.merge(email, 1, Integer::sum);
            if (attempts >= MAX_ATTEMPTS) {
                localCodes.remove(normalizedEmail);
                attemptCounts.remove(email);
            }
            return false;
        }
        localCodes.remove(normalizedEmail);
        attemptCounts.remove(email);
        return true;
    }

    private record CodeEntry(String code, Instant expiresAt) {}
}
