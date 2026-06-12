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
import java.util.concurrent.ConcurrentHashMap;

/**
 * Bearer Token 认证服务。
 *
 * <p>Token 以 MySQL {@code auth_token} 表为事实源，支持 30 分钟滑动续期、重启恢复与多实例共享；
 * 内存 {@link java.util.concurrent.ConcurrentHashMap} 仅在 MySQL 暂不可达时作降级兜底，
 * 不再依赖纯内存 Token（避免重启即失效）。</p>
 */
@Service
public class TokenAuthService {

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
        } catch (Exception ignored) {
            // MySQL 暂不可达时使用内存兜底，保证本地可登录。
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

        // 1) DB 优先：以 auth_token 表为事实源。
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
            // DB 可达但无该 Token：可能内存兜底签发，向下回退判断。
        } catch (Exception ignored) {
            // DB 不可达，使用内存兜底。
        }

        // 2) 内存兜底（仅 DB 不可达或兜底签发时命中）。
        AuthPrincipal principal = tokens.get(token);
        if (principal == null) {
            return Optional.empty();
        }
        if (principal.expiresAt().isBefore(now)) {
            tokens.remove(token);
            return Optional.empty();
        }
        AuthPrincipal next = new AuthPrincipal(principal.username(), principal.displayName(), principal.role(), renewed);
        tokens.put(token, next);
        return Optional.of(next);
    }

    public void revoke(String authorization) {
        String token = bearerToken(authorization);
        if (!token.isBlank()) {
            tokens.remove(token);
            try {
                repository.deleteAuthToken(token);
            } catch (Exception ignored) {
                // 忽略 DB 不可达，内存已清除。
            }
        }
    }

    /** 每 30 分钟清理过期 Token，避免表无限增长。 */
    @Scheduled(cron = "0 */30 * * * *")
    public void purgeExpired() {
        try {
            repository.deleteExpiredAuthTokens(Instant.now());
        } catch (Exception ignored) {
            // DB 不可达时跳过本轮清理。
        }
        tokens.values().removeIf(principal -> principal.expiresAt().isBefore(Instant.now()));
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
        } catch (Exception ignored) {
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
