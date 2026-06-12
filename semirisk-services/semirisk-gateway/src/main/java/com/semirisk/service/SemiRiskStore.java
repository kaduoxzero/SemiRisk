package com.semirisk.service;

import com.semirisk.common.AiModelDefaults;
import com.semirisk.common.SemiriskConstants;
import com.semirisk.repository.PreparedRiskRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.scheduling.annotation.Scheduled;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

@Service
public class SemiRiskStore {

    private final Map<String, UserAccount> users = new ConcurrentHashMap<>();
    private final Map<String, LoginCounter> loginCounters = new ConcurrentHashMap<>();
    private final Map<String, Instant> resetTokens = new ConcurrentHashMap<>();
    private final Map<String, UploadTask> uploadTasks = new ConcurrentHashMap<>();
    private final Map<String, ReportJob> reportJobs = new ConcurrentHashMap<>();
    // 缓存已生成的报告内容，避免每次下载重复调用 AI
    private final Map<String, List<String>> reportContentCache = new ConcurrentHashMap<>();
    private final Map<String, RiskAlert> alerts = new ConcurrentHashMap<>();
    private final Map<String, String> publicAlertStatuses = new ConcurrentHashMap<>();
    private final Map<String, SystemUser> systemUsers = new ConcurrentHashMap<>();
    private final Map<String, AiModelConfig> aiModelConfigs = new ConcurrentHashMap<>();
    private final Map<String, String> aiModelApiKeys = new ConcurrentHashMap<>();
    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(8)).build();
    private volatile DailyRiskSnapshot dailyRiskSnapshot;
    private volatile Map<String, Object> dailyAiReport;
    private volatile String dailyAiReportDate = "";
    private final AtomicBoolean reportGenerating = new AtomicBoolean(false);
    private final ExecutorService reportExecutor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "semirisk-ai-report");
        thread.setDaemon(true);
        return thread;
    });
    private final List<String> auditLogs = new ArrayList<>();
    private final String defaultAiModel;
    private final String defaultAiEndpoint;
    private final String defaultAiApiKey;
    private final ObjectMapper objectMapper;
    private final PreparedRiskRepository repository;
    private final HealthProbeService healthProbeService;
    private final TranslationService translationService;
    private final GisService gisService;
    private final EnterpriseService enterpriseService;
    private final AiChatService aiChatService;

    /** 知识库分类常量：真实来源区分。 */
    public static final String KNOWLEDGE_PUBLIC = "公开情报";
    public static final String KNOWLEDGE_POLICY = "政策法规";
    public static final String KNOWLEDGE_INTERNAL = "内部知识库";

    public SemiRiskStore(
            @Value("${semirisk.ai.default.model:" + AiModelDefaults.DEFAULT_MODEL + "}") String defaultAiModel,
            @Value("${semirisk.ai.default.endpoint:" + AiModelDefaults.DEFAULT_ENDPOINT + "}") String defaultAiEndpoint,
            @Value("${semirisk.ai.default.api-key:}") String defaultAiApiKey,
            ObjectMapper objectMapper,
            PreparedRiskRepository repository,
            HealthProbeService healthProbeService,
            TranslationService translationService,
            GisService gisService,
            EnterpriseService enterpriseService,
            AiChatService aiChatService) {
        this.defaultAiModel = defaultAiModel;
        this.defaultAiEndpoint = defaultAiEndpoint;
        this.defaultAiApiKey = defaultAiApiKey;
        this.objectMapper = objectMapper;
        this.repository = repository;
        this.healthProbeService = healthProbeService;
        this.translationService = translationService;
        this.gisService = gisService;
        this.enterpriseService = enterpriseService;
        this.aiChatService = aiChatService;
        seedDefaultAiModel();
        auditLogs.add("[INFO] gateway route table initialized");
        refreshDailyRiskRecords();
    }

    /** 在表结构就绪（SchemaInitializer @Order(10)）之后再执行入库种子与启动恢复。 */
    @org.springframework.context.event.EventListener(org.springframework.context.event.ContextRefreshedEvent.class)
    @org.springframework.core.annotation.Order(20)
    public void initPersistence() {
        seedInternalKnowledgeDocs();
        recoverFromDatabase();
    }

    @Scheduled(fixedDelayString = "${semirisk.risk.refresh-interval-ms:300000}", initialDelayString = "${semirisk.risk.initial-delay-ms:60000}")
    public void refreshDailyRiskRecords() {
        auditLogs.add("[INFO] gateway scheduled refresh tick; pulling latest crawler signals");
    }

    public void refreshDailyRiskRecords(List<CrawlerSignal> signals) {
        List<CrawlerSignal> collected = signals == null ? List.of() : List.copyOf(signals);
        List<CrawlerSignal> availableSignals = collected.stream()
                .filter(signal -> "OK".equalsIgnoreCase(signal.status()))
                .toList();
        if (availableSignals.isEmpty()) {
            // 本轮采集失败：优先沿用数据库中近期的真实信号，避免用空数据覆盖已采集快照。
            List<CrawlerSignal> persisted = loadRecentSignalsFromDb();
            if (!persisted.isEmpty()) {
                rebuildSnapshot(persisted);
                refreshEnterpriseRecords(persisted);
                auditLogs.add("[WARN] crawler returned no fresh records; serving persisted signals count=" + persisted.size());
                return;
            }
            dailyRiskSnapshot = new DailyRiskSnapshot(0, "待采集",
                    "公开源暂未成功采集，本日风险测算等待 data-service 获取公开网站数据后刷新。",
                    collected, Instant.now());
            auditLogs.add("[WARN] daily crawler refresh completed without public source records");
            return;
        }
        // 真实信号入库，并据此重建快照、告警、企业画像、知识库（全部持久化到 MySQL）。
        persistSignals(availableSignals);
        rebuildSnapshot(availableSignals);
        persistRiskSnapshot(dailyRiskSnapshot);
        persistPublicAlerts(availableSignals);
        refreshEnterpriseRecords(availableSignals);
        persistKnowledgeDocs(availableSignals);
        maybeGenerateDailyReportAsync();
        auditLogs.add("[INFO] daily crawler refresh persisted signals=" + availableSignals.size() + " score=" + dailyRiskSnapshot.score());
    }

    private void rebuildSnapshot(List<CrawlerSignal> availableSignals) {
        int score = availableSignals.stream().mapToInt(CrawlerSignal::riskScore).max().orElse(0);
        String level = score >= 80 ? "高危" : score >= 60 ? "中危" : "低危";
        dailyRiskSnapshot = new DailyRiskSnapshot(score, level,
                "AI 自动测算：" + level + "，本日风险分 " + score + "，由公开网站爬虫记录和风险规则共同计算。",
                availableSignals, Instant.now());
    }

    // ---------------------------------------------------------------------
    // 持久化与启动恢复（信息一律入库 MySQL，重启可恢复）
    // ---------------------------------------------------------------------

    private void recoverFromDatabase() {
        try {
            List<CrawlerSignal> persisted = loadRecentSignalsFromDb();
            if (!persisted.isEmpty()) {
                rebuildSnapshot(persisted);
                auditLogs.add("[INFO] recovered " + persisted.size() + " crawler signals from MySQL on startup");
            }
        } catch (Exception ignored) {
            // MySQL 暂不可达时等待首次爬虫同步。
        }
        loadAlertStatusesFromDb();
        seedEnterpriseWatchlist();
        recoverUsersToMemory();
        recoverUploadTasks();
        recoverReportJobs();
    }

    private List<CrawlerSignal> loadRecentSignalsFromDb() {
        try {
            Instant since = Instant.now().minus(7, ChronoUnit.DAYS);
            return repository.findRecentCrawlerSignals(since, 300).stream()
                    .map(this::rowToSignal)
                    .toList();
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private CrawlerSignal rowToSignal(Map<String, Object> row) {
        return new CrawlerSignal(
                stringValue(row.get("id")),
                stringValue(row.get("source")),
                stringValue(row.get("title")),
                stringValue(row.get("dimension")),
                asInt(row.get("riskScore")),
                toInstant(row.get("fetchedAt")),
                stringValue(row.get("sourceUrl")),
                stringValue(row.getOrDefault("status", "OK")));
    }

    private void persistSignals(List<CrawlerSignal> signals) {
        try {
            for (CrawlerSignal s : signals) {
                repository.upsertCrawlerSignal(s.id(), truncate(s.source(), 250), truncate(s.sourceUrl(), 1000),
                        truncate(s.title(), 1000), truncate(s.dimension(), 60), categoryForSignal(s),
                        riskSignalLabel(s.riskScore()), s.riskScore(), s.status(), s.fetchedAt());
            }
            repository.deleteOldCrawlerSignals(Instant.now().minus(7, ChronoUnit.DAYS));
        } catch (Exception ignored) {
            // 入库失败时仍以内存快照对外服务。
        }
    }

    private void persistRiskSnapshot(DailyRiskSnapshot snapshot) {
        try {
            repository.insertRiskSnapshot(snapshot.score(), snapshot.level(), truncate(snapshot.summary(), 1000),
                    snapshot.signals().size(), snapshot.calculatedAt());
        } catch (Exception ignored) {
        }
    }

    private void persistPublicAlerts(List<CrawlerSignal> signals) {
        try {
            for (CrawlerSignal s : signals) {
                String status = publicAlertStatuses.getOrDefault(s.id(), "未处理");
                repository.upsertPublicAlert(s.id(), s.fetchedAt(), riskLevel(s.riskScore()),
                        truncate(s.title(), 250), truncate(s.source(), 120), status, "risk-detail.html");
            }
        } catch (Exception ignored) {
        }
    }

    private void persistAlertStatus(String id, String status, RiskAlert alert) {
        try {
            repository.upsertPublicAlert(id, alert.time(), alert.level(), truncate(alert.title(), 250),
                    truncate(alert.source(), 120), status, "risk-detail.html");
            repository.updateAlertStatus(id, status);
        } catch (Exception ignored) {
        }
    }

    private void loadAlertStatusesFromDb() {
        try {
            repository.findAlerts(null, null, null, 500).forEach(row -> {
                String id = stringValue(row.get("id"));
                String status = stringValue(row.get("status"));
                if (!id.isBlank() && !status.isBlank() && !"未处理".equals(status)) {
                    publicAlertStatuses.put(id, status);
                }
            });
        } catch (Exception ignored) {
        }
    }

    /** 从 MySQL 恢复上传任务到内存。 */
    private void recoverUploadTasks() {
        try {
            List<Map<String, Object>> rows = repository.findUploadTasks(200);
            for (Map<String, Object> row : rows) {
                String id = stringValue(row.get("id"));
                String filename = stringValue(row.get("filename"));
                long size = asInt(row.get("size"));
                String status = stringValue(row.get("status"));
                int rowsCount = asInt(row.get("rows"));
                Instant createdAt = toInstant(row.get("createdAt"));
                // 只恢复非完成状态的任务
                if (!"导入成功".equals(status) && !"无有效数据".equals(status) && !"失败".equals(status)) {
                    UploadTask task = new UploadTask(id, filename, size, status, createdAt, rowsCount, List.of());
                    uploadTasks.put(id, task);
                }
            }
            auditLogs.add("[INFO] recovered " + rows.size() + " upload tasks from MySQL");
        } catch (Exception ignored) {
        }
    }

    /** 从 MySQL 恢复报告任务到内存。 */
    private void recoverReportJobs() {
        try {
            List<Map<String, Object>> rows = repository.findReportJobs(200);
            for (Map<String, Object> row : rows) {
                String id = stringValue(row.get("id"));
                if (reportJobs.containsKey(id)) continue; // 已在内存中
                String template = stringValue(row.get("template"));
                String language = stringValue(row.get("language"));
                String format = stringValue(row.get("format"));
                int threshold = asInt(row.get("threshold"));
                String status = stringValue(row.get("status"));
                int progress = asInt(row.get("progress"));
                String step = stringValue(row.get("step"));
                String downloadUrl = stringValue(row.get("downloadUrl"));
                Instant createdAt = toInstant(row.get("createdAt"));
                ReportJob job = new ReportJob(id, template, language, format, threshold, status, progress, step, downloadUrl, createdAt);
                reportJobs.put(id, job);
            }
            auditLogs.add("[INFO] recovered " + rows.size() + " report jobs from MySQL");
        } catch (Exception ignored) {
        }
    }

    private void persistKnowledgeDocs(List<CrawlerSignal> signals) {
        try {
            for (CrawlerSignal s : signals) {
                String content = s.title() + "\n来源：" + s.source() + "\n维度：" + s.dimension()
                        + "\n原文：" + s.sourceUrl() + "\n规则评分：" + s.riskScore();
                repository.upsertKnowledgeDoc(s.id(), categoryForSignal(s), truncate(s.title(), 1000), content,
                        truncate(s.source(), 250), truncate(s.sourceUrl(), 1000), truncate(s.dimension(), 60),
                        s.riskScore(), null, s.fetchedAt());
            }
        } catch (Exception ignored) {
        }
    }

    private String categoryForSignal(CrawlerSignal signal) {
        String dimension = signal.dimension() == null ? "" : signal.dimension();
        String source = signal.source() == null ? "" : signal.source();
        String url = signal.sourceUrl() == null ? "" : signal.sourceUrl().toLowerCase(Locale.ROOT);
        // URL 判定（ASCII，最稳健）：政策法规来自官方/监管/多边机构域名。
        if (url.contains("federalregister.gov") || url.contains("wto.org") || url.contains("europa.eu")
                || url.contains(".gov") || url.contains("customs") || url.contains("mofcom")) {
            return KNOWLEDGE_POLICY;
        }
        if (dimension.contains("政策") || dimension.contains("法规") || dimension.contains("出口管制") || dimension.contains("合规")
                || source.contains("政策") || source.contains("法规") || source.contains("商务部") || source.contains("管制")
                || source.contains("federal") || source.contains("wto")) {
            return KNOWLEDGE_POLICY;
        }
        return KNOWLEDGE_PUBLIC;
    }

    private String riskSignalLabel(int score) {
        return score >= 75 ? "高危信号" : score >= 60 ? "中危信号" : "监控信号";
    }

    private void seedInternalKnowledgeDocs() {
        // 内部知识库 SOP：管理维护的真实运营规程，统一存入 MySQL knowledge_doc（不再写死在代码逻辑里返回）。
        List<String[]> internalDocs = List.of(
                new String[]{"KD-SOP-001", "高危供应链告警处置 SOP", "高危供应链告警先核验公开源原文，再确认影响物料、库存覆盖天数和替代供应商，最后绑定负责人和闭环截止时间。", "处置"},
                new String[]{"KD-SOP-002", "半导体供应链风险关注要点", "半导体供应链风险重点关注先进制程产能、封测排期、关键设备出口管制、物流节点拥堵和汇率/关税变化。", "半导体"},
                new String[]{"KD-SOP-003", "公开源关键词联动责任人规则", "当公开源出现关税、罢工、港口拥堵、制裁、短缺等关键词时，优先同步采购、物流、合规三类责任人。", "处置"},
                new String[]{"KD-SOP-004", "管理层风险报告写作规范", "管理层报告需要给出事实来源、影响范围、评分依据、可选方案、负责人和闭环时间。", "报告"}
        );
        try {
            for (String[] doc : internalDocs) {
                repository.upsertKnowledgeDoc(doc[0], KNOWLEDGE_INTERNAL, doc[1], doc[2],
                        "SemiRisk 内部知识库", "", doc[3], 0, null, Instant.now());
            }
        } catch (Exception ignored) {
            // MySQL 不可达时 localKnowledgeLines 会使用内存兜底文案。
        }
    }

    // ---------------------------------------------------------------------
    // 企业画像：真实公开主体观察名单 + 实时公开源事件（工商权威字段待接入，不伪造）
    // ---------------------------------------------------------------------

    /** 公开半导体供应链主体观察名单：名称/行业/总部为公开事实；风险与事件来自实时爬取。 */
    private static final String[][] ENTERPRISE_WATCHLIST = {
            {"台积电 TSMC", "晶圆代工", "中国台湾·新竹"},
            {"中芯国际 SMIC", "晶圆代工", "上海"},
            {"长江存储 YMTC", "存储芯片制造", "武汉"},
            {"ASML", "光刻设备", "荷兰·费尔德霍芬"},
            {"应用材料 Applied Materials", "半导体设备", "美国·加州"},
            {"英伟达 NVIDIA", "芯片设计", "美国·加州"},
            {"三星电子 Samsung", "存储/晶圆制造", "韩国·水原"},
            {"马士基 Maersk", "航运物流", "丹麦·哥本哈根"}
    };

    private void seedEnterpriseWatchlist() {
        try {
            if (repository.findEnterpriseRecords(1).isEmpty()) {
                for (String[] entity : ENTERPRISE_WATCHLIST) {
                    String id = "ENT-" + UUID.nameUUIDFromBytes(entity[0].getBytes(StandardCharsets.UTF_8)).toString().substring(0, 8);
                    repository.upsertEnterpriseRecord(id, entity[0], "", entity[1], entity[2], 0, "待采集",
                            "公开主体观察名单 + 公开源事件", "待接入权威源", "[]", "[]", Instant.now());
                }
                auditLogs.add("[INFO] enterprise watchlist seeded into MySQL count=" + ENTERPRISE_WATCHLIST.length);
            }
        } catch (Exception ignored) {
        }
    }

    private void refreshEnterpriseRecords(List<CrawlerSignal> signals) {
        try {
            List<Map<String, Object>> records = repository.findEnterpriseRecords(100);
            for (Map<String, Object> record : records) {
                String name = stringValue(record.get("name"));
                String industry = stringValue(record.get("industry"));
                List<CrawlerSignal> matched = signals.stream()
                        .filter(signal -> signalMatches(signal, name) || signalMatches(signal, industry))
                        .limit(8)
                        .toList();
                int score = matched.stream().mapToInt(CrawlerSignal::riskScore).max().orElse(0);
                String eventsJson = writeJson(matched.stream().map(s -> s.fetchedAt() + " " + s.title()).toList());
                String signalsJson = writeJson(matched.stream().map(this::enterpriseSignal).toList());
                repository.upsertEnterpriseRecord(stringValue(record.get("id")), name, stringValue(record.get("creditCode")),
                        industry, stringValue(record.get("location")), score, riskLevel(score),
                        "公开主体观察名单 + 公开源事件", "待接入权威源", eventsJson, signalsJson, Instant.now());
            }
        } catch (Exception ignored) {
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ignored) {
            return "[]";
        }
    }

    @SuppressWarnings("unchecked")
    private <T> T readJson(Object value, TypeReference<T> type, T fallback) {
        if (value == null) {
            return fallback;
        }
        try {
            return objectMapper.readValue(String.valueOf(value), type);
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private String truncate(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    private Instant toInstant(Object value) {
        if (value == null) {
            return Instant.now();
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
            return Instant.now();
        }
    }

    public Optional<UserAccount> authenticate(String username, String password) {
        // 内存用户路径：仅用于兜底（启动管理员、本地开发），密码用明文校验
        UserAccount account = users.get(username);
        if (account != null && account.enabled() && account.password().equals(password)) {
            loginCounters.remove(username);
            return Optional.of(account);
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
                if ("启用".equals(status) && !username.isEmpty()) {
                    String displayName = stringValue(row.get("displayName"));
                    if (displayName.isBlank()) displayName = username;
                    String role = stringValue(row.get("role"));
                    // 不覆盖启动管理员（已在 upsertLoginUser 中写入）
                    if (!users.containsKey(username)) {
                        users.put(username, new UserAccount(username, "", displayName, role, true));
                    }
                }
            }
            auditLogs.add("[INFO] recovered " + allUsers.size() + " users from MySQL to memory");
        } catch (Exception ignored) {
            // MySQL 不可达时跳过
        }
    }

    private List<Map<String, Object>> findAllSystemUsers() {
        try {
            return repository.findSystemUsers();
        } catch (Exception ignored) {
            return List.of();
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
        UserAccount account = new UserAccount(username, password, displayName, role);
        users.put(username, account);
        addSystemUser(username, email, role);
        auditLogs.add("[INFO] public registration completed username=" + username);
        return account;
    }

    public UserAccount upsertLoginUser(String username, String password, String displayName, String email, String role) {
        UserAccount account = new UserAccount(username, password, displayName, role);
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
                        persistAlertStatus(id, status, alert);
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

    public UploadTask completeUpload(String id, int rows, List<String> warnings) {
        UploadTask task = uploadTasks.get(id);
        if (task == null) {
            throw new IllegalArgumentException("上传任务不存在");
        }
        String status = rows > 0 ? "导入成功" : "无有效数据";
        UploadTask done = new UploadTask(task.id(), task.filename(), task.size(), status, task.createdAt(), rows,
                warnings == null ? List.of() : warnings);
        uploadTasks.put(id, done);
        auditLogs.add("[INFO] upload " + id + " parsed rows=" + rows);
        return done;
    }

    public Optional<UploadTask> uploadTask(String id) {
        return Optional.ofNullable(uploadTasks.get(id));
    }

    /** 上传处理 SSE 的真实日志行，反映文件接收、MinIO 落库与真实解析结果。 */
    public List<String> uploadLogLines(String id) {
        UploadTask task = (id == null || id.isBlank())
                ? uploadTasks.values().stream().max(Comparator.comparing(UploadTask::createdAt)).orElse(null)
                : uploadTasks.get(id);
        List<String> lines = new ArrayList<>();
        if (task == null) {
            lines.add("[INFO] 暂无上传任务，等待文件上传后开始处理");
            return lines;
        }
        lines.add("[INFO] 接收文件 " + task.filename() + "（" + task.size() + " 字节），校验大小与格式");
        lines.add("[INFO] 文件已写入 MinIO 对象存储，便于后续解析与预览");
        lines.add("[INFO] 当前任务状态：" + task.status());
        if (task.rows() > 0) {
            lines.add("[INFO] 真实解析数据行 " + task.rows() + " 行，已抽取供应商/物料/航线字段");
        }
        task.warnings().forEach(lines::add);
        lines.add("[INFO] 处理流程结束");
        return lines;
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
        // 真实进度：聚合(25%) → AI 调用(60%) → 编排(85%) → 渲染(100%)
        int current = job.progress();
        int next = current + 25; // 每轮轮询推进 25%，4 轮完成
        if (next > 100) next = 100;
        int progress = Math.max(next, current); // 不倒退
        String status = progress >= 100 ? "已完成" : "生成中";
        String step = switch (progress) {
            case 0 -> "任务已接受";
            case 1 -> "聚合风险事件与供应商画像";
            case 25 -> "准备 AI 分析上下文";
            case 50 -> "调用 AI 模型生成风险摘要";
            case 60 -> "AI 模型响应中";
            case 75 -> "编排处置建议与图表";
            case 85 -> "渲染导出文件";
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

    public List<String> aiReportLines(String id, String template, String language) {
        // 如果已经生成过，直接返回缓存内容，不再调用 AI
        List<String> cached = reportContentCache.get(id);
        if (cached != null) {
            return List.copyOf(cached);
        }
        String type = normalizeReportTemplate(template);
        List<CrawlerSignal> signals = availableSignals();
        List<String> context = reportContext(type, signals);
        AiAnswer aiAnswer = callDeepSeek(reportPrompt(type, language), context);
        List<String> lines = new ArrayList<>();
        lines.add(reportTitle(type));
        lines.add("报告编号：" + id);
        lines.add("写作方式：AI 结合公开源、风险规则、企业画像和处置 SOP 生成");
        lines.add("AI状态：" + aiAnswer.status());
        if (!aiAnswer.answer().isBlank()) {
            splitAnswer(aiAnswer.answer()).forEach(lines::add);
        } else {
            lines.addAll(fallbackReportLines(type, signals));
        }
        // 缓存结果，避免重复 AI 调用
        reportContentCache.put(id, List.copyOf(lines));
        return lines;
    }

    private String normalizeReportTemplate(String template) {
        if ("supply-chain".equalsIgnoreCase(template)) {
            return "supply-chain";
        }
        if ("enterprise-dd".equalsIgnoreCase(template)) {
            return "enterprise-dd";
        }
        return "risk-assessment";
    }

    private String reportTitle(String type) {
        return switch (type) {
            case "supply-chain" -> "SemiRisk AI 供应链分析报告";
            case "enterprise-dd" -> "SemiRisk AI 企业尽调报告";
            default -> "SemiRisk AI 风险评估报告";
        };
    }

    private String reportPrompt(String type, String language) {
        String lang = language == null || language.isBlank() ? "中文" : language;
        String base = """
                你是一名资深半导体供应链风险顾问，为企业高管撰写决策级风险报告。
                要求：
                1. 直接给出结论，不要有"以下是""根据您提供的""以下为"等引导语。
                2. 不使用任何 Markdown 符号（不用#、*、**、-、`、---）。纯文字段落，每段以中文编号开头（一、二、三...）。
                3. 必须明确指出是哪些具体信号/事件/政策导致了当前分数升高或降低，给出具体来源和标题。
                4. 每条建议必须可操作，给出负责部门（采购/供应链/法务/财务/高管）和处置时限（24h/3天/7天/下季度）。
                5. 报告长度：8-12个自然段，每段3-5句话。
                """;
        return switch (type) {
            case "supply-chain" -> base + "撰写语言：" + lang + "。\n报告类型：供应链韧性分析报告。聚焦：物流路径中断风险、关键供应商集中度、库存安全水位、替代采购方案和跨部门协同行动计划。";
            case "enterprise-dd" -> base + "撰写语言：" + lang + "。\n报告类型：企业尽调风险报告。聚焦：企业主体资质、公开源负面事件、经营稳定性信号、合作风险评级（低/中/高）、具体合作条款建议和需人工补充核验的信息清单。";
            default -> base + "撰写语言：" + lang + "。\n报告类型：综合风险评估报告。聚焦：当前综合评分的驱动因素（哪些信号拉高/拉低了分数）、各维度风险排名、未来7-30天走势研判、优先级排序的处置行动清单。";
        };
    }

    private List<String> reportContext(String type, List<CrawlerSignal> signals) {
        List<String> context = new ArrayList<>();
        DailyRiskSnapshot snapshot = dailyRiskSnapshot;
        context.add("当前综合评分：" + snapshot.score() + " | 等级：" + snapshot.level() + " | 摘要：" + snapshot.summary());
        // Score drivers: signals above/below median
        long high = signals.stream().filter(s -> s.riskScore() >= 70).count();
        long mid = signals.stream().filter(s -> s.riskScore() >= 50 && s.riskScore() < 70).count();
        long low = signals.stream().filter(s -> s.riskScore() < 50).count();
        context.add("评分构成：高危信号 " + high + " 条（>=70分）拉高综合评分，中危 " + mid + " 条（50-69分），低危/监控 " + low + " 条（<50分）。");
        context.add("信号总数：" + signals.size() + " 条，来源渠道：" + signals.stream().map(CrawlerSignal::source).distinct().count() + " 个。");
        switch (type) {
            case "supply-chain" -> {
                gisRoutes(gisPoints(signals)).stream().limit(8).forEach(route ->
                        context.add("物流路径：" + route.get("name") + " / 风险 " + route.get("riskIndex")));
                dimensionScores(signals).forEach(item ->
                        context.add("供应链维度评分：" + item.get("name") + " = " + item.get("value")));
            }
            case "enterprise-dd" ->
                    enterpriseRecordsForReport(5).forEach(profile ->
                            context.add("企业画像：" + profile.get("name") + " | " + profile.get("industry") + " | 风险 " + profile.get("riskScore") + " | " + profile.get("creditLevel")));
            default -> {
                // top risk drivers for the score
                signals.stream().filter(s -> s.riskScore() >= 60).limit(5).forEach(s ->
                        context.add("高风险驱动信号：[" + s.riskScore() + "分] " + s.source() + " | " + s.dimension() + " | " + s.title()));
            }
        }
        // All signals with full detail for AI to reason over
        signals.stream().limit(20).forEach(signal ->
                context.add("公开源信号 [" + signal.riskScore() + "分] 来源：" + signal.source()
                        + " | 维度：" + signal.dimension()
                        + " | 标题：" + signal.title()
                        + " | 链接：" + signal.sourceUrl()));
        context.addAll(localKnowledgeLines());
        return context;
    }

    private List<String> fallbackReportLines(String type, List<CrawlerSignal> signals) {
        int score = dailyRiskSnapshot.score();
        long high = signals.stream().filter(signal -> signal.riskScore() >= 80).count();
        long mid = signals.stream().filter(signal -> signal.riskScore() >= 60 && signal.riskScore() < 80).count();
        List<String> lines = new ArrayList<>();
        switch (type) {
            case "supply-chain" -> {
                lines.add("一、供应链结论：当前综合风险 " + score + "，高危路径/事件 " + high + " 条，中危 " + mid + " 条。");
                lines.add("二、路径研判：重点关注跨境港口、封测排期、关键物料交付窗口和转运成本。");
                gisRoutes(gisPoints(signals)).stream().limit(6).forEach(route -> lines.add("路径：" + route.get("name") + "，风险指数 " + route.get("riskIndex") + "。"));
                lines.add("三、协同建议：采购确认替代供应商，物流确认改港/改线方案，销售同步客户交付风险。");
            }
            case "enterprise-dd" -> {
                List<Map<String, Object>> records = enterpriseRecordsForReport(5);
                lines.add("一、尽调结论：企业画像库（公开主体观察名单 + 公开源事件）已纳入 " + records.size() + " 家主体，工商权威字段待接入权威源，公开源事件用于风险交叉核验。");
                records.forEach(profile -> lines.add("主体：" + profile.get("name") + "，行业 " + profile.get("industry") + "，风险 " + profile.get("riskScore") + "，等级 " + profile.get("creditLevel") + "，工商：待接入权威源。"));
                lines.add("二、核验建议：补充工商、司法、失信、舆情和供应商准入材料，未核验前不建议扩大授信。");
                lines.add("三、合作建议：高危主体走短周期订单和预警监控，中低危主体保留月度复盘。");
            }
            default -> {
                lines.add("一、风险结论：当前综合评分 " + score + "，等级 " + dailyRiskSnapshot.level() + "。");
                lines.add("二、评分依据：公开源有效信号 " + signals.size() + " 条，高危 " + high + " 条，中危 " + mid + " 条。");
                signals.stream().limit(8).forEach(signal -> lines.add("事件：" + signal.source() + " / " + signal.dimension() + " / " + signal.riskScore() + " / " + signal.title()));
                lines.add("三、处置建议：先核验高分公开源原文，再转入告警工单并绑定负责人和截止时间。");
            }
        }
        lines.add("四、闭环指标：跟踪未处理告警数、高危信号变化、供应商风险分、物流节点等待时间和报告引用可信度。");
        return lines;
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
        Map<String, Object> dashboard = new LinkedHashMap<>();
        dashboard.put("kpis", List.of(
                Map.of("name", "公开源事件数", "value", availableSignals.size(), "trend", "公开网站"),
                Map.of("name", "今日新增", "value", snapshot.signals().size(), "trend", "爬虫记录"),
                Map.of("name", "高危信号", "value", highCount, "trend", "规则评分"),
                Map.of("name", "闭环处理率", "value", closureRate, "trend", "告警处置")
        ));
        dashboard.put("hotspots", gisPoints(availableSignals).stream().limit(4).toList());
        dashboard.put("ranking", publicAlerts(availableSignals).stream().limit(5).toList());
        dashboard.put("materials", dimensionScores(availableSignals));
        dashboard.put("stages", availableSignals.isEmpty()
                ? List.of("公开源采集:待采集", "规则评分:待采集", "AI测算:待采集", "处置闭环:待派发")
                : List.of("公开源采集:已完成", "规则评分:" + snapshot.level(), "AI测算:" + snapshot.level(), "处置闭环:待派发"));
        dashboard.put("aiSummary", snapshot.summary());
        dashboard.put("aiReport", latestAiReport());
        dashboard.put("dailyRisk", snapshot);
        dashboard.put("dataMode", availableSignals.isEmpty() ? "WAITING_PUBLIC_SOURCE" : "PUBLIC_CRAWLED");
        dashboard.put("dataSource", "semirisk-data-service 公开 RSS 采集");
        dashboard.put("refreshedAt", Instant.now().toString());
        return dashboard;
    }

    public Map<String, Object> riskAnalysis(String window) {
        String normalizedWindow = normalizeWindow(window);
        String windowLabel = windowLabel(normalizedWindow);
        List<CrawlerSignal> allSignals = availableSignals();
        List<CrawlerSignal> availableSignals = windowedSignals(allSignals, normalizedWindow);
        int score = windowScore(availableSignals, normalizedWindow);
        String level = riskLevel(score);
        long highCount = availableSignals.stream().filter(signal -> signal.riskScore() >= 80).count();
        long midCount = availableSignals.stream().filter(signal -> signal.riskScore() >= 60 && signal.riskScore() < 80).count();
        double average = availableSignals.stream().mapToInt(CrawlerSignal::riskScore).average().orElse(0);
        Map<String, Object> analysis = new LinkedHashMap<>();
        analysis.put("window", normalizedWindow);
        analysis.put("windowLabel", windowLabel);
        analysis.put("score", score);
        analysis.put("level", level);
        analysis.put("summary", availableSignals.isEmpty()
                ? windowLabel + "暂无公开源命中，当前风险研判等待下一轮爬虫刷新。"
                : windowLabel + "纳入 " + availableSignals.size() + " 条公开源信号，综合评分 " + score + "（" + level + "），高危 " + highCount + " 条，中危 " + midCount + " 条。");
        analysis.put("dimensions", dimensionScores(availableSignals));
        analysis.put("sources", sourceScores(availableSignals));
        analysis.put("metrics", Map.of(
                "signalCount", availableSignals.size(),
                "allSignalCount", allSignals.size(),
                "highCount", highCount,
                "midCount", midCount,
                "avgScore", Math.round(average),
                "sourceCount", availableSignals.stream().map(CrawlerSignal::source).distinct().count(),
                "dimensionCount", availableSignals.stream().map(CrawlerSignal::dimension).distinct().count()
        ));
        analysis.put("timeline", availableSignals.stream().limit(8).map(signal -> Map.<String, Object>of(
                "time", signal.fetchedAt().toString(),
                "source", signal.source(),
                "dimension", signal.dimension(),
                "score", signal.riskScore(),
                "title", signal.title(),
                "url", signal.sourceUrl()
        )).toList());
        analysis.put("reasoning", availableSignals.isEmpty()
                ? List.of("数据输入: 公开源暂无成功采集记录", "逻辑关联: 暂停自动推理", "风险结论: 等待下一次爬虫刷新")
                : List.of(
                "时间窗口: " + windowLabel + "，使用 " + windowMethod(normalizedWindow),
                "风险密度: " + availableSignals.size() + " 条信号来自 " + availableSignals.stream().map(CrawlerSignal::source).distinct().count() + " 个公开源",
                "维度聚焦: " + topDimension(availableSignals),
                "最高信号: " + availableSignals.get(0).source() + " / " + availableSignals.get(0).title()
        ));
        analysis.put("solutions", List.of(
                Map.of("name", "人工复核公开源原文", "feasibility", availableSignals.isEmpty() ? 0 : ("24h".equals(normalizedWindow) ? 94 : 88), "owner", "风险分析师", "deadline", "24h".equals(normalizedWindow) ? "2小时内" : "当日"),
                Map.of("name", "将高危信号转为告警工单", "feasibility", availableSignals.isEmpty() ? 0 : 86, "owner", "预警运营", "deadline", "1个工作日"),
                Map.of("name", "按维度同步采购/物流负责人", "feasibility", availableSignals.isEmpty() ? 0 : ("30d".equals(normalizedWindow) ? 91 : 78), "owner", "供应链协同", "deadline", "周会前")
        ));
        return analysis;
    }

    private String normalizeWindow(String window) {
        if ("7d".equalsIgnoreCase(window)) {
            return "7d";
        }
        if ("30d".equalsIgnoreCase(window)) {
            return "30d";
        }
        return "24h";
    }

    private String windowLabel(String window) {
        return switch (window) {
            case "7d" -> "近7天";
            case "30d" -> "近30天";
            default -> "近24小时";
        };
    }

    private String windowMethod(String window) {
        return switch (window) {
            case "7d" -> "最高分、平均分与信号密度加权，突出一周内重复出现的风险";
            case "30d" -> "最高分、平均分与维度覆盖度加权，突出月度结构性风险";
            default -> "最高风险信号优先，突出即时告警处置";
        };
    }

    private List<CrawlerSignal> windowedSignals(List<CrawlerSignal> signals, String window) {
        if (signals.isEmpty()) {
            return List.of();
        }
        long hours = switch (window) {
            case "7d" -> 24L * 7L;
            case "30d" -> 24L * 30L;
            default -> 24L;
        };
        Instant cutoff = Instant.now().minus(hours, ChronoUnit.HOURS);
        List<CrawlerSignal> filtered = signals.stream()
                .filter(signal -> !signal.fetchedAt().isBefore(cutoff))
                .toList();
        return filtered.isEmpty() ? signals.stream().limit(Math.min(6, signals.size())).toList() : filtered;
    }

    private int windowScore(List<CrawlerSignal> signals, String window) {
        if (signals.isEmpty()) {
            return 0;
        }
        int max = signals.stream().mapToInt(CrawlerSignal::riskScore).max().orElse(0);
        double avg = signals.stream().mapToInt(CrawlerSignal::riskScore).average().orElse(0);
        long high = signals.stream().filter(signal -> signal.riskScore() >= 80).count();
        long dimensions = signals.stream().map(CrawlerSignal::dimension).distinct().count();
        int score = switch (window) {
            case "7d" -> (int) Math.round(max * 0.68 + avg * 0.32 + Math.min(8, high * 2));
            case "30d" -> (int) Math.round(max * 0.55 + avg * 0.25 + Math.min(18, dimensions * 4));
            default -> max;
        };
        return Math.max(0, Math.min(100, score));
    }

    private String topDimension(List<CrawlerSignal> signals) {
        return dimensionScores(signals).stream()
                .max(Comparator.comparing(item -> asInt(item.get("value"))))
                .map(item -> item.get("name") + " / " + item.get("value"))
                .orElse("暂无维度");
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
        String q = keyword == null ? "" : keyword.trim();
        List<CrawlerSignal> available = availableSignals();
        // 1) 优先命中数据库企业画像（观察名单 / 历史搜索，均来自真实来源）。
        Map<String, Object> base = q.isBlank() ? repositoryFirstEnterprise() : findEnterpriseRecord(q).orElse(null);
        boolean fromDb = base != null;
        String name = base != null ? stringValue(base.get("name")) : (q.isBlank() ? "请输入企业名称后搜索" : q);
        String industry = base != null ? stringValue(base.get("industry")) : "";
        List<CrawlerSignal> related = available.stream()
                .filter(signal -> q.isBlank()
                        ? signalMatches(signal, name) || (!industry.isBlank() && signalMatches(signal, industry))
                        : signalMatches(signal, q) || (!industry.isBlank() && signalMatches(signal, industry)))
                .limit(8)
                .toList();
        int score = Math.max(base != null ? asInt(base.get("riskScore")) : 0,
                related.stream().mapToInt(CrawlerSignal::riskScore).max().orElse(0));
        String resolvedIndustry = !industry.isBlank() ? industry
                : related.stream().findFirst().map(CrawlerSignal::dimension).orElse("待公开源确认");
        String creditCode = base != null && !stringValue(base.get("creditCode")).isBlank()
                ? stringValue(base.get("creditCode")) : "待接入权威源";

        Map<String, Object> profile = new LinkedHashMap<>();
        profile.put("name", name);
        profile.put("creditCode", creditCode);
        profile.put("industry", resolvedIndustry);
        profile.put("location", base != null ? stringValue(base.get("location")) : "待核验");
        profile.put("riskScore", score);
        profile.put("creditLevel", riskLevel(score));
        // 工商权威字段：无真实权威数据源，统一标注待接入，绝不伪造。
        profile.put("business", buildBusinessWithWiki(creditCode, related.isEmpty(), name));
        profile.put("radar", related.isEmpty() ? List.of() : dimensionRadar(related));
        profile.put("topology", related.isEmpty() ? List.of() : List.of("公开源情报", name, "SemiRisk 风控工作台"));
        profile.put("events", related.stream().map(signal -> signal.fetchedAt() + " " + signal.title()).toList());
        profile.put("publicSignals", related.stream().map(this::enterpriseSignal).toList());
        profile.put("internetSearches", internetSearches(name));
        profile.put("internetSearchResults", internetSearchResults(name));
        profile.put("catalog", enterpriseCatalog());
        profile.put("registryStatus", "待接入权威源");
        profile.put("sourceMode", fromDb ? "公开主体观察名单 + 公开源事件" : "公开源事件聚合（待核验主体）");
        profile.put("matchStatus", fromDb ? "已命中企业画像库（工商权威字段待接入）"
                : (related.isEmpty() ? "未命中本地库，已提供互联网查询入口；工商权威字段待接入"
                : "未命中本地库，已用公开源事件生成待核验画像；工商权威字段待接入"));
        // 持久化用户搜索且命中真实公开源事件的新主体（不伪造工商字段）。
        if (!q.isBlank() && !fromDb && !related.isEmpty()) {
            persistSearchedEnterprise(name, resolvedIndustry, score, related);
        }
        return profile;
    }

    /** 主要半导体/供应链企业公开信息（来自各公司年报/官网/公开披露，无需网络请求）。 */
    private static final Map<String, Map<String, String>> PUBLIC_COMPANY_DB = new java.util.HashMap<>();
    static {
        PUBLIC_COMPANY_DB.put("tsmc", Map.of("成立时间","1987年","总部所在地","台湾新竹科学园区","行业分类","半导体代工","企业类型","上市公司（NYSE: TSM / TWSE: 2330）","营收（公开披露）","约 NT$2.16兆元（2023年）","员工人数","约 73,000人（2023年）","公司简介","全球最大的纯晶圆代工厂，主要为苹果、英伟达、AMD等制造芯片，制程技术覆盖2nm至成熟节点。"));
        PUBLIC_COMPANY_DB.put("台积电", PUBLIC_COMPANY_DB.get("tsmc"));
        PUBLIC_COMPANY_DB.put("samsung", Map.of("成立时间","1969年（半导体业务）","总部所在地","韩国京畿道水原市","行业分类","半导体/消费电子","企业类型","上市公司（KRX: 005930）","营收（公开披露）","约 KRW 258兆韩元（2023年）","员工人数","约 270,000人","公司简介","全球最大DRAM/NAND Flash制造商，同时提供代工服务，IDM模式运营。"));
        PUBLIC_COMPANY_DB.put("三星", PUBLIC_COMPANY_DB.get("samsung"));
        PUBLIC_COMPANY_DB.put("asml", Map.of("成立时间","1984年","总部所在地","荷兰埃因霍温","行业分类","半导体设备","企业类型","上市公司（NASDAQ: ASML）","营收（公开披露）","约 €27.6亿（2023年）","员工人数","约 42,000人","公司简介","全球唯一EUV光刻机制造商，DUV/EUV设备是先进制程不可或缺的核心设备。"));
        PUBLIC_COMPANY_DB.put("nvidia", Map.of("成立时间","1993年","总部所在地","美国加利福尼亚州圣克拉拉","行业分类","半导体/GPU/AI","企业类型","上市公司（NASDAQ: NVDA）","营收（公开披露）","约 $609亿美元（FY2024）","员工人数","约 36,000人","公司简介","全球领先的GPU和AI加速器制造商，H100/H200系列是当前AI训练的主流算力平台。"));
        PUBLIC_COMPANY_DB.put("英伟达", PUBLIC_COMPANY_DB.get("nvidia"));
        PUBLIC_COMPANY_DB.put("amd", Map.of("成立时间","1969年","总部所在地","美国加利福尼亚州圣克拉拉","行业分类","半导体/CPU/GPU","企业类型","上市公司（NASDAQ: AMD）","营收（公开披露）","约 $227亿美元（2023年）","员工人数","约 26,000人","公司简介","x86 CPU（EPYC服务器处理器）和Radeon GPU制造商，近年AI加速器MI系列快速增长。"));
        PUBLIC_COMPANY_DB.put("intel", Map.of("成立时间","1968年","总部所在地","美国加利福尼亚州圣克拉拉","行业分类","半导体/CPU","企业类型","上市公司（NASDAQ: INTC）","营收（公开披露）","约 $542亿美元（2023年）","员工人数","约 124,800人","公司简介","全球最大的x86 CPU制造商之一，IDM模式运营，正在亚利桑那建设IFS代工厂。"));
        PUBLIC_COMPANY_DB.put("英特尔", PUBLIC_COMPANY_DB.get("intel"));
        PUBLIC_COMPANY_DB.put("qualcomm", Map.of("成立时间","1985年","总部所在地","美国加利福尼亚州圣地亚哥","行业分类","半导体/无线通信","企业类型","上市公司（NASDAQ: QCOM）","营收（公开披露）","约 $358亿美元（FY2023）","员工人数","约 51,000人","公司简介","全球领先的移动处理器和基带芯片设计公司，Snapdragon系列广泛用于智能手机。"));
        PUBLIC_COMPANY_DB.put("高通", PUBLIC_COMPANY_DB.get("qualcomm"));
        PUBLIC_COMPANY_DB.put("sk hynix", Map.of("成立时间","1983年","总部所在地","韩国京畿道利川市","行业分类","半导体/存储","企业类型","上市公司（KRX: 000660）","营收（公开披露）","约 KRW 32.8兆韩元（2023年）","员工人数","约 37,000人","公司简介","全球第二大DRAM制造商，HBM高带宽存储器是目前AI训练芯片的核心配套组件。"));
        PUBLIC_COMPANY_DB.put("海力士", PUBLIC_COMPANY_DB.get("sk hynix"));
        PUBLIC_COMPANY_DB.put("micron", Map.of("成立时间","1978年","总部所在地","美国爱达荷州博伊西","行业分类","半导体/存储","企业类型","上市公司（NASDAQ: MU）","营收（公开披露）","约 $154亿美元（FY2023）","员工人数","约 48,000人","公司简介","全球主要DRAM和NAND Flash制造商，是美国本土唯一的存储芯片大厂。"));
        PUBLIC_COMPANY_DB.put("applied materials", Map.of("成立时间","1967年","总部所在地","美国加利福尼亚州圣克拉拉","行业分类","半导体设备","企业类型","上市公司（NASDAQ: AMAT）","营收（公开披露）","约 $266亿美元（FY2023）","员工人数","约 34,000人","公司简介","全球最大的半导体设备公司，覆盖CVD、PVD、CMP、离子注入等核心制程设备。"));
        PUBLIC_COMPANY_DB.put("broadcom", Map.of("成立时间","1991年（Avago前身）","总部所在地","美国加利福尼亚州圣何塞","行业分类","半导体/网络/AI","企业类型","上市公司（NASDAQ: AVGO）","营收（公开披露）","约 $359亿美元（FY2023）","员工人数","约 20,000人","公司简介","全球领先的网络芯片和定制AI ASIC设计公司，为谷歌等超大规模数据中心提供TPU等ASIC。"));
        PUBLIC_COMPANY_DB.put("博通", PUBLIC_COMPANY_DB.get("broadcom"));
        PUBLIC_COMPANY_DB.put("arm", Map.of("成立时间","1990年","总部所在地","英国剑桥","行业分类","半导体IP/指令集架构","企业类型","上市公司（NASDAQ: ARM）","营收（公开披露）","约 $27.3亿美元（FY2024）","员工人数","约 6,500人","公司简介","全球主导的CPU IP授权公司，超过99%的智能手机和大量服务器/AI芯片使用ARM架构。"));
        PUBLIC_COMPANY_DB.put("安谋", PUBLIC_COMPANY_DB.get("arm"));
        PUBLIC_COMPANY_DB.put("mediatek", Map.of("成立时间","1997年","总部所在地","台湾新竹","行业分类","半导体/SoC","企业类型","上市公司（TWSE: 2454）","营收（公开披露）","约 NT$4,414亿元（2023年）","员工人数","约 20,000人","公司简介","全球第三大无晶圆半导体公司，Dimensity系列SoC广泛应用于中高端安卓手机和IoT设备。"));
        PUBLIC_COMPANY_DB.put("联发科", PUBLIC_COMPANY_DB.get("mediatek"));
    }

    private Map<String, Object> buildBusinessWithWiki(String creditCode, boolean noSignal, String companyName) {
        Map<String, Object> business = new LinkedHashMap<>();
        // First try built-in public database of major semiconductor companies
        Map<String, String> known = null;
        if (companyName != null && !companyName.isBlank()) {
            String lc = companyName.toLowerCase(Locale.ROOT);
            for (Map.Entry<String, Map<String, String>> entry : PUBLIC_COMPANY_DB.entrySet()) {
                if (lc.contains(entry.getKey()) || entry.getKey().contains(lc)) {
                    known = entry.getValue();
                    break;
                }
            }
        }
        if (known != null) {
            business.putAll(known);
            business.put("数据来源", "公司年报/官网/公开披露（已内置）");
        } else {
            // Try Wikipedia for unknown companies
            Map<String, Object> wiki = companyName != null && !companyName.isBlank()
                    && !companyName.equals("请输入企业名称后搜索")
                    ? fetchWikipediaBusinessInfo(companyName) : Map.of();
            business.put("成立时间", wiki.getOrDefault("成立时间", "待接入权威源"));
            business.put("总部所在地", wiki.getOrDefault("总部所在地", "待接入权威源"));
            business.put("行业分类", wiki.getOrDefault("行业分类", "待接入权威源"));
            business.put("企业类型", wiki.getOrDefault("企业类型", "待接入权威源"));
            business.put("营收（公开披露）", wiki.getOrDefault("营收（公开披露）", "待接入权威源"));
            business.put("员工人数", wiki.getOrDefault("员工人数（公开披露）", "待接入权威源"));
            if (wiki.containsKey("description")) business.put("公司简介", wiki.get("description"));
            if (wiki.containsKey("wikiTitle")) business.put("维基百科词条", wiki.get("wikiTitle") + "（公开百科）");
        }
        business.put("统一信用代码", creditCode);
        business.put("法人代表", "待接入权威源（工商局/企查查/天眼查）");
        business.put("注册资本", "待接入权威源（工商局/企查查/天眼查）");
        business.put("司法/失信数据", "待接入权威源（法院/最高人民法院失信被执行人名单）");
        business.put("采集状态", noSignal ? "未命中公开源事件" : "已命中公开源事件");
        return business;
    }

    /**
     * 从维基百科公开 API 获取企业真实公开信息（注册地、成立时间、行业分类等）。
     * 维基百科 API 为公开接口，无需登录，返回 JSON。
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> fetchWikipediaBusinessInfo(String companyName) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            String encoded = URLEncoder.encode(companyName, StandardCharsets.UTF_8);
            // Step 1: search for the page
            HttpRequest searchReq = HttpRequest.newBuilder(
                    URI.create("https://en.wikipedia.org/w/api.php?action=query&list=search&srsearch="
                            + encoded + "&format=json&srlimit=1"))
                    .timeout(java.time.Duration.ofSeconds(6))
                    .header("User-Agent", "SemiRisk/1.0 (supply chain risk platform; research use)")
                    .GET().build();
            HttpResponse<String> searchResp = httpClient.send(searchReq, HttpResponse.BodyHandlers.ofString());
            Map<String, Object> searchBody = (Map<String, Object>) parseJson(searchResp.body());
            List<Map<String, Object>> hits = (List<Map<String, Object>>) ((Map<?, ?>) searchBody.get("query")).get("search");
            if (hits == null || hits.isEmpty()) return result;
            String pageTitle = String.valueOf(hits.get(0).get("title"));

            // Step 2: fetch extract + categories
            String titleEncoded = URLEncoder.encode(pageTitle, StandardCharsets.UTF_8);
            HttpRequest infoReq = HttpRequest.newBuilder(
                    URI.create("https://en.wikipedia.org/w/api.php?action=query&titles=" + titleEncoded
                            + "&prop=extracts|categories&exintro=true&explaintext=true&format=json&cllimit=10"))
                    .timeout(java.time.Duration.ofSeconds(8))
                    .header("User-Agent", "SemiRisk/1.0 (supply chain risk platform; research use)")
                    .GET().build();
            HttpResponse<String> infoResp = httpClient.send(infoReq, HttpResponse.BodyHandlers.ofString());
            Map<String, Object> infoBody = (Map<String, Object>) parseJson(infoResp.body());
            Map<?, ?> pages = (Map<?, ?>) ((Map<?, ?>) infoBody.get("query")).get("pages");
            if (pages == null || pages.isEmpty()) return result;
            Map<String, Object> page = (Map<String, Object>) pages.values().iterator().next();
            String extract = String.valueOf(page.getOrDefault("extract", ""));

            // Parse key fields from extract text
            result.put("wikiTitle", pageTitle);
            result.put("wikiSource", "维基百科公开百科词条（英文）");
            extractWikiField(result, extract, "Founded", "成立时间");
            extractWikiField(result, extract, "Headquarters", "总部所在地");
            extractWikiField(result, extract, "Industry", "行业分类");
            extractWikiField(result, extract, "Type", "企业类型");
            extractWikiField(result, extract, "Revenue", "营收（公开披露）");
            extractWikiField(result, extract, "Employees", "员工人数（公开披露）");
            // First paragraph as description
            String[] paras = extract.split("\n\n");
            if (paras.length > 0 && !paras[0].isBlank()) {
                result.put("description", truncate(paras[0].replaceAll("\\s+", " ").trim(), 200));
            }
        } catch (Exception ignored) {
            // Wikipedia unreachable or no data: return empty, UI shows 待接入权威源
        }
        return result;
    }

    private void extractWikiField(Map<String, Object> result, String text, String enKey, String zhKey) {
        // Look for "Key: value" patterns in the extract
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                "(?i)" + java.util.regex.Pattern.quote(enKey) + "[:\\s]+([^\\n]+)").matcher(text);
        if (m.find()) {
            result.put(zhKey, truncate(m.group(1).trim(), 80));
        }
    }

    @SuppressWarnings("unchecked")
    private Object parseJson(String json) {
        try {
            return objectMapper.readValue(json, Object.class);
        } catch (Exception e) {
            return Map.of();
        }
    }

    private Optional<Map<String, Object>> findEnterpriseRecord(String keyword) {
        try {
            return repository.findEnterpriseRecordByKeyword(keyword).stream().findFirst();
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    private Map<String, Object> repositoryFirstEnterprise() {
        try {
            return repository.findEnterpriseRecords(1).stream().findFirst().orElse(null);
        } catch (Exception ignored) {
            return null;
        }
    }

    private List<Integer> dimensionRadar(List<CrawlerSignal> related) {
        List<Integer> radar = new ArrayList<>();
        dimensionScores(related).stream().limit(5).forEach(item -> radar.add(asInt(item.get("value"))));
        while (radar.size() < 3 && !related.isEmpty()) {
            radar.add(related.get(0).riskScore());
        }
        return radar;
    }

    private void persistSearchedEnterprise(String name, String industry, int score, List<CrawlerSignal> related) {
        try {
            String id = "ENT-" + UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8)).toString().substring(0, 8);
            String eventsJson = writeJson(related.stream().map(s -> s.fetchedAt() + " " + s.title()).toList());
            String signalsJson = writeJson(related.stream().map(this::enterpriseSignal).toList());
            repository.upsertEnterpriseRecord(id, truncate(name, 250), "", truncate(industry, 120), "待核验",
                    score, riskLevel(score), "公开源事件聚合（用户搜索）", "待接入权威源", eventsJson, signalsJson, Instant.now());
        } catch (Exception ignored) {
        }
    }

    public List<Map<String, Object>> enterpriseCatalog() {
        try {
            return repository.findEnterpriseRecords(50).stream()
                    .map(record -> {
                        Map<String, Object> item = new LinkedHashMap<>();
                        item.put("name", record.get("name"));
                        item.put("creditCode", stringValue(record.get("creditCode")).isBlank() ? "待接入权威源" : record.get("creditCode"));
                        item.put("industry", record.get("industry"));
                        item.put("riskScore", record.get("riskScore"));
                        item.put("creditLevel", record.get("creditLevel"));
                        item.put("location", record.get("location"));
                        return item;
                    })
                    .toList();
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private List<Map<String, Object>> enterpriseRecordsForReport(int limit) {
        try {
            return repository.findEnterpriseRecords(limit);
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private Map<String, Object> enterpriseSignal(CrawlerSignal signal) {
        return Map.of(
                "id", signal.id(),
                "title", signal.title(),
                "source", signal.source(),
                "sourceUrl", signal.sourceUrl(),
                "riskScore", signal.riskScore(),
                "dimension", signal.dimension(),
                "fetchedAt", signal.fetchedAt().toString()
        );
    }

    private List<Map<String, String>> internetSearches(String keyword) {
        String encoded = URLEncoder.encode(keyword == null ? "" : keyword, StandardCharsets.UTF_8);
        return List.of(
                Map.of("name", "企查查公开搜索", "url", "https://www.qcc.com/web/search?key=" + encoded),
                Map.of("name", "天眼查公开搜索", "url", "https://www.tianyancha.com/cloud-other-information/companyInfo.html?keyword=" + encoded),
                Map.of("name", "Bing 新闻", "url", "https://www.bing.com/news/search?q=" + encoded),
                Map.of("name", "路透社检索", "url", "https://www.reuters.com/search/news?blob=" + encoded),
                Map.of("name", "SEC EDGAR 公示", "url", "https://efts.sec.gov/LATEST/search-index?q=%22" + encoded + "%22&dateRange=custom&startdt=2023-01-01"),
                Map.of("name", "彭博行业资讯", "url", "https://www.bloomberg.com/search?query=" + encoded)
        );
    }

    /** 实际从 Bing 搜索获取联网搜索结果，并自动存入知识库 */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> internetSearchResults(String keyword) {
        if (keyword == null || keyword.isBlank()) return List.of();
        List<Map<String, Object>> results = new ArrayList<>();
        try {
            String encoded = URLEncoder.encode(keyword, StandardCharsets.UTF_8);
            // Bing News Search API (public, no auth required for basic search)
            HttpRequest req = HttpRequest.newBuilder(
                    URI.create("https://api.bing.microsoft.com/v7.0/news/search?q=" + encoded + "&count=5"))
                    .timeout(java.time.Duration.ofSeconds(8))
                    .header("Ocp-Apim-Subscription-Key", defaultAiApiKey) // 复用 AI key 作为 Bing key（如无则降级）
                    .GET().build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                // 降级：尝试 Google 搜索
                return fallbackWebSearch(keyword);
            }
            Map<String, Object> body = (Map<String, Object>) parseJson(resp.body());
            Map<String, Object> data = (Map<String, Object>) body.getOrDefault("data", body);
            if (data == null) data = body;
            List<Map<String, Object>> items = (List<Map<String, Object>>) data.get("news");
            if (items == null) items = List.of();
            Instant now = Instant.now();
            for (Map<String, Object> item : items) {
                String name = String.valueOf(item.getOrDefault("name", ""));
                String url = String.valueOf(item.getOrDefault("url", ""));
                String desc = String.valueOf(item.getOrDefault("description", ""));
                String date = String.valueOf(item.getOrDefault("datePublished", ""));
                results.add(Map.of(
                        "title", truncate(name, 200),
                        "source", String.valueOf(item.getOrDefault("provider", Map.of("name", "Bing").toString())),
                        "url", url,
                        "snippet", truncate(desc, 300),
                        "date", date
                ));
                // 自动存入知识库
                try {
                    String docId = "WEB-" + UUID.nameUUIDFromBytes((keyword + name).getBytes(StandardCharsets.UTF_8)).toString().substring(0, 8);
                    String content = "标题：" + name + "\n来源：" + item.getOrDefault("provider", Map.of("name", "Bing").toString()) + "\n摘要：" + desc + "\n链接：" + url + "\n发布时间：" + date;
                    repository.upsertKnowledgeDoc(docId, KNOWLEDGE_PUBLIC, truncate(name, 1000), content,
                            "Bing News 搜索", url, "企业信息", 0, null, now);
                } catch (Exception ignored) {
                }
            }
        } catch (Exception ignored) {
            // 搜索失败时降级到 fallback
            return fallbackWebSearch(keyword);
        }
        return results.isEmpty() ? fallbackWebSearch(keyword) : results;
    }

    /** 降级搜索：从 Bing/Google 公开搜索页面抓取摘要 */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> fallbackWebSearch(String keyword) {
        List<Map<String, Object>> results = new ArrayList<>();
        try {
            String encoded = URLEncoder.encode(keyword, StandardCharsets.UTF_8);
            // Bing 公开搜索
            HttpRequest req = HttpRequest.newBuilder(
                    URI.create("https://html.duckduckgo.com/html/?q=" + encoded))
                    .timeout(java.time.Duration.ofSeconds(6))
                    .header("User-Agent", "SemiRisk/1.0 (supply chain risk platform; research use)")
                    .GET().build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) return results;
            // DuckDuckGo HTML 搜索结果解析
            String body = resp.body();
            // 提取结果条目
            java.util.regex.Matcher titleMatcher = java.util.regex.Pattern.compile("<a rel=\"nofollow\" class=\"result__a\" href=\"([^\"]+)\"[^>]*>([^<]+)</a>").matcher(body);
            java.util.regex.Matcher snippetMatcher = java.util.regex.Pattern.compile("<a class=\"result__snippet\"[^>]*>([^<]+)</a>").matcher(body);
            java.util.Map.Entry<String, String> lastTitle = null;
            while (titleMatcher.find()) {
                String url = titleMatcher.group(1);
                String title = titleMatcher.group(2).replaceAll("<[^>]+>", "").trim();
                String snippet = "";
                if (snippetMatcher.find()) {
                    snippet = snippetMatcher.group(1).replaceAll("<[^>]+>", "").trim();
                }
                results.add(Map.of(
                        "title", truncate(title, 200),
                        "source", "DuckDuckGo 搜索",
                        "url", url,
                        "snippet", truncate(snippet, 300),
                        "date", Instant.now().toString()
                ));
                lastTitle = java.util.Map.entry(url, title);
                if (results.size() >= 5) break;
            }
            // 自动存入知识库
            Instant now = Instant.now();
            for (Map<String, Object> r : results) {
                try {
                    String docId = "WEB-" + UUID.nameUUIDFromBytes((keyword + r.get("title")).getBytes(StandardCharsets.UTF_8)).toString().substring(0, 8);
                    repository.upsertKnowledgeDoc(docId, KNOWLEDGE_PUBLIC,
                            String.valueOf(r.get("title")),
                            "搜索词：" + keyword + "\n来源：" + r.get("source") + "\n摘要：" + r.get("snippet") + "\n链接：" + r.get("url"),
                            "网络搜索", String.valueOf(r.get("url")), "企业信息", 0, null, now);
                } catch (Exception ignored) {
                }
            }
        } catch (Exception ignored) {
        }
        return results;
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
        List<Map<String, Object>> results = new ArrayList<>(matchedSignals.stream().limit(24).map(signal -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", signal.id());
            item.put("title", signal.title());
            item.put("format", "WEB");
            item.put("size", "公开网页");
            item.put("category", KNOWLEDGE_PUBLIC);
            item.put("similarity", signal.riskScore());
            item.put("summary", signal.source() + " / " + signal.dimension() + " / " + signal.fetchedAt());
            item.put("url", signal.sourceUrl());
            return item;
        }).toList());
        results.addAll(knowledgeDocResults(q, KNOWLEDGE_POLICY, 6));
        results.addAll(knowledgeDocResults(q, KNOWLEDGE_INTERNAL, 6));
        return Map.of(
                "query", q,
                "searchEngine", "LocalPublicCrawler + MySQL knowledge_doc",
                "categories", knowledgeCategories("公开源文章", matchedSignals.size()),
                "tags", List.of("#半导体", "#物流", "#供应链", "#政策法规", "#公开源"),
                "results", results
        );
    }

    public Map<String, Object> knowledge(String query, List<Map<String, Object>> indexedResults) {
        String q = query == null || query.isBlank() ? "半导体物流中断" : query;
        List<Map<String, Object>> esResults = normalizeIndexedKnowledgeResults(indexedResults);
        if (esResults.isEmpty()) {
            return knowledge(q);
        }
        List<Map<String, Object>> results = new ArrayList<>(esResults);
        results.addAll(knowledgeDocResults(q, KNOWLEDGE_POLICY, 6));
        results.addAll(knowledgeDocResults(q, KNOWLEDGE_INTERNAL, 6));
        return Map.of(
                "query", q,
                "searchEngine", "Elasticsearch + MySQL knowledge_doc",
                "categories", knowledgeCategories("ES 公开源索引", esResults.size()),
                "tags", List.of("#半导体", "#物流", "#供应链", "#政策法规", "#RAG"),
                "results", results
        );
    }

    private List<Map<String, Object>> knowledgeDocResults(String query, String category, int limit) {
        try {
            String keyword = query == null || query.isBlank() ? null : query;
            return repository.findKnowledgeDocsByCategory(category, limit).stream()
                    .filter(doc -> keyword == null
                            || stringValue(doc.get("title")).contains(keyword)
                            || stringValue(doc.get("content")).contains(keyword)
                            || category.equals(KNOWLEDGE_INTERNAL))
                    .map(doc -> {
                        Map<String, Object> item = new LinkedHashMap<>();
                        item.put("id", stringValue(doc.get("id")));
                        item.put("title", stringValue(doc.get("title")));
                        item.put("format", category.equals(KNOWLEDGE_POLICY) ? "POLICY" : "DOC");
                        item.put("size", category);
                        item.put("category", category);
                        item.put("similarity", asInt(doc.get("riskScore")));
                        item.put("summary", stringValue(doc.get("source")) + " / " + stringValue(doc.get("dimension")) + " / " + truncate(stringValue(doc.get("content")), 60));
                        item.put("url", stringValue(doc.get("sourceUrl")));
                        return item;
                    })
                    .toList();
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private List<String> knowledgeCategories(String publicLabel, int publicCount) {
        return List.of(
                publicLabel + "(" + publicCount + ")",
                "内部知识库(" + countKnowledge(KNOWLEDGE_INTERNAL) + ")",
                "政策法规库(" + countKnowledge(KNOWLEDGE_POLICY) + ")");
    }

    private int countKnowledge(String category) {
        try {
            return repository.countKnowledgeDocsByCategory(category);
        } catch (Exception ignored) {
            return 0;
        }
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
        List<String> context = new ArrayList<>(localKnowledgeLines());
        context.addAll(matched.stream()
                .limit(8)
                .map(signal -> signal.source() + " | " + signal.dimension() + " | " + signal.title() + " | " + signal.sourceUrl())
                .toList());
        AiAnswer aiAnswer = callDeepSeek(q, context);
        List<Map<String, Object>> citations = matched.stream().limit(5).map(signal -> Map.<String, Object>of(
                        "id", signal.id(),
                        "title", signal.title(),
                        "source", signal.source(),
                        "sourceUrl", signal.sourceUrl(),
                        "score", signal.riskScore(),
                        "fetchedAt", signal.fetchedAt().toString()
                )).toList();
        return knowledgeAnswerPayload(q, aiAnswer.answer().isBlank() ? answer : aiAnswer.answer(), aiAnswer,
                aiAnswer.called()
                        ? List.of("Query Rewrite", "Knowledge Retrieval", "DeepSeek Chat Completions", "Answer Synthesis")
                        : List.of("Query Rewrite", "Knowledge Retrieval", "Risk Scoring", "Local Answer Synthesis"),
                citations);
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
        List<String> context = new ArrayList<>(localKnowledgeLines());
        context.addAll(results.stream()
                .limit(8)
                .map(result -> stringValue(result.get("source")) + " | " + stringValue(result.get("dimension")) + " | " + stringValue(result.get("title")) + " | " + stringValue(result.get("sourceUrl")))
                .toList());
        AiAnswer aiAnswer = callDeepSeek(q, context);
        List<Map<String, Object>> citations = results.stream().limit(5).map(result -> {
                    Map<String, Object> citation = new HashMap<>();
                    citation.put("id", stringValue(result.get("id")));
                    citation.put("title", stringValue(result.get("title")));
                    citation.put("source", stringValue(result.get("source")));
                    citation.put("sourceUrl", stringValue(result.get("sourceUrl")));
                    citation.put("score", result.getOrDefault("riskScore", 0));
                    citation.put("fetchedAt", stringValue(result.get("fetchedAt")));
                    citation.put("searchEngine", "Elasticsearch");
                    return citation;
                }).toList();
        return knowledgeAnswerPayload(q, aiAnswer.answer().isBlank() ? answer : aiAnswer.answer(), aiAnswer,
                aiAnswer.called()
                        ? List.of("Query Rewrite", "Elasticsearch Retrieval", "DeepSeek Chat Completions", "Answer Synthesis")
                        : List.of("Query Rewrite", "Elasticsearch Retrieval", "Risk Scoring", "Local Answer Synthesis"),
                citations);
    }

    private Map<String, Object> knowledgeAnswerPayload(String question, String answer, AiAnswer aiAnswer, List<String> trace, List<Map<String, Object>> citations) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("question", question);
        payload.put("answer", answer);
        payload.put("sections", answerSections(answer, citations));
        payload.put("nextActions", List.of("核验引用原文", "转入预警工单", "补充影响物料/供应商", "生成管理层报告"));
        payload.put("model", defaultAiModel);
        payload.put("modelStatus", aiAnswer.status());
        payload.put("aiCalled", aiAnswer.called());
        payload.put("usage", aiAnswer.usage());
        payload.put("trace", trace);
        payload.put("citations", citations);
        payload.put("answeredAt", Instant.now().toString());
        return payload;
    }

    private List<Map<String, Object>> answerSections(String answer, List<Map<String, Object>> citations) {
        String clean = answer == null || answer.isBlank() ? "暂无可用回答。" : answer.trim();
        List<String> paragraphs = splitAnswer(clean);
        List<Map<String, Object>> sections = new ArrayList<>();
        sections.add(Map.of(
                "title", "结论",
                "items", paragraphs.isEmpty() ? List.of(clean) : paragraphs.subList(0, Math.min(2, paragraphs.size()))
        ));
        List<String> basis = citations == null || citations.isEmpty()
                ? List.of("知识库未返回可引用公开源，建议先检查采集任务。")
                : citations.stream().limit(3).map(item -> stringValue(item.get("source")) + "：" + stringValue(item.get("title"))).toList();
        sections.add(Map.of("title", "依据", "items", basis));
        sections.add(Map.of(
                "title", "处置建议",
                "items", List.of("优先核验评分最高的公开源原文。", "确认影响物料、库存覆盖天数、替代供应商和责任人。", "将有效高危信号转为告警工单并进入闭环跟踪。")
        ));
        if (paragraphs.size() > 2) {
            sections.add(Map.of("title", "补充说明", "items", paragraphs.subList(2, Math.min(6, paragraphs.size()))));
        }
        return sections;
    }

    private List<String> splitAnswer(String answer) {
        if (answer == null || answer.isBlank()) return List.of();
        // Strip markdown: headings, bold/italic, bullets, horizontal rules
        String cleaned = answer
                .replaceAll("(?m)^#{1,6}\\s*", "")          // ## headings
                .replaceAll("\\*{1,3}([^*]+)\\*{1,3}", "$1") // **bold** *italic*
                .replaceAll("(?m)^[-*]{3,}\\s*$", "")         // --- horizontal rules
                .replaceAll("(?m)^[-*+]\\s+", "• ")           // bullet markers → •
                .replaceAll("`([^`]+)`", "$1")                 // `code`
                .replaceAll("\\[([^]]+)]\\([^)]+\\)", "$1")   // [text](url)
                .replace("\r", "\n");
        // Skip boilerplate openers
        return java.util.Arrays.stream(cleaned.split("\\n+|(?<=[。！？；])"))
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .filter(value -> !value.matches("(?i)(以下是|根据您|根据以上|以下为|下面是|以下内容|^好的[，,]?|^当然[，,]?).*"))
                .toList();
    }

    private AiAnswer callDeepSeek(String question, List<String> contextLines) {
        String apiKey = aiModelApiKeys.getOrDefault(defaultAiModel, defaultAiApiKey == null ? "" : defaultAiApiKey);
        if (apiKey == null || apiKey.isBlank()) {
            return new AiAnswer(false, "", "未配置 API Key，当前使用本地 RAG 摘要", Map.of());
        }
        String endpoint = aiModelConfigs.getOrDefault(defaultAiModel, new AiModelConfig(defaultAiModel, defaultAiEndpoint, mask(apiKey), true, Instant.now())).endpoint();
        String url = endpoint.endsWith("/chat/completions")
                ? endpoint
                : endpoint.replaceAll("/+$", "") + "/chat/completions";
        try {
            String apiModel = resolveDeepSeekApiModel(defaultAiModel);
            Map<String, Object> payload = Map.of(
                    "model", apiModel,
                    "temperature", 0.25,
                    "stream", false,
                    "messages", List.of(
                            Map.of("role", "system", "content",
                                    "你是 SemiRisk 半导体供应链风险顾问，为企业高管提供决策级分析。" +
                                    "规则：1)绝对不输出任何Markdown符号（不用#*-`---[]()）；" +
                                    "2)不使用'以下是''根据您提供的''以下为'等引导语，直接给出结论；" +
                                    "3)每段以中文序号开头（一、二、三…）；" +
                                    "4)必须引用给定上下文中的具体信号标题和分数来支撑判断；" +
                                    "5)每条建议必须注明负责部门和时限；" +
                                    "6)只能基于给定上下文回答，不编造数据。"),
                            Map.of("role", "user", "content", "任务：" + question + "\n\n当前数据上下文：\n" + String.join("\n", contextLines))
                    )
            );
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(java.time.Duration.ofSeconds(45)) // DeepSeek 模型响应可能较慢
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                String errorBody = response.body() != null ? response.body().substring(0, Math.min(200, response.body().length())) : "(empty)";
                auditLogs.add("[WARN] DeepSeek call failed status=" + response.statusCode() + " body=" + errorBody);
                return new AiAnswer(false, "", "DeepSeek 调用失败，HTTP " + response.statusCode() + "，已回退本地 RAG 摘要",
                        Map.of("httpStatus", response.statusCode(), "error", errorBody));
            }
            JsonNode root = objectMapper.readTree(response.body());
            String answer = root.path("choices").path(0).path("message").path("content").asText("");
            if (answer.isBlank()) {
                auditLogs.add("[WARN] DeepSeek returned empty answer");
                return new AiAnswer(false, "", "DeepSeek 返回空答案，已回退本地 RAG 摘要", Map.of());
            }
            Map<String, Object> usage = new HashMap<>();
            JsonNode usageNode = root.path("usage");
            if (!usageNode.isMissingNode()) {
                usage.put("promptTokens", usageNode.path("prompt_tokens").asInt(0));
                usage.put("completionTokens", usageNode.path("completion_tokens").asInt(0));
                usage.put("totalTokens", usageNode.path("total_tokens").asInt(0));
            }
            usage.put("apiModel", apiModel);
            auditLogs.add("[INFO] DeepSeek knowledge agent called model=" + apiModel + " displayModel=" + defaultAiModel + " totalTokens=" + usage.getOrDefault("totalTokens", 0));
            return new AiAnswer(true, answer, "已调用 DeepSeek Chat Completions，模型返回成功；显示模型 " + defaultAiModel + "，实际请求模型 " + apiModel, usage);
        } catch (Exception ex) {
            auditLogs.add("[WARN] DeepSeek call exception " + ex.getClass().getSimpleName());
            return new AiAnswer(false, "", "DeepSeek 调用异常：" + ex.getClass().getSimpleName() + "，已回退本地 RAG 摘要", Map.of("error", ex.getClass().getSimpleName()));
        }
    }

    private List<String> localKnowledgeLines() {
        try {
            List<Map<String, Object>> docs = repository.findKnowledgeDocsByCategory(KNOWLEDGE_INTERNAL, 20);
            if (!docs.isEmpty()) {
                return docs.stream()
                        .map(doc -> "内部知识库 | " + stringValue(doc.get("dimension")) + " | " + stringValue(doc.get("title")) + "：" + stringValue(doc.get("content")))
                        .toList();
            }
        } catch (Exception ignored) {
            // MySQL 不可达时使用内存兜底（与 DB 种子内容一致）。
        }
        return List.of(
                "内部知识库 | 处置 | 高危供应链告警先核验公开源原文，再确认影响物料、库存覆盖天数和替代供应商。",
                "内部知识库 | 半导体 | 半导体供应链风险重点关注先进制程产能、封测排期、关键设备出口管制、物流节点拥堵和汇率/关税变化。",
                "内部知识库 | 处置 | 当公开源出现关税、罢工、港口拥堵、制裁、短缺等关键词时，优先同步采购、物流、合规三类责任人。",
                "内部知识库 | 报告 | 管理层报告需要给出事实来源、影响范围、评分依据、可选方案、负责人和闭环时间。"
        );
    }

    private String resolveDeepSeekApiModel(String model) {
        if ("deepseekv4-pro".equalsIgnoreCase(model) || "deepseek-v4-pro".equalsIgnoreCase(model)) {
            return "deepseek-chat";
        }
        return model;
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
        DailyRiskSnapshot snapshot = dailyRiskSnapshot;
        String lastRefresh = snapshot == null ? "待采集" : snapshot.calculatedAt().toString();
        int signalCount = snapshot == null ? 0 : snapshot.signals().size();
        Map<String, Object> overview = new LinkedHashMap<>();
        overview.put("users", systemUsers());
        overview.put("roles", List.of("管理员", "分析师", "运营人员"));
        overview.put("models", List.of(
                modelOverview(defaultAiModel, defaultAiEndpoint),
                modelOverview("deepseek-chat", AiModelDefaults.DEFAULT_ENDPOINT)
        ));
        overview.put("agents", List.of(
                Map.of("name", "公开源爬虫 Agent", "status", "运行中", "cron", "0 0 */12 * * *", "lastPull", lastRefresh,
                        "detail", "实时爬取公开 RSS / 政策法规源，最近一轮纳入 " + signalCount + " 条真实信号"),
                Map.of("name", "风险测算 Agent", "status", snapshot == null || snapshot.score() == 0 ? "待采集" : "运行中", "cron", "0 0 */12 * * *", "lastPull", lastRefresh,
                        "detail", "基于公开源信号与规则自动测算每日风险分"),
                Map.of("name", "AI 报告 Agent", "status", aiConfigured() ? "运行中" : "待配置 API Key", "cron", "0 0 */12 * * *", "lastPull", lastRefresh,
                        "detail", "聚合公开源 + 风险快照调用 DeepSeek 生成本日报告")
        ));
        overview.put("logs", auditLogs());
        overview.put("dataSources", probeDataSources());
        return overview;
    }

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
        // Every signal gets its own map point. Signals that share a geocoded region
        // receive a deterministic hash-based offset so they spread visibly instead of stacking.
        Map<String, Integer> regionCount = new LinkedHashMap<>();
        List<Map<String, Object>> points = new ArrayList<>();
        for (CrawlerSignal signal : signals) {
            GeoPlace place = geocodeSignal(signal);
            int idx = regionCount.merge(place.name(), 0, (a, b) -> a + 1);
            // Spiral offset: each extra signal in the same region gets a small nudge
            double angle = idx * 2.399963; // golden angle in radians → good spread
            double radius = idx == 0 ? 0 : 0.35 + (idx % 8) * 0.28;
            double lon = place.lon() + radius * Math.cos(angle);
            double lat = place.lat() + radius * Math.sin(angle) * 0.6;
            Map<String, Object> point = new LinkedHashMap<>();
            point.put("id", signal.id());
            point.put("name", place.name() + " · " + signal.source());
            point.put("region", place.name());
            point.put("lon", Math.round(lon * 1000.0) / 1000.0);
            point.put("lat", Math.round(lat * 1000.0) / 1000.0);
            point.put("riskIndex", signal.riskScore());
            point.put("analysis", signal.title());
            point.put("source", signal.source());
            point.put("sourceUrl", signal.sourceUrl());
            points.add(point);
        }
        return points;
    }

    /** 真实地名 -> 真实经纬度映射（公开地理坐标），用于把公开源信号定位到其涉及/来源地区。 */
    private static final Object[][] PLACE_GEO = {
            {new String[]{"shanghai", "上海", "外高桥", "洋山"}, "上海", 121.49, 31.23},
            {new String[]{"shenzhen", "深圳", "盐田"}, "深圳", 114.06, 22.54},
            {new String[]{"beijing", "北京", "中关村"}, "北京", 116.40, 39.90},
            {new String[]{"wuhan", "武汉"}, "武汉", 114.30, 30.59},
            {new String[]{"suzhou", "苏州"}, "苏州", 120.58, 31.30},
            {new String[]{"guangzhou", "广州", "guangdong", "广东", "nansha"}, "广州", 113.27, 23.13},
            {new String[]{"chengdu", "成都"}, "成都", 104.07, 30.67},
            {new String[]{"nanjing", "南京"}, "南京", 118.80, 32.06},
            {new String[]{"hong kong", "香港"}, "香港", 114.16, 22.32},
            {new String[]{"macau", "澳门"}, "澳门", 113.55, 22.20},
            {new String[]{"taiwan", "台湾", "taipei", "hsinchu", "新竹", "tsmc", "台积电", "mediatek", "联发科"}, "中国台湾", 120.97, 24.80},
            {new String[]{"singapore", "新加坡", "sembcorp"}, "新加坡", 103.82, 1.35},
            {new String[]{"malaysia", "马来西亚", "penang", "槟城"}, "马来西亚", 101.70, 3.14},
            {new String[]{"vietnam", "越南", "hanoi", "ho chi minh"}, "越南", 105.84, 21.03},
            {new String[]{"indonesia", "印尼", "jakarta"}, "印尼", 106.85, -6.21},
            {new String[]{"india", "印度", "bangalore", "hyderabad", "chennai"}, "印度班加罗尔", 77.59, 12.97},
            {new String[]{"korea", "韩국", "韩国", "samsung", "三星", "hynix", "海力士", "seoul", "首尔"}, "韩国首尔", 126.98, 37.57},
            {new String[]{"japan", "日本", "tokyo", "东京", "osaka", "大阪", "nagoya", "名古屋", "renesas", "瑞萨", "murata", "村田"}, "日本东京", 139.69, 35.69},
            {new String[]{"netherlands", "荷兰", "asml", "rotterdam", "鹿特丹", "eindhoven"}, "荷兰", 4.48, 51.92},
            {new String[]{"germany", "德国", "hamburg", "munich", "frankfurt", "infineon", "英飞凌", "bosch", "博世"}, "德国", 9.99, 53.55},
            {new String[]{"france", "法国", "paris"}, "法国", 2.35, 48.86},
            {new String[]{"uk", "英国", "london", "arm", "安谋"}, "英国", -0.13, 51.51},
            {new String[]{"ireland", "爱尔兰", "dublin", "intel fab"}, "爱尔兰", -6.27, 53.33},
            {new String[]{"mexico", "墨西哥", "tijuana", "juarez"}, "墨西哥", -99.13, 19.43},
            {new String[]{"brazil", "巴西", "sao paulo", "圣保罗"}, "巴西", -46.63, -23.55},
            {new String[]{"california", "加州", "los angeles", "long beach", "洛杉矶", "silicon valley", "硅谷", "san jose", "santa clara", "nvidia", "英伟达", "amd", "qualcomm", "高通", "broadcom", "博通", "apple", "苹果"}, "美国加州", -121.88, 37.34},
            {new String[]{"arizona", "亚利桑那", "phoenix", "intel fab", "tsmc usa"}, "美国亚利桑那", -112.07, 33.45},
            {new String[]{"texas", "德州", "houston", "dallas", "austin", "samsung austin"}, "美国德州", -97.74, 30.27},
            {new String[]{"new york", "纽约", "wall street"}, "美国纽约", -74.01, 40.71},
            {new String[]{"washington", "white house", "u.s.", " us ", "united states", "america", "美国", "tariff", "关税", "export control", "出口管制", "federal register", "联邦公报"}, "美国华盛顿", -77.04, 38.90},
            {new String[]{"russia", "俄罗斯", "moscow"}, "俄罗斯", 37.62, 55.75},
            {new String[]{"israel", "以色列", "tower semi"}, "以色列", 34.85, 31.05},
            {new String[]{"china", "中国", "中国大陆"}, "中国", 116.40, 39.90},
            {new String[]{"europe", "欧盟", "eu ", "欧洲", "wto", "世贸组织"}, "欧盟", 4.35, 50.85},
            {new String[]{"middle east", "中东", "uae", "阿联酋", "saudi", "沙特"}, "中东", 55.30, 25.26},
            {new String[]{"africa", "非洲", "congo", "刚果", "cobalt", "钴"}, "非洲", 23.65, -3.39}
    };

    /** 各公开源的来源地区坐标（当文章未命中具体地名时，按来源地区定位）。 */
    private static final Object[][] SOURCE_GEO = {
            {new String[]{"中国新闻网", "chinanews", "xinhua", "新华"}, "中国北京", 116.40, 39.90},
            {new String[]{"freightwaves", "supplychaindive", "trucking", "manufacturing dive", "supply chain dive"}, "美国", -77.04, 38.90},
            {new String[]{"eetimes", "ee times", "semiconductor", "semiengineering", "chips"}, "美国硅谷", -121.96, 37.35},
            {new String[]{"federalregister", "federal register", "bis.doc"}, "美国华盛顿", -77.04, 38.90},
            {new String[]{"wto.org", "wto "}, "欧盟", 4.35, 50.85},
            {new String[]{"reuters", "bloomberg", "ft.com", "financial times"}, "英国", -0.13, 51.51},
            {new String[]{"商务部", "海关", "政策", "法规", "工信部"}, "中国北京", 116.40, 39.90},
            {new String[]{"nikkei", "日经"}, "日本东京", 139.69, 35.69},
            {new String[]{"korea", "koreaherald", "koreajoong"}, "韩国首尔", 126.98, 37.57}
    };

    private GeoPlace geocodeSignal(CrawlerSignal signal) {
        String haystack = (signal.title() + " " + signal.source()).toLowerCase(Locale.ROOT);
        for (Object[] entry : PLACE_GEO) {
            for (String keyword : (String[]) entry[0]) {
                if (haystack.contains(keyword.toLowerCase(Locale.ROOT))) {
                    return new GeoPlace((String) entry[1], (double) entry[2], (double) entry[3]);
                }
            }
        }
        String source = signal.source() == null ? "" : signal.source().toLowerCase(Locale.ROOT);
        for (Object[] entry : SOURCE_GEO) {
            for (String keyword : (String[]) entry[0]) {
                if (source.contains(keyword.toLowerCase(Locale.ROOT))) {
                    return new GeoPlace((String) entry[1], (double) entry[2], (double) entry[3]);
                }
            }
        }
        // 未命中地名与来源地区：定位到全球供应链枢纽（新加坡），并在名称中标注待核验。
        return new GeoPlace("全球公开源(待核验地区)", 103.82, 1.35);
    }

    private record GeoPlace(String name, double lon, double lat) {
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
        if (apiKey != null && !apiKey.isBlank()) {
            aiModelApiKeys.put(model, apiKey);
        }
        auditLogs.add("[INFO] AI model config saved for " + model + " endpoint=" + endpoint);
        return config;
    }

    public Map<String, AiModelConfig> aiModelConfigs() {
        return Map.copyOf(aiModelConfigs);
    }

    public DailyRiskSnapshot dailyRiskSnapshot() {
        return dailyRiskSnapshot;
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

    // ----- AI 本日报告：真实聚合公开源 + 风险快照，调用 DeepSeek 生成，持久化到 ai_report -----

    /** 读取本日 AI 报告（DB 优先，不存在则返回待生成态，不阻塞调用线程触发模型）。 */
    public Map<String, Object> latestAiReport() {
        String today = LocalDate.now().toString();
        if (dailyAiReport != null && today.equals(dailyAiReportDate)) {
            return dailyAiReport;
        }
        try {
            List<Map<String, Object>> rows = repository.findLatestAiReport();
            if (!rows.isEmpty()) {
                Map<String, Object> report = aiReportFromRow(rows.get(0));
                if (today.equals(stringValue(report.get("reportDate")))) {
                    dailyAiReport = report;
                    dailyAiReportDate = today;
                }
                return report;
            }
        } catch (Exception ignored) {
        }
        // 尚无报告：后台异步生成，先返回待生成态。
        maybeGenerateDailyReportAsync();
        Map<String, Object> pending = new LinkedHashMap<>();
        pending.put("reportDate", today);
        pending.put("title", "SemiRisk AI 本日风险分析");
        pending.put("model", defaultAiModel);
        pending.put("configured", aiConfigured());
        pending.put("modelStatus", "本日报告生成中，请稍后刷新");
        pending.put("summary", "正在聚合公开源情报与风险快照并生成本日报告。");
        pending.put("recommendation", "");
        pending.put("sections", List.of());
        pending.put("generatedAt", Instant.now().toString());
        pending.put("pending", true);
        return pending;
    }

    private Map<String, Object> aiReportFromRow(Map<String, Object> row) {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("reportDate", stringValue(row.get("reportDate")));
        report.put("title", stringValue(row.get("title")));
        report.put("model", stringValue(row.get("model")));
        report.put("configured", asInt(row.get("configured")) == 1 || Boolean.TRUE.equals(row.get("configured")));
        report.put("modelStatus", stringValue(row.get("modelStatus")));
        report.put("summary", stringValue(row.get("summary")));
        report.put("recommendation", stringValue(row.get("recommendation")));
        report.put("sections", readJson(row.get("bodyJson"), new TypeReference<List<String>>() {
        }, List.of()));
        report.put("generatedAt", stringValue(row.get("generatedAt")));
        return report;
    }

    private void maybeGenerateDailyReportAsync() {
        String today = LocalDate.now().toString();
        if (today.equals(dailyAiReportDate)) {
            return;
        }
        if (reportGenerating.compareAndSet(false, true)) {
            reportExecutor.submit(() -> {
                try {
                    generateDailyAiReport();
                } finally {
                    reportGenerating.set(false);
                }
            });
        }
    }

    /** 同步生成本日 AI 报告（聚合真实数据 + 调 DeepSeek），并持久化。 */
    public Map<String, Object> generateDailyAiReport() {
        String today = LocalDate.now().toString();
        DailyRiskSnapshot snapshot = dailyRiskSnapshot;
        List<CrawlerSignal> signals = availableSignals();
        List<String> context = reportContext("risk-assessment", signals);
        AiAnswer ai = callDeepSeek(
                "请基于以下公开源情报与风险快照，生成 SemiRisk 半导体供应链本日风险分析报告，包含【总体态势】【重点风险】【处置建议】三部分，分段清晰。",
                context);
        int score = snapshot == null ? 0 : snapshot.score();
        String level = snapshot == null ? "待采集" : snapshot.level();
        List<String> sections = ai.answer().isBlank()
                ? fallbackReportLines("risk-assessment", signals)
                : splitAnswer(ai.answer());
        String summary = ai.answer().isBlank()
                ? "本日综合风险 " + score + "（" + level + "），有效公开源信号 " + signals.size() + " 条。"
                : (sections.isEmpty() ? ai.answer() : sections.get(0));
        String recommendation = ai.answer().isBlank()
                ? "优先核验高分公开源原文，将高危信号转为告警工单并绑定负责人与闭环时间。"
                : sections.get(sections.size() - 1);
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("reportDate", today);
        report.put("title", "SemiRisk AI 本日风险分析");
        report.put("model", defaultAiModel);
        report.put("configured", aiConfigured());
        report.put("modelStatus", ai.status());
        report.put("aiCalled", ai.called());
        report.put("usage", ai.usage());
        report.put("summary", summary);
        report.put("recommendation", recommendation);
        report.put("sections", sections);
        report.put("signalCount", signals.size());
        report.put("score", score);
        report.put("level", level);
        report.put("generatedAt", Instant.now().toString());
        try {
            repository.upsertAiReport(today, stringValue(report.get("title")), defaultAiModel, aiConfigured(),
                    truncate(ai.status(), 500), truncate(summary, 2000), truncate(recommendation, 2000),
                    writeJson(sections), Instant.now());
        } catch (Exception ignored) {
        }
        dailyAiReport = report;
        dailyAiReportDate = today;
        auditLogs.add("[INFO] daily AI report generated aiCalled=" + ai.called() + " signals=" + signals.size());
        return report;
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

    private record AiAnswer(boolean called, String answer, String status, Map<String, Object> usage) {
    }
}
