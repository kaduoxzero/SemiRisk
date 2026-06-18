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
 *
 * <p>AI 模型配置委托给 SemiRiskStore 作为单一事实源。</p>
 */
@Service
public class SystemManagementService {

    private final Map<String, SystemUser> systemUsers = new ConcurrentHashMap<>();
    private final List<String> auditLogs = new ArrayList<>();
    private final PreparedRiskRepository repository;
    private final HealthProbeService healthProbeService;
    private final SemiRiskStore store;
    private final String defaultAiModel;
    private final String defaultAiEndpoint;

    public SystemManagementService(
            PreparedRiskRepository repository,
            HealthProbeService healthProbeService,
            SemiRiskStore store,
            @Value("${semirisk.ai.default.model:}") String defaultAiModel,
            @Value("${semirisk.ai.default.endpoint:}") String defaultAiEndpoint) {
        this.repository = repository;
        this.healthProbeService = healthProbeService;
        this.store = store;
        this.defaultAiModel = defaultAiModel;
        this.defaultAiEndpoint = defaultAiEndpoint;
        auditLogs.add("[INFO] gateway route table initialized");
    }

    // -----------------------------------------------------------------
    // 系统用户管理
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
    // 审计日志
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
    // AI 模型配置（委托给 SemiRiskStore）
    // -----------------------------------------------------------------

    public AiModelConfig saveAiModelConfig(String model, String endpoint, String apiKey) {
        return store.saveAiModelConfig(model, endpoint, apiKey);
    }

    public Map<String, AiModelConfig> aiModelConfigs() {
        return store.aiModelConfigs();
    }

    // -----------------------------------------------------------------
    // 系统概览
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
                Map.of("name", "AI 报告 Agent", "status", store.isAiConfigured() ? "运行中" : "待配置 API Key", "cron", "0 0 */12 * * *", "lastPull", "待采集",
                        "detail", "聚合公开源 + 风险快照调用 DeepSeek 生成本日报告")
        ));
        overview.put("logs", auditLogs());
        overview.put("dataSources", probeDataSources());
        return overview;
    }

    // -----------------------------------------------------------------
    // 内部辅助方法
    // -----------------------------------------------------------------

    private List<Map<String, Object>> probeDataSources() {
        try {
            return healthProbeService.probeAll();
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private Map<String, Object> modelOverview(String name, String endpoint) {
        Map<String, AiModelConfig> configs = store.aiModelConfigs();
        AiModelConfig config = configs.get(name);
        String apiKey = store.getAiApiKey(name);
        Map<String, Object> map = new HashMap<>();
        map.put("name", name);
        map.put("endpoint", config == null ? endpoint : config.endpoint());
        boolean configured = (config != null && config.configured()) || (apiKey != null && !apiKey.isBlank());
        map.put("configured", configured);
        map.put("status", configured ? "已配置" : "待配置 API Key");
        map.put("hint", "延迟请用连通性测试实测");
        return map;
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
}
