package com.semirisk.service;

import com.semirisk.common.SemiriskConstants;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.scheduling.annotation.Scheduled;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

@Service
public class SemiRiskStore {

    private final Map<String, UserAccount> users = new ConcurrentHashMap<>();
    private final Map<String, LoginCounter> loginCounters = new ConcurrentHashMap<>();
    private final Map<String, Instant> resetTokens = new ConcurrentHashMap<>();
    private final Map<String, UploadTask> uploadTasks = new ConcurrentHashMap<>();
    private final Map<String, ReportJob> reportJobs = new ConcurrentHashMap<>();
    private final Map<String, RiskAlert> alerts = new ConcurrentHashMap<>();
    private final Map<String, SystemUser> systemUsers = new ConcurrentHashMap<>();
    private final Map<String, AiModelConfig> aiModelConfigs = new ConcurrentHashMap<>();
    private volatile DailyRiskSnapshot dailyRiskSnapshot;
    private final List<String> auditLogs = new ArrayList<>();

    public SemiRiskStore() {
        users.put("admin", new UserAccount("admin", "password", "管理员", SemiriskConstants.ROLE_ADMIN));
        users.put("analyst", new UserAccount("analyst", "risk2026", "风险分析师", SemiriskConstants.ROLE_ANALYST));
        users.put("ops", new UserAccount("ops", "ops2026", "运营人员", SemiriskConstants.ROLE_OPERATOR));
        seedAlerts();
        seedUsers();
        auditLogs.add("[INFO] 2026-06-03 09:00:00 gateway route table initialized");
        auditLogs.add("[WARN] 2026-06-03 09:04:12 rag-service embedding queue latency 820ms");
        auditLogs.add("[ERROR] 2026-06-03 09:08:31 gis webhook handshake timeout");
        refreshDailyRiskRecords();
    }

    @Scheduled(cron = "0 0 0 * * *")
    public void refreshDailyRiskRecords() {
        List<CrawlerSignal> signals = List.of(
                crawlerSignal("Reuters Commodity Feed", "稀有金属价格波动扩大，采购成本承压", "原材料", 76),
                crawlerSignal("Freight Waves", "东南亚港口等待时间高于历史均值", "物流", 84),
                crawlerSignal("Policy Monitor", "出口管制政策出现新解释口径", "合规", 69)
        );
        int score = signals.stream().mapToInt(CrawlerSignal::riskScore).max().orElse(65);
        String level = score >= 80 ? "高危" : score >= 60 ? "中危" : "低危";
        dailyRiskSnapshot = new DailyRiskSnapshot(score, level,
                "AI 自动测算：" + level + "，本日风险分 " + score + "，由爬虫情报与历史基线共同计算。",
                signals, Instant.now());
        auditLogs.add("[INFO] daily crawler refresh and AI risk calculation completed score=" + score);
    }

    public Optional<UserAccount> authenticate(String username, String password) {
        UserAccount account = users.get(username);
        if (account == null || !account.enabled() || !account.password().equals(password)) {
            return Optional.empty();
        }
        loginCounters.remove(username);
        return Optional.of(account);
    }

    public UserAccount register(String username, String password, String displayName) {
        if (users.containsKey(username)) {
            throw new IllegalArgumentException("账号已存在");
        }
        UserAccount account = new UserAccount(username, password, displayName, SemiriskConstants.ROLE_OPERATOR);
        users.put(username, account);
        addSystemUser(username, username + "@risk.com", "运营人员");
        auditLogs.add("[INFO] public registration completed username=" + username);
        return account;
    }

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

    public String createResetToken(String email) {
        String token = UUID.randomUUID().toString().replace("-", "");
        resetTokens.put(token, Instant.now().plus(15, ChronoUnit.MINUTES));
        auditLogs.add("[INFO] password reset token issued for " + email);
        return token;
    }

    public List<RiskAlert> alerts() {
        return alerts.values().stream()
                .sorted(Comparator.comparing(RiskAlert::time).reversed())
                .toList();
    }

    public Optional<RiskAlert> alert(String id) {
        return Optional.ofNullable(alerts.get(id));
    }

    public RiskAlert updateAlertStatus(String id, String status) {
        RiskAlert current = alerts.get(id);
        if (current == null) {
            throw new IllegalArgumentException("告警不存在");
        }
        RiskAlert updated = new RiskAlert(current.id(), current.time(), current.level(), current.title(), current.source(), status, current.target());
        alerts.put(id, updated);
        auditLogs.add("[INFO] alert " + id + " marked as " + status);
        return updated;
    }

    public UploadTask createUpload(MultipartFile file) throws IOException {
        if (file.getSize() > 50L * 1024L * 1024L) {
            throw new IllegalArgumentException("单个文件不能超过 50MB");
        }
        String id = "UP-" + System.currentTimeMillis();
        String status = file.getOriginalFilename() != null && file.getOriginalFilename().toLowerCase(Locale.ROOT).endsWith(".zip")
                ? "待解压"
                : "解析中";
        UploadTask task = new UploadTask(id, file.getOriginalFilename(), file.getSize(), status, Instant.now(), 0, List.of());
        uploadTasks.put(id, task);
        auditLogs.add("[INFO] upload accepted " + file.getOriginalFilename() + " size=" + file.getSize());
        return task;
    }

    public UploadTask advanceUpload(String id) {
        UploadTask task = uploadTasks.get(id);
        if (task == null) {
            throw new IllegalArgumentException("上传任务不存在");
        }
        int rows = Math.max(task.rows(), ThreadLocalRandom.current().nextInt(120, 1300));
        UploadTask done = new UploadTask(task.id(), task.filename(), task.size(), "导入成功", task.createdAt(), rows,
                List.of("[WARN] 缺失字段 lead_time_days 已按临近均值填充", "[INFO] 供应商实体语义归并完成"));
        uploadTasks.put(id, done);
        return done;
    }

    public List<UploadTask> uploadTasks() {
        return uploadTasks.values().stream().sorted(Comparator.comparing(UploadTask::createdAt).reversed()).toList();
    }

    public ReportJob createReport(String template, String language, String format, int threshold) {
        String id = "RP-" + System.currentTimeMillis();
        ReportJob job = new ReportJob(id, template, language, format, threshold, "排队中", 0, "任务已进入 AI 编译队列", null, Instant.now());
        reportJobs.put(id, job);
        auditLogs.add("[INFO] report job created " + id);
        return job;
    }

    public ReportJob advanceReport(String id) {
        ReportJob job = reportJobs.get(id);
        if (job == null) {
            throw new IllegalArgumentException("报告任务不存在");
        }
        int progress = Math.min(100, job.progress() + ThreadLocalRandom.current().nextInt(18, 34));
        String status = progress >= 100 ? "已完成" : "生成中";
        String step = switch (Math.min(progress / 25, 4)) {
            case 0 -> "聚合风险事件与供应商画像";
            case 1 -> "调用 AI 生成风险摘要";
            case 2 -> "编排处置建议与图表";
            case 3 -> "渲染导出文件";
            default -> "报告文件已生成";
        };
        String downloadUrl = progress >= 100 ? "/api/reports/" + id + "/download" : null;
        ReportJob updated = new ReportJob(job.id(), job.template(), job.language(), job.format(), job.threshold(), status, progress, step, downloadUrl, job.createdAt());
        reportJobs.put(id, updated);
        return updated;
    }

    public Optional<ReportJob> reportJob(String id) {
        return Optional.ofNullable(reportJobs.get(id));
    }

    public List<SystemUser> systemUsers() {
        return systemUsers.values().stream().sorted(Comparator.comparing(SystemUser::username)).toList();
    }

    public SystemUser addSystemUser(String username, String email, String role) {
        String id = "U" + (1000 + systemUsers.size() + 1);
        SystemUser user = new SystemUser(id, username, email, role, "启用");
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

    public List<String> auditLogs() {
        return List.copyOf(auditLogs);
    }

    public Map<String, Object> dashboard() {
        DailyRiskSnapshot snapshot = dailyRiskSnapshot;
        return Map.of(
                "kpis", List.of(
                        Map.of("name", "总风险事件数", "value", 1286, "trend", "+12.4%"),
                        Map.of("name", "今日新增", "value", snapshot.signals().size() * 12 + 1, "trend", "+8"),
                        Map.of("name", "已处理件数", "value", 914, "trend", "+26"),
                        Map.of("name", "闭环处理率", "value", "71.1%", "trend", "+3.2%")
                ),
                "hotspots", List.of(
                        Map.of("name", "新加坡港", "lon", 103.8, "lat", 1.29, "level", "高危", "score", 91),
                        Map.of("name", "鹿特丹港", "lon", 4.47, "lat", 51.92, "level", "中危", "score", 68),
                        Map.of("name", "洛杉矶港", "lon", -118.26, "lat", 33.74, "level", "中危", "score", 64)
                ),
                "ranking", alerts().stream().limit(5).toList(),
                "materials", List.of(
                        Map.of("name", "锂电池材料", "index", 82),
                        Map.of("name", "稀有金属", "index", 74),
                        Map.of("name", "高分子材料", "index", 47)
                ),
                "stages", List.of("原材料采集:中危", "生产制造:低危", "仓储物流:高危", "终端销售:低危"),
                "aiSummary", snapshot.summary(),
                "dailyRisk", snapshot,
                "refreshedAt", Instant.now().toString()
        );
    }

    public Map<String, Object> riskAnalysis(String window) {
        return Map.of(
                "window", window,
                "score", 78,
                "summary", "罢工、财报下调与海运延迟同时出现，AI 判断物流维度是当前系统性风险主因。",
                "dimensions", List.of(Map.of("name", "物流", "value", 42), Map.of("name", "财务", "value", 24), Map.of("name", "合规", "value", 19), Map.of("name", "地缘", "value", 15)),
                "sources", List.of(Map.of("name", "外部新闻", "value", 48), Map.of("name", "财报", "value", 31), Map.of("name", "传感器", "value", 21)),
                "reasoning", List.of("数据输入: 港口拥堵、交期拉长", "逻辑关联: 关键供应商覆盖率高", "风险结论: 未来 7 天断供概率升高"),
                "solutions", List.of(
                        Map.of("name", "启用华南备用仓", "feasibility", 88),
                        Map.of("name", "切换现货供应商", "feasibility", 76),
                        Map.of("name", "提升安全库存阈值", "feasibility", 69)
                )
        );
    }

    public Map<String, Object> riskDetail(String id) {
        return Map.of(
                "id", id,
                "type", "物流中断",
                "firstSeen", "2026-06-03T08:42:00+08:00",
                "status", alert(id).map(RiskAlert::status).orElse("处理中"),
                "scope", "东南亚港口、一级供应商 3 家、二级供应商 9 家",
                "weeklyLoss", 860000,
                "timeline", List.of("基准监控", "异常信号捕获", "风险等级上调", "处置策略生成"),
                "path", List.of("新加坡港", "一级供应商 A", "二级封测厂", "总装线"),
                "sop", List.of("核查未来 14 天库存", "询价备用供应商", "切换转运路线", "下发处置报告"),
                "enterprises", List.of("安芯物流", "华南晶圆", "北美封测")
        );
    }

    public Map<String, Object> enterprise(String keyword) {
        String name = keyword == null || keyword.isBlank() ? "安芯半导体供应链有限公司" : keyword;
        return Map.of(
                "name", name,
                "creditCode", "91310000MA1RISK2026",
                "cooperationYears", 6,
                "industry", "半导体供应链",
                "riskScore", 72,
                "creditLevel", "A",
                "business", Map.of("legalPerson", "陈启明", "capital", "5000 万人民币", "founded", "2018-04-12", "type", "有限责任公司", "status", "存续"),
                "radar", List.of(76, 68, 61, 83, 72),
                "topology", List.of("上游核心晶圆厂", name, "贵司总装", "物流承运方"),
                "events", List.of("2026-05 财报异常预警", "2025-11 扩产验收成功", "2025-03 交期违约一次")
        );
    }

    public Map<String, Object> gis(String layers) {
        return Map.of(
                "layers", layers == null ? "heatmap,suppliers,ports,routes" : layers,
                "regions", List.of(
                        Map.of("name", "东南亚", "status", "港口积压升高", "score", 91),
                        Map.of("name", "北美", "status", "铁路转运延误", "score", 67)
                ),
                "points", List.of(
                        Map.of("name", "新加坡港", "lon", 103.8, "lat", 1.29, "riskIndex", 91, "analysis", "等待泊位时间超过历史均值 37%"),
                        Map.of("name", "上海港", "lon", 121.49, "lat", 31.23, "riskIndex", 42, "analysis", "航线运行稳定")
                )
        );
    }

    public Map<String, Object> knowledge(String query) {
        String q = query == null || query.isBlank() ? "半导体物流中断" : query;
        return Map.of(
                "query", q,
                "categories", List.of("行业标准与规范(128)", "历史风险案例(356)", "政策法规库(92)"),
                "tags", List.of("#半导体", "#物流中断", "#东南亚", "#国产替代", "#供应商尽调"),
                "results", List.of(
                        Map.of("id", "KB-001", "title", "港口拥堵场景供应链处置 SOP", "format", "PDF", "size", "1.8MB", "similarity", 94, "summary", "建议建立备用港口切换阈值和安全库存联动规则。"),
                        Map.of("id", "KB-002", "title", "半导体关键物料断供案例复盘", "format", "DOCX", "size", "940KB", "similarity", 88, "summary", "涵盖晶圆、封测、物流三类高频断点。")
                )
        );
    }

    public Map<String, Object> systemOverview() {
        return Map.of(
                "users", systemUsers(),
                "roles", List.of("管理员", "分析师", "运营人员"),
                "models", List.of(
                        modelOverview("GPT-4o", "https://api.openai.com/v1", 321),
                        modelOverview("Claude", "https://api.anthropic.com/v1", 425)
                ),
                "agents", List.of(
                        Map.of("name", "舆情监控 Agent", "status", "运行中", "cron", "*/15 * * * *", "lastPull", Instant.now().minus(8, ChronoUnit.MINUTES).toString()),
                        Map.of("name", "财务监视 Agent", "status", "运行中", "cron", "0 */1 * * *", "lastPull", Instant.now().minus(22, ChronoUnit.MINUTES).toString())
                ),
                "logs", auditLogs(),
                "dataSources", List.of(
                        Map.of("name", "SAP ERP", "status", "健康", "host", "192.168.101.128:3306"),
                        Map.of("name", "三方 GIS Webhook", "status", "阻断", "host", "192.168.101.128:8088")
                )
        );
    }

    public AiModelConfig saveAiModelConfig(String model, String endpoint, String apiKey) {
        AiModelConfig config = new AiModelConfig(model, endpoint, mask(apiKey), apiKey != null && !apiKey.isBlank(), Instant.now());
        aiModelConfigs.put(model, config);
        auditLogs.add("[INFO] AI model config saved for " + model + " endpoint=" + endpoint);
        return config;
    }

    public Map<String, AiModelConfig> aiModelConfigs() {
        return Map.copyOf(aiModelConfigs);
    }

    public DailyRiskSnapshot dailyRiskSnapshot() {
        return dailyRiskSnapshot;
    }

    private Map<String, Object> modelOverview(String name, String endpoint, int latencyMs) {
        AiModelConfig config = aiModelConfigs.get(name);
        Map<String, Object> map = new HashMap<>();
        map.put("name", name);
        map.put("endpoint", config == null ? endpoint : config.endpoint());
        map.put("latencyMs", latencyMs);
        map.put("status", config != null && config.configured() ? "已配置" : "待配置 API Key");
        return map;
    }

    private CrawlerSignal crawlerSignal(String source, String title, String dimension, int riskScore) {
        return new CrawlerSignal("CS-" + UUID.randomUUID().toString().substring(0, 8), source, title, dimension, riskScore, Instant.now());
    }

    private String mask(String key) {
        if (key == null || key.length() < 8) {
            return "未配置";
        }
        return key.substring(0, 4) + "****" + key.substring(key.length() - 4);
    }

    private void seedAlerts() {
        alerts.put("RA-20260603-001", new RiskAlert("RA-20260603-001", Instant.now().minus(40, ChronoUnit.MINUTES), "高危", "新加坡港拥堵影响封测物料交付", "GIS Agent", "未处理", "risk-detail.html"));
        alerts.put("RA-20260603-002", new RiskAlert("RA-20260603-002", Instant.now().minus(2, ChronoUnit.HOURS), "中危", "稀有金属报价连续三日上行", "Price Agent", "处理中", "risk-detail.html"));
        alerts.put("RA-20260603-003", new RiskAlert("RA-20260603-003", Instant.now().minus(5, ChronoUnit.HOURS), "低危", "供应商工商信息发生变更", "Compliance Agent", "未处理", "enterprise-profile.html"));
        alerts.put("RA-20260602-004", new RiskAlert("RA-20260602-004", Instant.now().minus(1, ChronoUnit.DAYS), "高危", "一级供应商现金流评级下调", "Finance Agent", "未处理", "enterprise-profile.html"));
    }

    private void seedUsers() {
        systemUsers.put("U1001", new SystemUser("U1001", "admin", "admin@risk.com", "管理员", "启用"));
        systemUsers.put("U1002", new SystemUser("U1002", "analyst", "analyst@risk.com", "分析师", "启用"));
        systemUsers.put("U1003", new SystemUser("U1003", "ops", "ops@risk.com", "运营人员", "禁用"));
    }

    public record UserAccount(String username, String password, String displayName, String role, boolean enabled) {
        public UserAccount(String username, String password, String displayName, String role) {
            this(username, password, displayName, role, true);
        }
    }

    public record LoginCounter(int failures, Instant windowStarted, Instant lockedUntil) {
    }

    public record LoginState(boolean locked, int failures, Instant lockedUntil) {
    }

    public record RiskAlert(String id, Instant time, String level, String title, String source, String status, String target) {
    }

    public record UploadTask(String id, String filename, long size, String status, Instant createdAt, int rows, List<String> warnings) {
    }

    public record ReportJob(String id, String template, String language, String format, int threshold, String status, int progress, String step, String downloadUrl, Instant createdAt) {
    }

    public record SystemUser(String id, String username, String email, String role, String status) {
    }

    public record CrawlerSignal(String id, String source, String title, String dimension, int riskScore, Instant fetchedAt) {
    }

    public record DailyRiskSnapshot(int score, String level, String summary, List<CrawlerSignal> signals, Instant calculatedAt) {
    }

    public record AiModelConfig(String model, String endpoint, String maskedApiKey, boolean configured, Instant updatedAt) {
    }
}
