package com.semirisk.api;

import com.semirisk.model.SystemUser;
import com.semirisk.service.SemiRiskStore;
import com.semirisk.service.HealthProbeService;
import com.semirisk.security.InputSanitizer;
import com.semirisk.security.PasswordHashService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.*;

/**
 * 系统管理 API：用户管理、模型配置、数据源探测、Agent 触发、系统概览。
 */
@RestController
@RequestMapping("/api/system")
public class SystemController {

    private static final Logger log = LoggerFactory.getLogger(SystemController.class);

    private final SemiRiskStore store;
    private final InputSanitizer inputSanitizer;
    private final PasswordHashService passwordHashService;

    public SystemController(SemiRiskStore store, InputSanitizer inputSanitizer, PasswordHashService passwordHashService) {
        this.store = store;
        this.inputSanitizer = inputSanitizer;
        this.passwordHashService = passwordHashService;
    }

    @GetMapping("/overview")
    public ApiResponse<Map<String, Object>> systemOverview() {
        Map<String, Object> overview = new HashMap<>(store.systemOverview());
        return ApiResponse.ok(overview);
    }

    @PostMapping("/users")
    public ApiResponse<SystemUser> addUser(@Valid @RequestBody AddUserRequest request) {
        String username = inputSanitizer.username(request.username());
        String email = inputSanitizer.qqEmail(request.email());
        String role = inputSanitizer.role(request.role());
        SystemUser user = store.addSystemUser(username, email, role, "禁用");
        try {
            store.getPreparedRiskRepository().insertSystemUser(user.id(), user.username(), user.username(), user.email(), null, user.role(), user.status());
            store.getPreparedRiskRepository().insertAuditLog("INFO", "system user created " + user.username());
        } catch (Exception ex) {
            log.error("Failed to persist system user to MySQL, user={}: {}", user.username(), ex.getMessage());
        }
        return ApiResponse.ok("系统用户已创建", user);
    }

    @PostMapping("/users/login")
    public ApiResponse<Map<String, Object>> upsertLoginUser(@Valid @RequestBody LoginUserRequest request) {
        String username = inputSanitizer.username(request.username());
        String email = inputSanitizer.qqEmail(request.email());
        String displayName = inputSanitizer.displayName(request.displayName());
        String password = inputSanitizer.password(request.password());
        String role = inputSanitizer.role(request.role());
        var account = store.upsertLoginUser(username, password, displayName, email, role);
        try {
            String id = "U-" + UUID.randomUUID().toString().substring(0, 8);
            store.getPreparedRiskRepository().upsertSystemLoginUser(id, username, displayName, email,
                    passwordHashService.hash(password), role, "启用");
            store.getPreparedRiskRepository().insertAuditLog("INFO", "login user upserted " + username + " role=" + role);
        } catch (Exception ex) {
            log.error("Failed to persist login user to MySQL, username={}: {}", username, ex.getMessage());
        }
        return ApiResponse.ok("登录用户已创建/更新", Map.of(
                "username", account.username(),
                "displayName", account.displayName(),
                "role", account.role(),
                "modules", com.semirisk.common.RolePermissionPolicy.modules(account.role())
        ));
    }

    @PutMapping("/users/{id}/status")
    public ApiResponse<SystemUser> updateUserStatus(@PathVariable String id, @Valid @RequestBody StatusRequest request) {
        String cleanStatus = inputSanitizer.status(request.status());
        SystemUser user = store.updateSystemUserStatus(id, cleanStatus);
        try {
            store.getPreparedRiskRepository().updateSystemUserStatus(id, cleanStatus);
            store.getPreparedRiskRepository().insertAuditLog("WARN", "system user status changed " + id + " -> " + cleanStatus);
        } catch (Exception ex) {
            log.error("Failed to update system user status in MySQL, id={}: {}", id, ex.getMessage());
        }
        return ApiResponse.ok("用户状态已更新，后续请求需重新获取 Token", user);
    }

    @DeleteMapping("/users/{id}")
    public ApiResponse<Map<String, Object>> deleteUser(@PathVariable String id) {
        store.deleteSystemUser(id);
        try {
            store.getPreparedRiskRepository().deleteSystemUser(id);
            store.getPreparedRiskRepository().insertAuditLog("ERROR", "system user deleted " + id);
        } catch (Exception ex) {
            log.error("Failed to delete system user from MySQL, id={}: {}", id, ex.getMessage());
        }
        return ApiResponse.ok("用户已物理删除", Map.of("id", id));
    }

    @PostMapping("/models/ping")
    public ApiResponse<Map<String, Object>> modelPing(@RequestBody Map<String, String> request) {
        String model = inputSanitizer.plain(request.getOrDefault("model", "default"), 128);
        String endpoint = request.get("endpoint");
        Map<String, Object> result = store.getHealthProbeService().probeModelEndpoint(model, endpoint);
        boolean reachable = Boolean.TRUE.equals(result.get("reachable"));
        return ApiResponse.ok(reachable ? "模型 endpoint 连通性测试成功" : "模型 endpoint 暂不可达", result);
    }

    @PostMapping("/models/config")
    public ApiResponse<?> saveModelConfig(@Valid @RequestBody AiModelConfigRequest request) {
        String model = inputSanitizer.plain(request.model(), 128);
        String endpoint = sanitizeEndpoint(request.endpoint());
        String apiKey = inputSanitizer.plain(request.apiKey(), 256);
        var config = store.saveAiModelConfig(model, endpoint, apiKey);
        try {
            store.getPreparedRiskRepository().upsertAiModelConfig(config.model(), config.endpoint(), config.maskedApiKey(), config.configured(), config.updatedAt());
            store.getPreparedRiskRepository().insertAuditLog("INFO", "AI model config saved " + config.model());
        } catch (Exception ex) {
            log.error("Failed to persist AI model config to MySQL, model={}: {}", config.model(), ex.getMessage());
        }
        return ApiResponse.ok("AI 模型 API Key 已保存", config);
    }

    @GetMapping("/models/config")
    public ApiResponse<?> modelConfigs() {
        return ApiResponse.ok(store.aiModelConfigs());
    }

    @PostMapping("/agents/{name}/trigger")
    public ApiResponse<Map<String, Object>> triggerAgent(@PathVariable String name) {
        String lower = name == null ? "" : name.toLowerCase();
        String result;
        if (lower.contains("报告") || lower.contains("report")) {
            Map<String, Object> report = store.generateDailyAiReport();
            result = "已触发 AI 报告生成，aiCalled=" + report.getOrDefault("aiCalled", false);
        } else {
            result = "Agent 已触发";
        }
        try {
            store.getPreparedRiskRepository().insertAuditLog("INFO", "agent triggered " + name + " -> " + result);
        } catch (Exception ex) {
            log.error("Failed to insert audit log for agent trigger, agent={}: {}", name, ex.getMessage());
        }
        return ApiResponse.ok("Agent 已手动触发", Map.of("agent", name, "result", result, "triggeredAt", Instant.now().toString()));
    }

    @PostMapping("/datasources/{name}/reconnect")
    public ApiResponse<Map<String, Object>> reconnect(@PathVariable String name) {
        Map<String, Object> probe = store.getHealthProbeService().probeOne(name);
        boolean reachable = Boolean.TRUE.equals(probe.get("reachable"));
        try {
            store.getPreparedRiskRepository().insertAuditLog(reachable ? "INFO" : "WARN", "datasource reconnect " + name + " reachable=" + reachable);
        } catch (Exception ex) {
            log.error("Failed to insert audit log for datasource reconnect, name={}: {}", name, ex.getMessage());
        }
        return ApiResponse.ok(reachable ? "数据源连通正常" : "数据源不可达，请检查中间件状态", probe);
    }

    // ---- helpers ----

    private String sanitizeEndpoint(String endpoint) {
        String clean = inputSanitizer.plain(endpoint, 512);
        if (!clean.startsWith("https://") && !clean.startsWith("http://")) {
            throw new IllegalArgumentException("Endpoint 必须以 http:// 或 https:// 开头");
        }
        return clean;
    }

    // ---- request records ----

    public record AddUserRequest(@NotBlank String username, @NotBlank @Email String email, @NotBlank String role) {}
    public record LoginUserRequest(@NotBlank String username, @NotBlank @Email String email, @NotBlank String displayName, @NotBlank String password, @NotBlank String role) {}
    public record StatusRequest(@NotBlank String status) {}
    public record AiModelConfigRequest(@NotBlank String model, @NotBlank String endpoint, @NotBlank String apiKey) {}
}
