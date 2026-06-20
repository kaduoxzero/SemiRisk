package com.semirisk.api;

import com.semirisk.common.SemiriskConstants;
import com.semirisk.model.LoginState;
import com.semirisk.model.UserAccount;
import com.semirisk.security.*;
import com.semirisk.repository.PreparedRiskRepository;
import com.semirisk.service.EmailService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 认证相关 API：登录、注册、密码重置、验证码、登出、当前用户信息。
 */
@Validated
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    private final PreparedRiskRepository preparedRiskRepository;
    private final RedisLoginGuardService redisLoginGuardService;
    private final PasswordHashService passwordHashService;
    private final InputSanitizer inputSanitizer;
    private final TokenAuthService tokenAuthService;
    private final CsrfTokenService csrfTokenService;
    private final VerificationCodeService verificationCodeService;
    private final PasswordResetTokenService passwordResetTokenService;
    private final EmailService emailService;

    public AuthController(PreparedRiskRepository preparedRiskRepository,
                          RedisLoginGuardService redisLoginGuardService,
                          PasswordHashService passwordHashService,
                          InputSanitizer inputSanitizer,
                          TokenAuthService tokenAuthService,
                          CsrfTokenService csrfTokenService,
                          VerificationCodeService verificationCodeService,
                          PasswordResetTokenService passwordResetTokenService,
                          EmailService emailService) {
        this.preparedRiskRepository = preparedRiskRepository;
        this.redisLoginGuardService = redisLoginGuardService;
        this.passwordHashService = passwordHashService;
        this.inputSanitizer = inputSanitizer;
        this.tokenAuthService = tokenAuthService;
        this.csrfTokenService = csrfTokenService;
        this.verificationCodeService = verificationCodeService;
        this.passwordResetTokenService = passwordResetTokenService;
        this.emailService = emailService;
    }

    @GetMapping("/csrf")
    public ApiResponse<Map<String, Object>> csrf() {
        return ApiResponse.ok(Map.of("token", csrfTokenService.issue()));
    }

    @PostMapping("/login")
    public ResponseEntity<ApiResponse<Map<String, Object>>> login(@Valid @RequestBody LoginRequest request) {
        String username = inputSanitizer.username(request.username());
        String password = inputSanitizer.loginPassword(request.password());
        LoginState state = redisLoginGuardService.loginState(username);
        if (state.locked()) {
            return ResponseEntity.status(423).body(ApiResponse.fail("账号已锁定至 " + state.lockedUntil()));
        }
        return authenticate(username, password)
                .map(account -> {
                    redisLoginGuardService.clear(username);
                    TokenAuthService.IssuedToken issuedToken = tokenAuthService.issue(account);
                    Map<String, Object> body = new HashMap<>();
                    body.put("token", issuedToken.token());
                    body.put("expiresAt", issuedToken.expiresAt().toString());
                    body.put("rememberMe", request.rememberMe());
                    body.put("user", Map.of(
                            "username", account.username(),
                            "displayName", account.displayName(),
                            "role", account.role(),
                            "modules", com.semirisk.common.RolePermissionPolicy.modules(account.role())
                    ));
                    return ResponseEntity.ok(ApiResponse.ok("登录成功", body));
                })
                .orElseGet(() -> {
                    LoginState failed = redisLoginGuardService.recordFailure(username);
                    String message = failed.locked()
                            ? "密码错误次数达到 5 次，账号锁定 30 分钟"
                            : "账号或密码错误，当前 5 分钟窗口失败次数：" + failed.failures();
                    return ResponseEntity.status(401).body(ApiResponse.fail(message));
                });
    }

    @PostMapping("/register")
    public ResponseEntity<ApiResponse<Map<String, Object>>> register(@Valid @RequestBody RegisterRequest request) {
        String username = inputSanitizer.username(request.username());
        String email = inputSanitizer.qqEmail(request.email());
        String displayName = inputSanitizer.displayName(request.displayName());
        String password = inputSanitizer.password(request.password());
        if (!verificationCodeService.verify(email, request.verificationCode())) {
            return ResponseEntity.badRequest().body(ApiResponse.fail("验证码错误或已过期"));
        }
        if (!preparedRiskRepository.findAuthUserByUsername(username).isEmpty()) {
            throw new IllegalArgumentException("账号已存在");
        }
        if (preparedRiskRepository.emailExists(email)) {
            throw new IllegalArgumentException("邮箱已被注册");
        }
        String role = preparedRiskRepository.countLoginUsers() == 0 ? SemiriskConstants.ROLE_ADMIN : SemiriskConstants.ROLE_OPERATOR;
        String id = "U-" + java.util.UUID.randomUUID().toString().substring(0, 8);
        preparedRiskRepository.insertSystemUser(id, username, displayName, email, passwordHashService.hash(password), role, "启用");
        preparedRiskRepository.insertAuditLog("INFO", "public registration persisted username=" + username + " role=" + role);
        UserAccount account = new UserAccount(username, "", displayName, role, true);
        TokenAuthService.IssuedToken issuedToken = tokenAuthService.issue(account);
        Map<String, Object> body = new HashMap<>();
        body.put("token", issuedToken.token());
        body.put("expiresAt", issuedToken.expiresAt().toString());
        body.put("user", Map.of(
                "username", account.username(),
                "displayName", account.displayName(),
                "role", account.role(),
                "modules", com.semirisk.common.RolePermissionPolicy.modules(account.role())
        ));
        return ResponseEntity.ok(ApiResponse.ok("注册成功", body));
    }

    @PostMapping("/send-verification-code")
    public ApiResponse<Void> sendVerificationCode(@Valid @RequestBody SendVerificationCodeRequest request) {
        String email = inputSanitizer.qqEmail(request.email());
        String code = verificationCodeService.generate(email);
        try {
            emailService.sendVerificationCode(email, code);
        } catch (Exception ex) {
            log.error("Failed to send verification email to {}: {}", email, ex.getMessage());
        }
        return ApiResponse.ok(null);
    }

    @PostMapping("/logout")
    public ApiResponse<Map<String, Object>> logout(jakarta.servlet.http.HttpServletRequest request) {
        tokenAuthService.revoke(request.getHeader("Authorization"));
        return ApiResponse.ok(Map.of("loggedOut", true));
    }

    @GetMapping("/me")
    public ResponseEntity<ApiResponse<Map<String, Object>>> me(jakarta.servlet.http.HttpServletRequest request) {
        return tokenAuthService.validate(request.getHeader("Authorization"))
                .map(principal -> {
                    Map<String, Object> data = new HashMap<>();
                    data.put("username", principal.username());
                    data.put("displayName", principal.displayName());
                    data.put("role", principal.role());
                    data.put("modules", com.semirisk.common.RolePermissionPolicy.modules(principal.role()));
                    data.put("expiresAt", principal.expiresAt().toString());
                    try {
                        List<Map<String, Object>> rows = preparedRiskRepository.findAuthUserByUsername(principal.username());
                        if (!rows.isEmpty()) {
                            Object lastLogin = rows.get(0).get("lastLoginAt");
                            if (lastLogin != null && !String.valueOf(lastLogin).isEmpty()) {
                                data.put("lastLoginAt", String.valueOf(lastLogin));
                            }
                        }
                    } catch (Exception ex) {
                        log.debug("Failed to fetch lastLoginAt for user {}: {}", principal.username(), ex.getMessage());
                    }
                    return ResponseEntity.ok(ApiResponse.ok(data));
                })
                .orElseGet(() -> ResponseEntity.status(401).body(ApiResponse.fail("未登录或 Token 已过期")));
    }

    @GetMapping("/permissions/{module}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> permission(@PathVariable String module,
                                                                       jakarta.servlet.http.HttpServletRequest request) {
        Optional<TokenAuthService.AuthPrincipal> principal = tokenAuthService.validate(request.getHeader("Authorization"));
        boolean allowed = principal.isPresent() && com.semirisk.common.RolePermissionPolicy.canAccess(principal.get().role(), module);
        return allowed
                ? ResponseEntity.ok(ApiResponse.ok(Map.of("module", module, "allowed", true)))
                : ResponseEntity.status(403).body(ApiResponse.fail("无权访问模块：" + module));
    }

    @PostMapping("/password-reset/request")
    public ApiResponse<Map<String, Object>> requestReset(@Valid @RequestBody PasswordResetRequest request) {
        String email = inputSanitizer.qqEmail(request.email());
        List<Map<String, Object>> rows = preparedRiskRepository.findAuthUserByEmail(email);
        if (rows.isEmpty()) {
            return ApiResponse.ok("重置验证码已发送至您的邮箱", null);
        }
        String token = passwordResetTokenService.createToken(email);
        try {
            emailService.sendPasswordResetCode(email, token);
        } catch (Exception ex) {
            log.error("Failed to send password reset email to {}: {}", email, ex.getMessage());
        }
        return ApiResponse.ok("重置验证码已发送至您的邮箱", Map.of("email", email, "expiresInMinutes", 15));
    }

    @PostMapping("/password-reset/confirm")
    public ApiResponse<Map<String, Object>> confirmReset(@Valid @RequestBody PasswordResetConfirmRequest request) {
        String email = inputSanitizer.qqEmail(request.email());
        String newPassword = inputSanitizer.password(request.newPassword());
        if (newPassword.length() < 8) {
            throw new IllegalArgumentException("密码至少 8 位");
        }
        if (!passwordResetTokenService.validateAndConsume(request.resetCode()).filter(e -> e.equalsIgnoreCase(email)).isPresent()) {
            throw new IllegalArgumentException("验证码错误或已过期");
        }
        List<Map<String, Object>> rows = preparedRiskRepository.findAuthUserByEmail(email);
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("该邮箱未注册");
        }
        String userId = rowString(rows.get(0), "id");
        preparedRiskRepository.updateSystemUserPassword(userId, passwordHashService.hash(newPassword));
        preparedRiskRepository.insertAuditLog("INFO", "password reset for email=" + email);
        return ApiResponse.ok("密码重置成功，请使用新密码登录", null);
    }

    // ---- internal helpers ----

    private Optional<UserAccount> authenticate(String username, String password) {
        List<Map<String, Object>> rows = preparedRiskRepository.findAuthUserByUsername(username);
        if (!rows.isEmpty()) {
            Map<String, Object> row = rows.get(0);
            String status = rowString(row, "status");
            String hash = rowString(row, "passwordHash");
            if (!"启用".equals(status) || !passwordHashService.verify(password, hash)) {
                return Optional.empty();
            }
            String id = rowString(row, "id");
            String role = normalizeRole(rowString(row, "role"));
            String displayName = rowString(row, "displayName").isBlank() ? username : rowString(row, "displayName");
            preparedRiskRepository.updateSystemUserLastLogin(id);
            preparedRiskRepository.insertAuditLog("INFO", "auth login success " + username);
            return Optional.of(new UserAccount(username, "", displayName, role, true));
        }
        return Optional.empty();
    }

    private String rowString(Map<String, Object> row, String key) {
        Object value = row.get(key);
        return value == null ? "" : String.valueOf(value);
    }

    private String normalizeRole(String role) {
        try {
            return inputSanitizer.role(role);
        } catch (IllegalArgumentException ex) {
            log.debug("Invalid role '{}', defaulting to OPERATOR", role);
            return SemiriskConstants.ROLE_OPERATOR;
        }
    }

    // ---- request records ----

    public record LoginRequest(@NotBlank String username, @NotBlank String password, boolean rememberMe, String captchaToken) {}
    public record RegisterRequest(@NotBlank String username, @NotBlank @Email String email, @NotBlank String password, @NotBlank String displayName, @NotBlank String verificationCode) {}
    public record SendVerificationCodeRequest(@NotBlank @Email String email) {}
    public record PasswordResetRequest(@NotBlank @Email String email) {}
    public record PasswordResetConfirmRequest(@NotBlank @Email String email, @NotBlank String resetCode, @NotBlank String newPassword) {}
}
