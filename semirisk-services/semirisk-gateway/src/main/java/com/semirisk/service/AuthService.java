package com.semirisk.service;

import com.semirisk.common.SemiriskConstants;
import com.semirisk.model.LoginCounter;
import com.semirisk.model.LoginState;
import com.semirisk.model.SystemUser;
import com.semirisk.model.UserAccount;
import com.semirisk.repository.PreparedRiskRepository;
import com.semirisk.security.PasswordHashService;
import com.semirisk.util.SafeLogger;
import com.semirisk.util.CircularBuffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private final Map<String, UserAccount> users = new ConcurrentHashMap<>();
    private final Map<String, LoginCounter> loginCounters = new ConcurrentHashMap<>();
    private final Map<String, Instant> resetTokens = new ConcurrentHashMap<>();
    private final Map<String, SystemUser> systemUsers = new ConcurrentHashMap<>();
    private final List<String> auditLogs = Collections.synchronizedList(new CircularBuffer<>(10000));

    private final PreparedRiskRepository repository;
    private final PasswordHashService passwordHashService;

    public AuthService(PreparedRiskRepository repository, PasswordHashService passwordHashService) {
        this.repository = repository;
        this.passwordHashService = passwordHashService;
    }

    // ---------------------------------------------------------------------
    // SemiRiskStore 的访问器
    // ---------------------------------------------------------------------

    Map<String, UserAccount> getUsers() {
        return users;
    }

    Map<String, LoginCounter> getLoginCounters() {
        return loginCounters;
    }

    Map<String, Instant> getResetTokens() {
        return resetTokens;
    }

    Map<String, SystemUser> getSystemUsers() {
        return systemUsers;
    }

    List<String> getAuditLogs() {
        return auditLogs;
    }

    // ---------------------------------------------------------------------
    // 身份验证
    // ---------------------------------------------------------------------

    public Optional<UserAccount> authenticate(String username, String password) {
        UserAccount account = users.get(username);
        if (account != null && account.enabled()) {
            if (passwordHashService.verify(password, account.password())) {
                loginCounters.remove(username);
                return Optional.of(account);
            }
        }
        return Optional.empty();
    }

    /** 从 MySQL 恢复已注册用户到内存，确保数据库可用时登录路径一致。 */
    public void recoverUsersToMemory() {
        try {
            List<Map<String, Object>> allUsers = findAllSystemUsers();
            for (Map<String, Object> row : allUsers) {
                String status = stringValue(row.get("status"));
                String username = stringValue(row.get("username"));
                if (!username.isEmpty()) {
                    String id = stringValue(row.get("id"));
                    if (id.isBlank()) {
                        id = "U-" + username;
                    }
                    String email = stringValue(row.get("email"));
                    String role = stringValue(row.get("role"));
                    systemUsers.put(id, new SystemUser(id, username, email, role, status));
                }
                if ("启用".equals(status) && !username.isEmpty()) {
                    String displayName = stringValue(row.get("displayName"));
                    if (displayName.isBlank()) displayName = username;
                    String role = stringValue(row.get("role"));
                    if (!users.containsKey(username)) {
                        // 恢复时密码字段为空，实际登录走 DB 路径（SemiRiskController.authenticate）
                        users.put(username, new UserAccount(username, "", displayName, role, true));
                    }
                }
            }
            auditLogs.add("[INFO] recovered " + allUsers.size() + " users from MySQL to memory");
        } catch (Exception ex) {
            log.warn("Failed to recover users from MySQL to memory: {}", ex.getMessage());
        }
    }

    public UserAccount register(String username, String password, String displayName, String email) {
        if (users.containsKey(username)) {
            throw new IllegalArgumentException("账号已存在");
        }
        boolean emailExists = systemUsers.values().stream().anyMatch(user -> user.email().equalsIgnoreCase(email));
        if (emailExists) {
            throw new IllegalArgumentException("邮箱已被注册");
        }
        String role = users.isEmpty() ? SemiriskConstants.ROLE_ADMIN : SemiriskConstants.ROLE_OPERATOR;
        String hashed = passwordHashService.hash(password);
        UserAccount account = new UserAccount(username, hashed, displayName, role);
        users.put(username, account);
        addSystemUser(username, email, role);
        auditLogs.add("[INFO] public registration completed username=" + username);
        return account;
    }

    public UserAccount upsertLoginUser(String username, String password, String displayName, String email, String role) {
        String hashed = passwordHashService.hash(password);
        UserAccount account = new UserAccount(username, hashed, displayName, role);
        users.put(username, account);
        systemUsers.values().stream()
                .filter(user -> user.username().equals(username))
                .findFirst()
                .ifPresentOrElse(
                        user -> systemUsers.put(user.id(), new SystemUser(user.id(), username, email, role, "启用")),
                        () -> addSystemUser(username, email, role, "启用")
                );
        auditLogs.add("[INFO] login user " + username + " upserted with role " + role);
        return account;
    }

    // ---------------------------------------------------------------------
    // 登录失败追踪
    // ---------------------------------------------------------------------

    public LoginState loginState(String username) {
        LoginCounter counter = loginCounters.get(username);
        if (counter == null) {
            return new LoginState(false, 0, null);
        }
        Instant now = Instant.now();
        if (counter.lockedUntil() != null && counter.lockedUntil().isAfter(now)) {
            return new LoginState(true, counter.failures(), counter.lockedUntil());
        }
        if (counter.windowStarted().plus(5, ChronoUnit.MINUTES).isBefore(now)) {
            loginCounters.remove(username);
            return new LoginState(false, 0, null);
        }
        return new LoginState(false, counter.failures(), null);
    }

    public LoginState recordFailure(String username) {
        Instant now = Instant.now();
        LoginCounter counter = loginCounters.compute(username, (key, old) -> {
            if (old == null || old.windowStarted().plus(5, ChronoUnit.MINUTES).isBefore(now)) {
                return new LoginCounter(1, now, null);
            }
            int failures = old.failures() + 1;
            Instant lockedUntil = failures >= 5 ? now.plus(30, ChronoUnit.MINUTES) : old.lockedUntil();
            return new LoginCounter(failures, old.windowStarted(), lockedUntil);
        });
        return new LoginState(counter.lockedUntil() != null && counter.lockedUntil().isAfter(now), counter.failures(), counter.lockedUntil());
    }

    // ---------------------------------------------------------------------
    // 密码重置令牌
    // ---------------------------------------------------------------------

    public String createResetToken(String email) {
        String token = UUID.randomUUID().toString().replace("-", "");
        resetTokens.put(token, Instant.now().plus(15, ChronoUnit.MINUTES));
        auditLogs.add("[INFO] password reset token issued for " + email);
        return token;
    }

    // ---------------------------------------------------------------------
    // 系统用户管理（与 SemiRiskStore 共享状态）
    // ---------------------------------------------------------------------

    public List<SystemUser> systemUsers() {
        return systemUsers.values().stream()
                .sorted(Comparator.comparingInt((SystemUser user) -> rolePriority(user.role()))
                        .thenComparing(SystemUser::username))
                .toList();
    }

    public SystemUser addSystemUser(String username, String email, String role) {
        return addSystemUser(username, email, role, "启用");
    }

    public SystemUser addSystemUser(String username, String email, String role, String status) {
        String id = "U" + (1000 + systemUsers.size() + 1);
        SystemUser user = new SystemUser(id, username, email, role, status);
        systemUsers.put(id, user);
        auditLogs.add("[INFO] user " + username + " created with role " + role);
        return user;
    }

    public SystemUser updateSystemUserStatus(String id, String status) {
        SystemUser current = systemUsers.get(id);
        if (current == null) {
            throw new IllegalArgumentException("用户不存在");
        }
        SystemUser updated = new SystemUser(current.id(), current.username(), current.email(), current.role(), status);
        systemUsers.put(id, updated);
        auditLogs.add("[WARN] user " + current.username() + " status changed to " + status + "; online sessions kicked");
        return updated;
    }

    public void deleteSystemUser(String id) {
        SystemUser removed = systemUsers.remove(id);
        if (removed == null) {
            throw new IllegalArgumentException("用户不存在");
        }
        auditLogs.add("[ERROR] user " + removed.username() + " physically removed");
    }

    // ---------------------------------------------------------------------
    // 私有辅助方法
    // ---------------------------------------------------------------------

    private List<Map<String, Object>> findAllSystemUsers() {
        try {
            return repository.findSystemUsers();
        } catch (Exception ex) {
            log.warn("Failed to fetch system users from MySQL: {}", ex.getMessage());
            return List.of();
        }
    }

    private int rolePriority(String role) {
        return switch (role) {
            case SemiriskConstants.ROLE_ADMIN -> 1;
            case SemiriskConstants.ROLE_ANALYST -> 2;
            case SemiriskConstants.ROLE_OPERATOR -> 3;
            default -> 99;
        };
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
