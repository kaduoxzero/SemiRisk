package com.semirisk.service;

import com.semirisk.common.AiModelDefaults;
import com.semirisk.common.SemiriskConstants;
import org.springframework.beans.factory.annotation.Value;
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
import java.util.regex.Pattern;

@Service
public class SemiRiskStore {

    private final Map<String, UserAccount> users = new ConcurrentHashMap<>();
    private final Map<String, LoginCounter> loginCounters = new ConcurrentHashMap<>();
    private final Map<String, Instant> resetTokens = new ConcurrentHashMap<>();
    private final Map<String, UploadTask> uploadTasks = new ConcurrentHashMap<>();
    private final Map<String, ReportJob> reportJobs = new ConcurrentHashMap<>();
    private final Map<String, RiskAlert> alerts = new ConcurrentHashMap<>();
    private final Map<String, String> publicAlertStatuses = new ConcurrentHashMap<>();
    private final Map<String, SystemUser> systemUsers = new ConcurrentHashMap<>();
    private final Map<String, AiModelConfig> aiModelConfigs = new ConcurrentHashMap<>();
    private volatile DailyRiskSnapshot dailyRiskSnapshot;
    private final List<String> auditLogs = new ArrayList<>();
    private final String defaultAiModel;
    private final String defaultAiEndpoint;
    private final String defaultAiApiKey;

    public SemiRiskStore(
            @Value("${semirisk.ai.default.model:" + AiModelDefaults.DEFAULT_MODEL + "}") String defaultAiModel,
            @Value("${semirisk.ai.default.endpoint:" + AiModelDefaults.DEFAULT_ENDPOINT + "}") String defaultAiEndpoint,
            @Value("${semirisk.ai.default.api-key:}") String defaultAiApiKey) {
        this.defaultAiModel = defaultAiModel;
        this.defaultAiEndpoint = defaultAiEndpoint;
        this.defaultAiApiKey = defaultAiApiKey;
        seedDefaultAiModel();
        auditLogs.add("[INFO] gateway route table initialized");
        refreshDailyRiskRecords();
    }

    @Scheduled(cron = "0 0 */12 * * *")
    public void refreshDailyRiskRecords() {
        auditLogs.add("[INFO] gateway scheduled refresh tick; waiting for data-service public crawler sync");
    }

    public void refreshDailyRiskRecords(List<CrawlerSignal> signals) {
        List<CrawlerSignal> collected = signals == null ? List.of() : List.copyOf(signals);
        List<CrawlerSignal> availableSignals = collected.stream()
                .filter(signal -> "OK".equalsIgnoreCase(signal.status()))
                .toList();
        if (availableSignals.isEmpty()) {
            dailyRiskSnapshot = new DailyRiskSnapshot(0, "待采集",
                    "公开源暂未成功采集，本日风险测算等待 data-service 获取公开网站数据后刷新。",
                    collected, Instant.now());
            auditLogs.add("[WARN] daily crawler refresh completed without public source records");
            return;
        }
        int score = availableSignals.stream().mapToInt(CrawlerSignal::riskScore).max().orElse(0);
        String level = score >= 80 ? "高危" : score >= 60 ? "中危" : "低危";
        dailyRiskSnapshot = new DailyRiskSnapshot(score, level,
                "AI 自动测算：" + level + "，本日风险分 " + score + "，由公开网站爬虫记录和风险规则共同计算。",
                collected, Instant.now());
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

    public UserAccount register(String username, String password, String displayName, String email) {
        if (users.containsKey(username)) {
            throw new IllegalArgumentException("账号已存在");
        }
        boolean emailExists = systemUsers.values().stream().anyMatch(user -> user.email().equalsIgnoreCase(email));
        if (emailExists) {
            throw new IllegalArgumentException("邮箱已被注册");
        }
        String role = users.isEmpty() ? SemiriskConstants.ROLE_ADMIN : SemiriskConstants.ROLE_OPERATOR;
        UserAccount account = new UserAccount(username, password, displayName, role);
        users.put(username, account);
        addSystemUser(username, email, role);
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
            return publicSignalAlerts().stream()
                    .filter(alert -> alert.id().equals(id))
                    .findFirst()
                    .map(alert -> {
                        publicAlertStatuses.put(id, status);
                        auditLogs.add("[INFO] public alert " + id + " marked as " + status);
                        return new RiskAlert(alert.id(), alert.time(), alert.level(), alert.title(), alert.source(), alert.sourceUrl(), status, alert.target());
                    })
                    .orElseThrow(() -> new IllegalArgumentException("告警不存在"));
        }
        RiskAlert updated = new RiskAlert(current.id(), current.time(), current.level(), current.title(), current.source(), current.sourceUrl(), status, current.target());
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

    public List<String> auditLogs() {
        return List.copyOf(auditLogs);
    }

    public List<RiskAlert> publicSignalAlerts() {
        return publicAlerts(availableSignals());
    }

    public Map<String, Object> dashboard() {
        DailyRiskSnapshot snapshot = dailyRiskSnapshot;
        List<CrawlerSignal> availableSignals = availableSignals();
        long highCount = availableSignals.stream().filter(signal -> signal.riskScore() >= 80).count();
        long handled = publicSignalAlerts().stream().filter(alert -> "处理中".equals(alert.status()) || "已处理".equals(alert.status())).count();
        long totalEvents = availableSignals.size();
        String closureRate = totalEvents == 0 ? "0%" : String.format(Locale.ROOT, "%.1f%%", handled * 100.0 / totalEvents);
        return Map.of(
                "kpis", List.of(
                        Map.of("name", "公开源事件数", "value", availableSignals.size(), "trend", "公开网站"),
                        Map.of("name", "今日新增", "value", snapshot.signals().size(), "trend", "爬虫记录"),
                        Map.of("name", "高危信号", "value", highCount, "trend", "规则评分"),
                        Map.of("name", "闭环处理率", "value", closureRate, "trend", "告警处置")
                ),
                "hotspots", gisPoints(availableSignals).stream().limit(4).toList(),
                "ranking", publicAlerts(availableSignals).stream().limit(5).toList(),
                "materials", dimensionScores(availableSignals),
                "stages", availableSignals.isEmpty()
                        ? List.of("公开源采集:待采集", "规则评分:待采集", "AI测算:待采集", "处置闭环:待派发")
                        : List.of("公开源采集:已完成", "规则评分:" + snapshot.level(), "AI测算:" + snapshot.level(), "处置闭环:待派发"),
                "aiSummary", snapshot.summary(),
                "dailyRisk", snapshot,
                "dataMode", availableSignals.isEmpty() ? "WAITING_PUBLIC_SOURCE" : "PUBLIC_CRAWLED",
                "dataSource", "semirisk-data-service 公开 RSS 采集",
                "refreshedAt", Instant.now().toString()
        );
    }

    public Map<String, Object> riskAnalysis(String window) {
        DailyRiskSnapshot snapshot = dailyRiskSnapshot;
        List<CrawlerSignal> availableSignals = availableSignals();
        return Map.of(
                "window", window,
                "score", snapshot.score(),
                "summary", snapshot.summary(),
                "dimensions", dimensionScores(availableSignals),
                "sources", sourceScores(availableSignals),
                "reasoning", availableSignals.isEmpty()
                        ? List.of("数据输入: 公开源暂无成功采集记录", "逻辑关联: 暂停自动推理", "风险结论: 等待下一次爬虫刷新")
                        : availableSignals.stream().limit(5).map(signal -> "公开源: " + signal.source() + " / " + signal.title()).toList(),
                "solutions", List.of(
                        Map.of("name", "人工复核公开源原文", "feasibility", availableSignals.isEmpty() ? 0 : 92),
                        Map.of("name", "将高危信号转为告警工单", "feasibility", availableSignals.isEmpty() ? 0 : 84),
                        Map.of("name", "按维度同步采购/物流负责人", "feasibility", availableSignals.isEmpty() ? 0 : 78)
                )
        );
    }

    public Map<String, Object> riskDetail(String id) {
        Optional<CrawlerSignal> signal = dailyRiskSnapshot.signals().stream().filter(item -> item.id().equals(id)).findFirst();
        if (signal.isPresent()) {
            CrawlerSignal current = signal.get();
            String level = riskLevel(current.riskScore());
            String status = publicAlertStatuses.getOrDefault(current.id(), "未处理");
            Map<String, String> translation = titleTranslation(current.title());
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("alertOnly", true);
            detail.put("id", current.id());
            detail.put("type", current.dimension());
            detail.put("firstSeen", current.fetchedAt().toString());
            detail.put("level", level);
            detail.put("status", status);
            detail.put("source", current.source());
            detail.put("sourceUrl", current.sourceUrl());
            detail.put("originalTitle", current.title());
            detail.put("title", translation.get("zh"));
            detail.put("titleEn", translation.get("en"));
            detail.put("riskScore", current.riskScore());
            detail.put("crawlerStatus", current.status());
            detail.put("translation", translation);
            detail.put("bilingualRows", List.of(
                    bilingualRow("告警编号", "Alert ID", current.id(), current.id()),
                    bilingualRow("发布时间", "Published At", current.fetchedAt().toString(), current.fetchedAt().toString()),
                    bilingualRow("风险等级", "Risk Level", level, levelName(level)),
                    bilingualRow("风险维度", "Risk Dimension", current.dimension(), dimensionName(current.dimension())),
                    bilingualRow("告警状态", "Alert Status", status, statusName(status)),
                    bilingualRow("公开来源", "Public Source", current.source(), current.source()),
                    bilingualRow("中文译文", "Chinese Translation", translation.get("zh"), translation.get("zh")),
                    bilingualRow("英文原文/译文", "English Original/Translation", translation.get("en"), translation.get("en"))
            ));
            return detail;
        }
        Map<String, Object> missing = new LinkedHashMap<>();
        missing.put("alertOnly", true);
        missing.put("id", id);
        missing.put("type", "待采集");
        missing.put("firstSeen", "");
        missing.put("level", "待采集");
        missing.put("status", "未找到公开源记录");
        missing.put("source", "公开源");
        missing.put("sourceUrl", "");
        missing.put("originalTitle", "公开源暂无匹配记录");
        missing.put("title", "公开源暂无匹配记录");
        missing.put("titleEn", "No matching public-source alert was found");
        missing.put("riskScore", 0);
        missing.put("bilingualRows", List.of(
                bilingualRow("告警编号", "Alert ID", id, id),
                bilingualRow("状态", "Status", "未找到公开源记录", "No matching public-source alert was found")
        ));
        return missing;
    }

    private Map<String, Object> bilingualRow(String zhLabel, String enLabel, Object zhValue, Object enValue) {
        return Map.of(
                "zhLabel", zhLabel,
                "enLabel", enLabel,
                "zh", zhValue == null ? "" : String.valueOf(zhValue),
                "en", enValue == null ? "" : String.valueOf(enValue)
        );
    }

    private Map<String, String> titleTranslation(String title) {
        String original = title == null ? "" : title.trim();
        if (original.isBlank()) {
            return Map.of("zh", "公开源标题为空", "en", "The public-source title is empty");
        }
        if (containsChinese(original)) {
            return Map.of("zh", original, "en", translateChineseTitle(original));
        }
        return Map.of("zh", translateEnglishTitle(original), "en", original);
    }

    private String translateEnglishTitle(String title) {
        String translated = title;
        String[][] terms = {
                {"trump admin", "特朗普政府"},
                {"u.s.", "美国"},
                {"us", "美国"},
                {"china", "中国"},
                {"eu", "欧盟"},
                {"mexico", "墨西哥"},
                {"brazil", "巴西"},
                {"taiwan", "台湾"},
                {"appeals", "提出上诉"},
                {"raises stakes", "提高风险权重"},
                {"aspects of", "部分内容"},
                {"refund order", "退款令"},
                {"labor probes", "劳工调查"},
                {"forced labor", "强迫劳动"},
                {"rare earth", "稀土"},
                {"industrial base", "产业基础"},
                {"data center", "数据中心"},
                {"data centers", "数据中心"},
                {"market surges", "市场增长"},
                {"must turn", "必须将"},
                {"industrial reality", "产业现实"},
                {"supply chain", "供应链"},
                {"semiconductor", "半导体"},
                {"semiconductors", "半导体"},
                {"manufacturing", "制造"},
                {"manufacturer", "制造商"},
                {"manufacturers", "制造商"},
                {"logistics", "物流"},
                {"freight", "货运"},
                {"trucking", "公路运输"},
                {"port", "港口"},
                {"ports", "港口"},
                {"delay", "延迟"},
                {"delays", "延迟"},
                {"strike", "罢工"},
                {"strikes", "罢工"},
                {"shortage", "短缺"},
                {"shortages", "短缺"},
                {"disruption", "中断"},
                {"disruptions", "中断"},
                {"tariff", "关税"},
                {"tariffs", "关税"},
                {"export", "出口"},
                {"exports", "出口"},
                {"imports", "进口"},
                {"restriction", "限制"},
                {"restrictions", "限制"},
                {"risk", "风险"},
                {"risks", "风险"},
                {"warning", "预警"},
                {"recall", "召回"},
                {"chip", "芯片"},
                {"chips", "芯片"},
                {"automotive", "汽车"},
                {"electronics", "电子"},
                {"supplier", "供应商"},
                {"suppliers", "供应商"},
                {"steel", "钢铁"},
                {"aluminum", "铝"},
                {"copper", "铜"},
                {"factory", "工厂"},
                {"invest", "投资"},
                {"global", "全球"},
                {"europe", "欧洲"},
                {"ambition", "目标"},
                {"order", "命令"},
                {"probe", "调查"},
                {"probes", "调查"}
        };
        for (String[] term : terms) {
            translated = translated.replaceAll("(?i)" + Pattern.quote(term[0]), term[1]);
        }
        return translated.equals(title) ? "原文为英文，暂无关键词命中：" + title : translated;
    }

    private String translateChineseTitle(String title) {
        String translated = title
                .replace("公开源返回内容未解析到 RSS/Atom 条目", "Public source returned no parseable RSS/Atom entries")
                .replace("近三天未发现 RSS/Atom 条目", "No RSS/Atom entries were found in the last three days")
                .replace("公开源采集失败", "Public source collection failed")
                .replace("采集失败", "Collection failed")
                .replace("供应链", "supply chain")
                .replace("半导体", "semiconductor")
                .replace("物流", "logistics")
                .replace("制造", "manufacturing")
                .replace("中断", "disruption")
                .replace("拥堵", "congestion")
                .replace("短缺", "shortage")
                .replace("关税", "tariff")
                .replace("出口", "export")
                .replace("风险", "risk");
        return translated.equals(title) ? "Chinese source title: " + title : translated;
    }

    private boolean containsChinese(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c >= '\u4e00' && c <= '\u9fff') {
                return true;
            }
        }
        return false;
    }

    private String levelName(String level) {
        return switch (level) {
            case "高危" -> "High";
            case "中危" -> "Medium";
            case "低危" -> "Low";
            default -> "Pending";
        };
    }

    private String statusName(String status) {
        return switch (status) {
            case "未处理" -> "Open";
            case "处理中" -> "In Progress";
            case "已忽略" -> "Ignored";
            default -> status;
        };
    }

    private String dimensionName(String dimension) {
        return switch (dimension) {
            case "供应链" -> "Supply Chain";
            case "物流" -> "Logistics";
            case "半导体" -> "Semiconductor";
            case "制造" -> "Manufacturing";
            default -> dimension;
        };
    }

    public Map<String, Object> enterprise(String keyword) {
        String name = keyword == null || keyword.isBlank() ? "请输入企业名称后搜索" : keyword;
        List<CrawlerSignal> relatedSignals = availableSignals().stream()
                .filter(signal -> keyword == null || keyword.isBlank() || signalMatches(signal, keyword))
                .toList();
        int score = relatedSignals.stream().mapToInt(CrawlerSignal::riskScore).max().orElse(0);
        return Map.of(
                "name", name,
                "creditCode", "公开工商数据源未接入",
                "cooperationYears", "待内部 ERP 同步",
                "industry", relatedSignals.stream().findFirst().map(CrawlerSignal::dimension).orElse("待公开源确认"),
                "riskScore", score,
                "creditLevel", riskLevel(score),
                "business", Map.of("legalPerson", "待公开工商接口返回", "capital", "待公开工商接口返回", "founded", "待公开工商接口返回", "type", "待公开工商接口返回", "status", "待公开工商接口返回"),
                "radar", List.of(score, Math.max(0, score - 8), Math.max(0, score - 16), Math.max(0, score - 12), score),
                "topology", List.of("公开网站信号", name, "SemiRisk 风控工作台"),
                "events", relatedSignals.stream().limit(6).map(signal -> signal.fetchedAt() + " " + signal.title()).toList()
        );
    }

    public Map<String, Object> gis(String layers) {
        List<CrawlerSignal> availableSignals = availableSignals();
        List<Map<String, Object>> points = gisPoints(availableSignals);
        return Map.of(
                "layers", layers == null ? "heatmap,suppliers,ports,routes" : layers,
                "regions", regionsFromSignals(availableSignals),
                "points", points,
                "routes", gisRoutes(points),
                "updatedAt", dailyRiskSnapshot.calculatedAt().toString(),
                "dataSource", "公开网站 RSS 条目映射到来源区域，仅用于风险空间视图"
        );
    }

    public Map<String, Object> knowledge(String query) {
        String q = query == null || query.isBlank() ? "半导体物流中断" : query;
        List<CrawlerSignal> matchedSignals = availableSignals().stream()
                .filter(signal -> signalMatches(signal, q) || q.isBlank())
                .toList();
        return Map.of(
                "query", q,
                "searchEngine", "LocalPublicCrawler",
                "categories", List.of("公开源文章(" + matchedSignals.size() + ")", "内部知识库(待接入)", "政策法规库(待接入)"),
                "tags", List.of("#半导体", "#物流", "#供应链", "#公开源", "#风险信号"),
                "results", matchedSignals.stream().limit(30).map(signal -> Map.of(
                        "id", signal.id(),
                        "title", signal.title(),
                        "format", "WEB",
                        "size", "公开网页",
                        "similarity", signal.riskScore(),
                        "summary", signal.source() + " / " + signal.dimension() + " / " + signal.fetchedAt(),
                        "url", signal.sourceUrl()
                )).toList()
        );
    }

    public Map<String, Object> knowledge(String query, List<Map<String, Object>> indexedResults) {
        String q = query == null || query.isBlank() ? "半导体物流中断" : query;
        List<Map<String, Object>> results = normalizeIndexedKnowledgeResults(indexedResults);
        if (results.isEmpty()) {
            return knowledge(q);
        }
        return Map.of(
                "query", q,
                "searchEngine", "Elasticsearch",
                "categories", List.of("ES 公开源索引(" + results.size() + ")", "内部知识库(待接入)", "政策法规库(待接入)"),
                "tags", List.of("#半导体", "#物流", "#供应链", "#Elasticsearch", "#RAG"),
                "results", results
        );
    }

    public Map<String, Object> askKnowledgeAgent(String question) {
        String q = question == null || question.isBlank() ? "请总结当前供应链风险" : question.trim();
        List<CrawlerSignal> candidates = availableSignals();
        List<CrawlerSignal> matched = candidates.stream()
                .filter(signal -> signalMatches(signal, q))
                .toList();
        if (matched.isEmpty()) {
            matched = candidates.stream().limit(5).toList();
        }
        String answer;
        if (matched.isEmpty()) {
            answer = "知识库公开源暂无成功采集记录，无法给出可信问答结果。请先确认 data-service 可以访问公开 RSS 源。";
        } else {
            CrawlerSignal top = matched.get(0);
            answer = "基于知识库检索到的公开源记录，当前最相关风险来自 " + top.source()
                    + "，维度为“" + top.dimension() + "”，规则评分 " + top.riskScore()
                    + "。建议先打开引用原文核验事实，再将高分信号转为告警工单。";
        }
        return Map.of(
                "question", q,
                "answer", answer,
                "model", defaultAiModel,
                "modelStatus", aiModelConfigs.containsKey(defaultAiModel) ? "API Key 已配置，可扩展为真实 DeepSeek 调用" : "未配置 API Key，当前使用本地 RAG 摘要",
                "trace", List.of("Query Rewrite", "Knowledge Retrieval", "Risk Scoring", "Answer Synthesis"),
                "citations", matched.stream().limit(5).map(signal -> Map.of(
                        "id", signal.id(),
                        "title", signal.title(),
                        "source", signal.source(),
                        "sourceUrl", signal.sourceUrl(),
                        "score", signal.riskScore(),
                        "fetchedAt", signal.fetchedAt().toString()
                )).toList(),
                "answeredAt", Instant.now().toString()
        );
    }

    public Map<String, Object> askKnowledgeAgent(String question, List<Map<String, Object>> indexedResults) {
        String q = question == null || question.isBlank() ? "请总结当前供应链风险" : question.trim();
        List<Map<String, Object>> results = normalizeIndexedKnowledgeResults(indexedResults);
        if (results.isEmpty()) {
            return askKnowledgeAgent(q);
        }
        Map<String, Object> top = results.get(0);
        String answer = "基于 Elasticsearch 知识库检索到的公开源记录，当前最相关风险来自 "
                + stringValue(top.get("source")) + "，维度为“" + stringValue(top.get("dimension"))
                + "”，规则评分 " + top.getOrDefault("riskScore", 0)
                + "。建议先打开引用原文核验事实，再将高分信号转为告警工单或报告输入。";
        return Map.of(
                "question", q,
                "answer", answer,
                "model", defaultAiModel,
                "modelStatus", aiModelConfigs.containsKey(defaultAiModel) ? "API Key 已配置，可扩展为真实 DeepSeek 调用" : "未配置 API Key，当前使用 Elasticsearch RAG 摘要",
                "trace", List.of("Query Rewrite", "Elasticsearch Retrieval", "Risk Scoring", "Answer Synthesis"),
                "citations", results.stream().limit(5).map(result -> {
                    Map<String, Object> citation = new HashMap<>();
                    citation.put("id", stringValue(result.get("id")));
                    citation.put("title", stringValue(result.get("title")));
                    citation.put("source", stringValue(result.get("source")));
                    citation.put("sourceUrl", stringValue(result.get("sourceUrl")));
                    citation.put("score", result.getOrDefault("riskScore", 0));
                    citation.put("fetchedAt", stringValue(result.get("fetchedAt")));
                    citation.put("searchEngine", "Elasticsearch");
                    return citation;
                }).toList(),
                "answeredAt", Instant.now().toString()
        );
    }

    private List<Map<String, Object>> normalizeIndexedKnowledgeResults(List<Map<String, Object>> indexedResults) {
        if (indexedResults == null || indexedResults.isEmpty()) {
            return List.of();
        }
        return indexedResults.stream()
                .filter(result -> result != null && !result.isEmpty())
                .limit(30)
                .map(result -> {
                    Map<String, Object> item = new HashMap<>();
                    String source = stringValue(result.get("source"));
                    String dimension = stringValue(result.get("dimension"));
                    String fetchedAt = stringValue(result.get("fetchedAt"));
                    String sourceUrl = stringValue(result.get("sourceUrl"));
                    item.put("id", stringValue(result.get("id")));
                    item.put("title", stringValue(result.get("title")));
                    item.put("format", result.getOrDefault("format", "WEB"));
                    item.put("size", result.getOrDefault("size", "公开网页"));
                    item.put("similarity", result.getOrDefault("similarity", result.getOrDefault("riskScore", 0)));
                    item.put("summary", result.getOrDefault("summary", source + " / " + dimension + " / " + fetchedAt));
                    item.put("url", result.getOrDefault("url", sourceUrl));
                    item.put("source", source);
                    item.put("sourceUrl", sourceUrl);
                    item.put("dimension", dimension);
                    item.put("riskScore", result.getOrDefault("riskScore", 0));
                    item.put("fetchedAt", fetchedAt);
                    item.put("searchEngine", result.getOrDefault("searchEngine", "Elasticsearch"));
                    if (result.containsKey("searchScore")) {
                        item.put("searchScore", result.get("searchScore"));
                    }
                    return item;
                })
                .toList();
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    public Map<String, Object> systemOverview() {
        return Map.of(
                "users", systemUsers(),
                "roles", List.of("管理员", "分析师", "运营人员"),
                "models", List.of(
                        modelOverview(defaultAiModel, defaultAiEndpoint, 286),
                        modelOverview("deepseek-chat", AiModelDefaults.DEFAULT_ENDPOINT, 344)
                ),
                "agents", List.of(
                        Map.of("name", "舆情监控 Agent", "status", "运行中", "cron", "*/15 * * * *", "lastPull", Instant.now().minus(8, ChronoUnit.MINUTES).toString()),
                        Map.of("name", "财务监视 Agent", "status", "运行中", "cron", "0 */1 * * *", "lastPull", Instant.now().minus(22, ChronoUnit.MINUTES).toString())
                ),
                "logs", auditLogs(),
                "dataSources", List.of(
                        Map.of("name", "SAP ERP", "status", "健康", "host", "192.168.101.130:3306"),
                        Map.of("name", "三方 GIS Webhook", "status", "阻断", "host", "192.168.101.130:8088")
                )
        );
    }

    private List<CrawlerSignal> availableSignals() {
        DailyRiskSnapshot snapshot = dailyRiskSnapshot;
        if (snapshot == null) {
            return List.of();
        }
        return snapshot.signals().stream()
                .filter(signal -> "OK".equalsIgnoreCase(signal.status()))
                .sorted(Comparator.comparing(CrawlerSignal::riskScore).reversed())
                .toList();
    }

    private List<RiskAlert> publicAlerts(List<CrawlerSignal> signals) {
        return signals.stream()
                .sorted(Comparator.comparing(CrawlerSignal::riskScore).reversed())
                .map(signal -> new RiskAlert(signal.id(), signal.fetchedAt(), riskLevel(signal.riskScore()), signal.title(), signal.source(), signal.sourceUrl(), publicAlertStatuses.getOrDefault(signal.id(), "未处理"), "risk-detail.html"))
                .toList();
    }

    private List<Map<String, Object>> dimensionScores(List<CrawlerSignal> signals) {
        Map<String, Integer> scores = new LinkedHashMap<>();
        for (CrawlerSignal signal : signals) {
            scores.merge(signal.dimension(), signal.riskScore(), Math::max);
        }
        return scores.entrySet().stream()
                .map(entry -> Map.<String, Object>of("name", entry.getKey(), "index", entry.getValue(), "value", entry.getValue()))
                .toList();
    }

    private List<Map<String, Object>> sourceScores(List<CrawlerSignal> signals) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (CrawlerSignal signal : signals) {
            counts.merge(signal.source(), 1, Integer::sum);
        }
        return counts.entrySet().stream()
                .map(entry -> Map.<String, Object>of("name", entry.getKey(), "value", entry.getValue()))
                .toList();
    }

    private List<Map<String, Object>> regionsFromSignals(List<CrawlerSignal> signals) {
        if (signals.isEmpty()) {
            return List.of(Map.of("name", "公开源", "status", "暂无成功采集记录", "score", 0));
        }
        return dimensionScores(signals).stream()
                .map(item -> Map.<String, Object>of(
                        "name", String.valueOf(item.get("name")),
                        "status", "公开源维度信号",
                        "score", item.get("value")
                ))
                .toList();
    }

    private List<Map<String, Object>> gisPoints(List<CrawlerSignal> signals) {
        double[][] coordinates = {
                {-77.03, 38.90},
                {-118.26, 33.74},
                {103.80, 1.29},
                {121.49, 31.23},
                {4.47, 51.92},
                {139.76, 35.68}
        };
        List<Map<String, Object>> points = new ArrayList<>();
        for (int i = 0; i < signals.size(); i++) {
            CrawlerSignal signal = signals.get(i);
            double[] coordinate = coordinates[i % coordinates.length];
            points.add(Map.of(
                    "id", signal.id(),
                    "name", signal.source() + " #" + (i + 1),
                    "lon", coordinate[0],
                    "lat", coordinate[1],
                    "riskIndex", signal.riskScore(),
                    "analysis", signal.title(),
                    "source", signal.source(),
                    "sourceUrl", signal.sourceUrl()
            ));
        }
        return points;
    }

    private List<Map<String, Object>> gisRoutes(List<Map<String, Object>> points) {
        if (points.size() < 2) {
            return List.of();
        }
        List<Map<String, Object>> routes = new ArrayList<>();
        for (int i = 0; i < points.size(); i++) {
            Map<String, Object> from = points.get(i);
            Map<String, Object> to = points.get((i + 1) % points.size());
            int risk = Math.max(asInt(from.get("riskIndex")), asInt(to.get("riskIndex")));
            routes.add(Map.of(
                    "id", "GR-" + i,
                    "name", from.get("name") + " -> " + to.get("name"),
                    "fromLon", from.get("lon"),
                    "fromLat", from.get("lat"),
                    "toLon", to.get("lon"),
                    "toLat", to.get("lat"),
                    "riskIndex", risk,
                    "sourceUrl", from.getOrDefault("sourceUrl", "")
            ));
        }
        return routes;
    }

    private int asInt(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (Exception ignored) {
            return 0;
        }
    }

    private String riskLevel(int score) {
        if (score >= 80) {
            return "高危";
        }
        if (score >= 60) {
            return "中危";
        }
        if (score > 0) {
            return "低危";
        }
        return "待采集";
    }

    private boolean signalMatches(CrawlerSignal signal, String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return true;
        }
        String normalized = keyword.toLowerCase(Locale.ROOT);
        return signal.title().toLowerCase(Locale.ROOT).contains(normalized)
                || signal.source().toLowerCase(Locale.ROOT).contains(normalized)
                || signal.dimension().toLowerCase(Locale.ROOT).contains(normalized);
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

    private void seedDefaultAiModel() {
        if (defaultAiApiKey != null && !defaultAiApiKey.isBlank()) {
            aiModelConfigs.put(defaultAiModel, new AiModelConfig(defaultAiModel, defaultAiEndpoint, mask(defaultAiApiKey), true, Instant.now()));
        }
    }

    private String mask(String key) {
        if (key == null || key.length() < 8) {
            return "未配置";
        }
        return key.substring(0, 4) + "****" + key.substring(key.length() - 4);
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

    public record RiskAlert(String id, Instant time, String level, String title, String source, String sourceUrl, String status, String target) {
        public RiskAlert(String id, Instant time, String level, String title, String source, String status, String target) {
            this(id, time, level, title, source, "", status, target);
        }
    }

    public record UploadTask(String id, String filename, long size, String status, Instant createdAt, int rows, List<String> warnings) {
    }

    public record ReportJob(String id, String template, String language, String format, int threshold, String status, int progress, String step, String downloadUrl, Instant createdAt) {
    }

    public record SystemUser(String id, String username, String email, String role, String status) {
    }

    public record CrawlerSignal(String id, String source, String title, String dimension, int riskScore, Instant fetchedAt, String sourceUrl, String status) {
    }

    public record DailyRiskSnapshot(int score, String level, String summary, List<CrawlerSignal> signals, Instant calculatedAt) {
    }

    public record AiModelConfig(String model, String endpoint, String maskedApiKey, boolean configured, Instant updatedAt) {
    }
}
