package com.semirisk.security;

import com.semirisk.service.SemiRiskStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class TokenAuthService {

    private static final SecureRandom RANDOM = new SecureRandom();
    private final Map<String, AuthPrincipal> tokens = new ConcurrentHashMap<>();
    private final long ttlMinutes;

    public TokenAuthService(@Value("${semirisk.auth.token-ttl-minutes:30}") long ttlMinutes) {
        this.ttlMinutes = ttlMinutes;
    }

    public IssuedToken issue(SemiRiskStore.UserAccount account) {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        Instant expiresAt = Instant.now().plus(ttlMinutes, ChronoUnit.MINUTES);
        tokens.put(token, new AuthPrincipal(account.username(), account.displayName(), account.role(), expiresAt));
        return new IssuedToken(token, expiresAt);
    }

    public Optional<AuthPrincipal> validate(String authorization) {
        String token = bearerToken(authorization);
        if (token.isBlank()) {
            return Optional.empty();
        }
        AuthPrincipal principal = tokens.get(token);
        if (principal == null) {
            return Optional.empty();
        }
        if (principal.expiresAt().isBefore(Instant.now())) {
            tokens.remove(token);
            return Optional.empty();
        }
        AuthPrincipal renewed = new AuthPrincipal(principal.username(), principal.displayName(), principal.role(), Instant.now().plus(ttlMinutes, ChronoUnit.MINUTES));
        tokens.put(token, renewed);
        return Optional.of(renewed);
    }

    public void revoke(String authorization) {
        String token = bearerToken(authorization);
        if (!token.isBlank()) {
            tokens.remove(token);
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
