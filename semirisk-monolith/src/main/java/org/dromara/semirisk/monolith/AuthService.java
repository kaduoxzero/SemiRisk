package org.dromara.semirisk.monolith;

import jakarta.annotation.PostConstruct;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.springframework.http.HttpStatus.FORBIDDEN;
import static org.springframework.http.HttpStatus.UNAUTHORIZED;

@Service
public class AuthService {
    private static final int TOKEN_MINUTES = 30;
    private final JdbcTemplate jdbcTemplate;
    private final SecureRandom secureRandom = new SecureRandom();

    public AuthService(JdbcTemplate jdbcTemplate, RiskDatabase ignored) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @PostConstruct
    public void bootstrapUsers() {
        ensureUser("admin", "admin@risk.local", "password", "ADMIN");
        ensureUser("user", "user@risk.local", "password", "USER");
    }

    public Map<String, Object> login(String username, String password) {
        AuthUser user = findUser(username);
        if (user == null || !"ACTIVE".equals(user.status) || !matches(password, passwordHash(username))) {
            throw new ResponseStatusException(UNAUTHORIZED, "用户名或密码错误");
        }
        String token = UUID.randomUUID().toString().replace("-", "");
        Instant now = Instant.now();
        Instant expiresAt = now.plus(TOKEN_MINUTES, ChronoUnit.MINUTES);
        jdbcTemplate.update(
            "insert into semirisk_tokens(token, user_id, role, expires_at, created_at) values(?, ?, ?, ?, ?)",
            token,
            user.userId,
            user.role,
            expiresAt,
            now
        );
        return Map.of("token", token, "expiresAt", expiresAt, "user", user);
    }

    public AuthUser register(String username, String email, String password) {
        if (blank(username) || blank(password)) {
            throw new ResponseStatusException(UNAUTHORIZED, "用户名和密码不能为空");
        }
        if (findUser(username) != null) {
            throw new ResponseStatusException(UNAUTHORIZED, "用户已存在");
        }
        Instant now = Instant.now();
        jdbcTemplate.update(
            "insert into semirisk_users(username, email, password_hash, role, status, created_at, updated_at) values(?, ?, ?, 'USER', 'ACTIVE', ?, ?)",
            username.trim(),
            blank(email) ? null : email.trim(),
            hash(password),
            now,
            now
        );
        return findUser(username);
    }

    public void resetPassword(String account, String newPassword) {
        AuthUser user = findUser(account);
        if (user == null) {
            List<AuthUser> rows = jdbcTemplate.query(
                "select user_id, username, email, role, status from semirisk_users where email = ?",
                (rs, rowNum) -> mapUser(rs.getLong("user_id"), rs.getString("username"), rs.getString("email"), rs.getString("role"), rs.getString("status")),
                account
            );
            user = rows.isEmpty() ? null : rows.get(0);
        }
        if (user == null || blank(newPassword)) {
            throw new ResponseStatusException(UNAUTHORIZED, "账号不存在或新密码为空");
        }
        jdbcTemplate.update("update semirisk_users set password_hash = ?, updated_at = ? where user_id = ?", hash(newPassword), Instant.now(), user.userId);
        jdbcTemplate.update("delete from semirisk_tokens where user_id = ?", user.userId);
    }

    public AuthUser validate(String token) {
        if (blank(token)) {
            throw new ResponseStatusException(UNAUTHORIZED, "未登录");
        }
        jdbcTemplate.update("delete from semirisk_tokens where expires_at < ?", Instant.now());
        List<AuthUser> rows = jdbcTemplate.query(
            """
            select u.user_id, u.username, u.email, u.role, u.status
            from semirisk_tokens t
            join semirisk_users u on u.user_id = t.user_id
            where t.token = ? and t.expires_at >= ? and u.status = 'ACTIVE'
            """,
            (rs, rowNum) -> mapUser(rs.getLong("user_id"), rs.getString("username"), rs.getString("email"), rs.getString("role"), rs.getString("status")),
            token,
            Instant.now()
        );
        if (rows.isEmpty()) {
            throw new ResponseStatusException(UNAUTHORIZED, "登录已过期");
        }
        return rows.get(0);
    }

    public void requireAdmin(AuthUser user) {
        if (user == null || !"ADMIN".equals(user.role)) {
            throw new ResponseStatusException(FORBIDDEN, "需要管理员权限");
        }
    }

    private void ensureUser(String username, String email, String password, String role) {
        if (findUser(username) != null) {
            return;
        }
        Instant now = Instant.now();
        jdbcTemplate.update(
            "insert into semirisk_users(username, email, password_hash, role, status, created_at, updated_at) values(?, ?, ?, ?, 'ACTIVE', ?, ?)",
            username,
            email,
            hash(password),
            role,
            now,
            now
        );
    }

    private AuthUser findUser(String username) {
        if (blank(username)) {
            return null;
        }
        List<AuthUser> rows = jdbcTemplate.query(
            "select user_id, username, email, role, status from semirisk_users where username = ?",
            (rs, rowNum) -> mapUser(rs.getLong("user_id"), rs.getString("username"), rs.getString("email"), rs.getString("role"), rs.getString("status")),
            username.trim()
        );
        return rows.isEmpty() ? null : rows.get(0);
    }

    private String passwordHash(String username) {
        return jdbcTemplate.queryForObject("select password_hash from semirisk_users where username = ?", String.class, username.trim());
    }

    private AuthUser mapUser(Long id, String username, String email, String role, String status) {
        AuthUser user = new AuthUser();
        user.userId = id;
        user.username = username;
        user.email = email;
        user.role = role;
        user.status = status;
        return user;
    }

    private String hash(String password) {
        byte[] salt = new byte[16];
        secureRandom.nextBytes(salt);
        byte[] digest = pbkdf(password.toCharArray(), salt);
        return Base64.getEncoder().encodeToString(salt) + ":" + Base64.getEncoder().encodeToString(digest);
    }

    private boolean matches(String password, String stored) {
        if (blank(password) || blank(stored) || !stored.contains(":")) {
            return false;
        }
        String[] parts = stored.split(":", 2);
        byte[] salt = Base64.getDecoder().decode(parts[0]);
        byte[] expected = Base64.getDecoder().decode(parts[1]);
        byte[] actual = pbkdf(password.toCharArray(), salt);
        if (actual.length != expected.length) {
            return false;
        }
        int diff = 0;
        for (int i = 0; i < actual.length; i++) {
            diff |= actual[i] ^ expected[i];
        }
        return diff == 0;
    }

    private byte[] pbkdf(char[] password, byte[] salt) {
        try {
            PBEKeySpec spec = new PBEKeySpec(password, salt, 120_000, 256);
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (Exception ex) {
            throw new IllegalStateException("Password hashing failed", ex);
        }
    }

    private static boolean blank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
