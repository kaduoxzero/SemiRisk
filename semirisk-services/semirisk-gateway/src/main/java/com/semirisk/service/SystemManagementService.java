package com.semirisk.service;

import com.semirisk.model.AiModelConfig;
import com.semirisk.model.SystemUser;
import com.semirisk.repository.PreparedRiskRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.util.concurrent.ConcurrentHashMap;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 系统管理相关功能：系统用户 CRUD、AI 模型配置管理、系统概览、审计日志、数据源探测。
 */
@Service
public class SystemManagementService {

    private final Map<String, SystemUser> systemUsers = new ConcurrentHashMap<>();
    private final Map<String, AiModelConfig> aiModelConfigs = new ConcurrentHashMap<>();
    private final Map<String, String> aiModelApiKeys = new ConcurrentHashMap<>();
    private final List<String> auditLogs = new ArrayList<>();
    private final PreparedRiskRepository repository;
    private final HealthProbeService healthProbeService;
    private final String defaultAiModel;
    private final String defaultAiEndpoint;
    private final String defaultAiApiKey;

    public SystemManagementService(
            PreparedRiskRepository repository,
            HealthProbeService healthProbeService,
            @Value("${semirisk.ai.default.model:}") String defaultAiModel,
            @Value("${semirisk.ai.default.endpoint:}") String defaultAiEndpoint,
            @Value("${semirisk.ai.default.api-key:}") String defaultAiApiKey) {
        this.repository = repository;
        this.healthProbeService = healthProbeService;
        this.defaultAiModel = defaultAiModel;
        this.defaultAiEndpoint = defaultAiEndpoint;
        this.defaultAiApiKey = defaultAiApiKey;
        seedDefaultAiModel();
        auditLogs.add("[INFO] gateway route table initialized");
    }

    // -----------------------------------------------------------------
    // System user management
    // -----------------------------------------------------------------

    public List<SystemUser> systemUsers() {
        return systemUsers.values().stream()
                .sorted(Comparator.comparing(SystemUser::username))
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

    // -----------------------------------------------------------------
    // Audit logs
    // -----------------------------------------------------------------

    public List<String> auditLogs() {
        try {
            List<Map<String, Object>> rows = repository.findAuditLogs(200);
            if (!rows.isEmpty()) {
                return rows.stream()
                        .map(row -> stringValue(row.get("createdAt")) + " [" + stringValue(row.get("level")) + "] " + stringValue(row.get("message")))
                        .toList();
            }
        } catch (Exception ignored) {
            // MySQL 不可达时回退内存审计日志。
        }
        String today = LocalDate.now().toString();
        return auditLogs.stream()
                .map(log -> log.matches("^\\d{4}-\\d{2}-\\d{2}.*") ? log : today + " " + log)
                .toList();
    }

    // -----------------------------------------------------------------
    // AI model config
    // -----------------------------------------------------------------

    public AiModelConfig saveAiModelConfig(String model, String endpoint, String apiKey) {
        AiModelConfig config = new AiModelConfig(model, endpoint, mask(apiKey), apiKey != null && !apiKey.isBlank(), Instant.now());
        aiModelConfigs.put(model, config);
        if (apiKey != null && !apiKey.isBlank()) {
            aiModelApiKeys.put(model, apiKey);
        }
        auditLogs.add("[INFO] AI model config saved for " + model + " endpoint=" + endpoint);
        return config;
    }

    public Map<String, AiModelConfig> aiModelConfigs() {
        return Map.copyOf(aiModelConfigs);
    }

    // -----------------------------------------------------------------
    // System overview
    // -----------------------------------------------------------------

    public Map<String, Object> systemOverview() {
        Map<String, Object> overview = new LinkedHashMap<>();
        overview.put("users", systemUsers());
        overview.put("roles", List.of("管理员", "分析师", "运营人员"));
        overview.put("models", List.of(
                modelOverview(defaultAiModel, defaultAiEndpoint),
                modelOverview("deepseek-chat", "https://api.deepseek.com/v1")
        ));
        overview.put("agents", List.of(
                Map.of("name", "公开源爬虫 Agent", "status", "运行中", "cron", "0 0 */12 * * *", "lastPull", "待采集",
                        "detail", "实时爬取公开 RSS / 政策法规源"),
                Map.of("name", "风险测算 Agent", "status", "待采集", "cron", "0 0 */12 * * *", "lastPull", "待采集",
                        "detail", "基于公开源信号与规则自动测算每日风险分"),
                Map.of("name", "AI 报告 Agent", "status", aiConfigured() ? "运行中" : "待配置 API Key", "cron", "0 0 */12 * * *", "lastPull", "待采集",
                        "detail", "聚合公开源 + 风险快照调用 DeepSeek 生成本日报告")
        ));
        overview.put("logs", auditLogs());
        overview.put("dataSources", probeDataSources());
        return overview;
    }

    // -----------------------------------------------------------------
    // Internal helpers
    // -----------------------------------------------------------------

    private List<Map<String, Object>> probeDataSources() {
        try {
            return healthProbeService.probeAll();
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private boolean aiConfigured() {
        String apiKey = aiModelApiKeys.getOrDefault(defaultAiModel, defaultAiApiKey == null ? "" : defaultAiApiKey);
        return apiKey != null && !apiKey.isBlank();
    }

    private Map<String, Object> modelOverview(String name, String endpoint) {
        AiModelConfig config = aiModelConfigs.get(name);
        Map<String, Object> map = new HashMap<>();
        map.put("name", name);
        map.put("endpoint", config == null ? endpoint : config.endpoint());
        map.put("status", config != null && config.configured() ? "已配置" : "待配置 API Key");
        map.put("hint", "延迟请用连通性测试实测");
        return map;
    }

    private void seedDefaultAiModel() {
        if (defaultAiApiKey != null && !defaultAiApiKey.isBlank()) {
            aiModelConfigs.put(defaultAiModel, new AiModelConfig(defaultAiModel, defaultAiEndpoint, mask(defaultAiApiKey), true, Instant.now()));
            aiModelApiKeys.put(defaultAiModel, defaultAiApiKey);
        }
    }

    private String mask(String key) {
        if (key == null || key.length() < 8) {
            return "未配置";
        }
        return key.substring(0, 4) + "****" + key.substring(key.length() - 4);
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    // -----------------------------------------------------------------
    // Package-private getters for the maps (used by SemiRiskStore)
    // -----------------------------------------------------------------

    Map<String, SystemUser> systemUsersMap() {
        return systemUsers;
    }

    Map<String, AiModelConfig> aiModelConfigsMap() {
        return aiModelConfigs;
    }

    Map<String, String> aiModelApiKeysMap() {
        return aiModelApiKeys;
    }

    List<String> auditLogsList() {
        return auditLogs;
    }
}
