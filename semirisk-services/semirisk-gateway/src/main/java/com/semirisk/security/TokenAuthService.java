package com.semirisk.security;

import com.semirisk.repository.PreparedRiskRepository;
import com.semirisk.model.UserAccount;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Bearer Token 认证服务。
 *
 * <p>Token 以 MySQL {@code auth_token} 表为事实源，支持 30 分钟滑动续期、重启恢复与多实例共享。</p>
 */
@Service
public class TokenAuthService {

    private static final Logger log = LoggerFactory.getLogger(TokenAuthService.class);
    private static final SecureRandom RANDOM = new SecureRandom();
    private final Map<String, AuthPrincipal> tokens = new ConcurrentHashMap<>();
    private final PreparedRiskRepository repository;
    private final long ttlMinutes;

    public TokenAuthService(PreparedRiskRepository repository,
                            @Value("${semirisk.auth.token-ttl-minutes:30}") long ttlMinutes) {
        this.repository = repository;
        this.ttlMinutes = ttlMinutes;
    }

    public IssuedToken issue(UserAccount account) {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        Instant now = Instant.now();
        Instant expiresAt = now.plus(ttlMinutes, ChronoUnit.MINUTES);
        AuthPrincipal principal = new AuthPrincipal(account.username(), account.displayName(), account.role(), expiresAt);
        tokens.put(token, principal);
        try {
            repository.insertAuthToken(token, account.username(), account.displayName(), account.role(), now, expiresAt);
        } catch (Exception ex) {
            log.error("Failed to persist auth token for user={} to MySQL", account.username(), ex);
        }
        return new IssuedToken(token, expiresAt);
    }

    public Optional<AuthPrincipal> validate(String authorization) {
        return validateToken(bearerToken(authorization));
    }

    public Optional<AuthPrincipal> validate(String authorization, String accessToken) {
        Optional<AuthPrincipal> principal = validate(authorization);
        if (principal.isPresent()) {
            return principal;
        }
        return validateToken(accessToken);
    }

    private Optional<AuthPrincipal> validateToken(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        Instant now = Instant.now();
        Instant renewed = now.plus(ttlMinutes, ChronoUnit.MINUTES);

        // DB 优先：以 auth_token 表为事实源。
        try {
            List<Map<String, Object>> rows = repository.findAuthToken(token);
            if (!rows.isEmpty()) {
                Map<String, Object> row = rows.get(0);
                Instant expiresAt = toInstant(row.get("expiresAt"));
                if (expiresAt == null || expiresAt.isBefore(now)) {
                    repository.deleteAuthToken(token);
                    tokens.remove(token);
                    return Optional.empty();
                }
                AuthPrincipal principal = new AuthPrincipal(
                        String.valueOf(row.get("username")),
                        String.valueOf(row.get("displayName")),
                        String.valueOf(row.get("role")),
                        renewed);
                repository.renewAuthToken(token, renewed);
                tokens.put(token, principal);
                return Optional.of(principal);
            }
        } catch (Exception ex) {
            log.warn("Failed to validate token from MySQL (DB may be unavailable), token={}", token, ex);
        }

        // 数据库中未找到 Token（或数据库不可用）：未认证。
        return Optional.empty();
    }

    public Set<String> getActiveUsernames() {
        Instant now = Instant.now();
        return tokens.values().stream()
                .filter(p -> p.expiresAt().isAfter(now))
                .map(AuthPrincipal::username)
                .collect(Collectors.toSet());
    }

    public void revoke(String authorization) {
        String token = bearerToken(authorization);
        if (!token.isBlank()) {
            tokens.remove(token);
            try {
                repository.deleteAuthToken(token);
            } catch (Exception ex) {
                log.error("Failed to revoke token from MySQL, token={}", token, ex);
            }
        }
    }

    /** 每 30 分钟清理过期 Token，避免表无限增长。 */
    @Scheduled(cron = "0 */30 * * * *")
    public void purgeExpired() {
        try {
            repository.deleteExpiredAuthTokens(Instant.now());
        } catch (Exception ex) {
            log.error("Failed to purge expired auth tokens from MySQL", ex);
        }
    }

    private Instant toInstant(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Instant instant) {
            return instant;
        }
        if (value instanceof java.sql.Timestamp timestamp) {
            return timestamp.toInstant();
        }
        if (value instanceof java.time.LocalDateTime localDateTime) {
            return localDateTime.atZone(java.time.ZoneId.systemDefault()).toInstant();
        }
        try {
            return Instant.parse(String.valueOf(value));
        } catch (Exception ex) {
            log.debug("Failed to parse Instant from '{}': {}", value, ex.getMessage());
            return null;
        }
    }

    private String bearerToken(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            return "";
        }
        return authorization.substring("Bearer ".length()).trim();
    }

    public record IssuedToken(String token, Instant expiresAt) {
    }

    public record AuthPrincipal(String username, String displayName, String role, Instant expiresAt) {
    }
}
