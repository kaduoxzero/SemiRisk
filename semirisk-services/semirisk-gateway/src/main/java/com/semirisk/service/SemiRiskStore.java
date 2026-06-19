package com.semirisk.service;

import com.semirisk.common.AiModelDefaults;
import com.semirisk.common.SemiriskConstants;
import com.semirisk.model.AiModelConfig;
import com.semirisk.model.CrawlerSignal;
import com.semirisk.model.DailyRiskSnapshot;
import com.semirisk.model.LoginCounter;
import com.semirisk.model.LoginState;
import com.semirisk.model.ReportJob;
import com.semirisk.model.RiskAlert;
import com.semirisk.model.SystemUser;
import com.semirisk.model.UploadTask;
import com.semirisk.model.UserAccount;
import com.semirisk.repository.PreparedRiskRepository;
import com.semirisk.service.AiChatService.AiAnswer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import jakarta.annotation.PreDestroy;
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
import java.util.function.Supplier;
import java.util.regex.Pattern;

@Service
public class SemiRiskStore {

    private static final Logger log = LoggerFactory.getLogger(SemiRiskStore.class);

    private final Map<String, UserAccount> users = new ConcurrentHashMap<>();
    private final Map<String, LoginCounter> loginCounters = new ConcurrentHashMap<>();
    private final Map<String, Instant> resetTokens = new ConcurrentHashMap<>();
    private final Map<String, UploadTask> uploadTasks = new ConcurrentHashMap<>();
    private final Map<String, ReportJob> reportJobs = new ConcurrentHashMap<>();
    // 缓存已生成的报告内容，避免每次下载重复调用 AI
    private final Map<String, List<String>> reportContentCache = new ConcurrentHashMap<>();
    private final Map<String, RiskAlert> alerts = new ConcurrentHashMap<>(); // 已废弃，由 AlertService 统一管理
    // publicAlertStatuses 已废弃：所有告警状态由 AlertService 统一管理，SemiRiskStore 通过 alertService 桥接访问
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
    private final AuthService authService;
    private final AlertService alertService;
    private final UploadService uploadService;
    private final ReportService reportService;
    private final DashboardService dashboardService;
    private final SystemManagementService systemManagementService;
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
            AuthService authService,
            AlertService alertService,
            UploadService uploadService,
            @Lazy ReportService reportService,
            DashboardService dashboardService,
            SystemManagementService systemManagementService,
            @Lazy AiChatService aiChatService) {
        this.defaultAiModel = defaultAiModel;
        this.defaultAiEndpoint = defaultAiEndpoint;
        this.defaultAiApiKey = defaultAiApiKey;
        this.objectMapper = objectMapper;
        this.repository = repository;
        this.healthProbeService = healthProbeService;
        this.translationService = translationService;
        this.gisService = gisService;
        this.enterpriseService = enterpriseService;
        this.authService = authService;
        this.alertService = alertService;
        this.uploadService = uploadService;
        this.reportService = reportService;
        this.dashboardService = dashboardService;
        this.systemManagementService = systemManagementService;
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
        } catch (Exception ex) {
            log.error("Failed to recover crawler signals from MySQL on startup", ex);
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
        } catch (Exception ex) {
            log.warn("Failed to load recent crawler signals from MySQL", ex);
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
        } catch (Exception ex) {
            log.error("Failed to persist crawler signals to MySQL", ex);
        }
    }

    private void persistRiskSnapshot(DailyRiskSnapshot snapshot) {
        try {
            repository.insertRiskSnapshot(snapshot.score(), snapshot.level(), truncate(snapshot.summary(), 1000),
                    snapshot.signals().size(), snapshot.calculatedAt());
        } catch (Exception ex) {
            log.error("Failed to persist risk snapshot to MySQL", ex);
        }
    }

    private void persistPublicAlerts(List<CrawlerSignal> signals) {
        try {
            Map<String, String> statuses = alertService.getPublicAlertStatusesMap();
            for (CrawlerSignal s : signals) {
                String status = statuses.getOrDefault(s.id(), "未处理");
                repository.upsertPublicAlert(s.id(), s.fetchedAt(), riskLevel(s.riskScore()),
                        truncate(s.title(), 250), truncate(s.source(), 120), status, "risk-detail.html");
            }
        } catch (Exception ex) {
            log.error("Failed to persist public alerts to MySQL", ex);
        }
    }

    private void persistAlertStatus(String id, String status, RiskAlert alert) {
        try {
            repository.upsertPublicAlert(id, alert.time(), alert.level(), truncate(alert.title(), 250),
                    truncate(alert.source(), 120), status, "risk-detail.html");
            repository.updateAlertStatus(id, status);
        } catch (Exception ex) {
            log.error("Failed to persist alert status to MySQL, alert={}", id, ex);
        }
    }

    private void loadAlertStatusesFromDb() {
        try {
            Map<String, String> statuses = alertService.getPublicAlertStatusesMap();
            repository.findAlerts(null, null, null, 500).forEach(row -> {
                String id = stringValue(row.get("id"));
                String status = stringValue(row.get("status"));
                if (!id.isBlank() && !status.isBlank() && !"未处理".equals(status)) {
                    statuses.put(id, status);
                }
            });
        } catch (Exception ex) {
            log.warn("Failed to load alert statuses from MySQL", ex);
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
                if (!"已入库".equals(status) && !"AI评估失败".equals(status)) {
                    UploadTask task = new UploadTask(id, filename, size, status, createdAt, rowsCount, List.of());
                    uploadTasks.put(id, task);
                }
            }
            auditLogs.add("[INFO] recovered " + rows.size() + " upload tasks from MySQL");
        } catch (Exception ex) {
            log.error("Failed to recover upload tasks from MySQL", ex);
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
        } catch (Exception ex) {
            log.error("Failed to recover report jobs from MySQL", ex);
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
        } catch (Exception ex) {
            log.error("Failed to persist knowledge docs to MySQL", ex);
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
        } catch (Exception ex) {
            log.error("Failed to seed internal knowledge docs to MySQL", ex);
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
        } catch (Exception ex) {
            log.error("Failed to seed enterprise watchlist to MySQL", ex);
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
        } catch (Exception ex) {
            log.error("Failed to refresh enterprise records in MySQL", ex);
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
        return authService.authenticate(username, password);
    }

    /** 从 MySQL 恢复已注册用户到内存，确保数据库可用时登录路径一致。 */
    public void recoverUsersToMemory() {
        authService.recoverUsersToMemory();
    }

    private List<Map<String, Object>> findAllSystemUsers() {
        try {
            return repository.findSystemUsers();
        } catch (Exception ex) {
            log.warn("Failed to fetch system users from MySQL", ex);
            return List.of();
        }
    }

    public UserAccount register(String username, String password, String displayName, String email) {
        return authService.register(username, password, displayName, email);
    }

    public UserAccount upsertLoginUser(String username, String password, String displayName, String email, String role) {
        return authService.upsertLoginUser(username, password, displayName, email, role);
    }

    public LoginState loginState(String username) {
        return authService.loginState(username);
    }

    public LoginState recordFailure(String username) {
        return authService.recordFailure(username);
    }

    public String createResetToken(String email) {
        return authService.createResetToken(email);
    }

    public List<RiskAlert> alerts() {
        return alertService.alerts();
    }

    public Optional<RiskAlert> alert(String id) {
        return alertService.alert(id);
    }

    public RiskAlert updateAlertStatus(String id, String status) {
        return alertService.updateAlertStatus(id, status, this::availableSignals);
    }

    public UploadTask createUpload(MultipartFile file) throws IOException {
        return uploadService.createUpload(file);
    }

    public UploadTask completeUpload(String id, int rows, List<String> warnings) {
        return uploadService.completeUpload(id, rows, warnings);
    }

    public Optional<UploadTask> uploadTask(String id) {
        return uploadService.uploadTask(id);
    }

    public List<UploadTask> uploadTasks() {
        return uploadService.uploadTasks();
    }

    public ReportJob createReport(String template, String language, String format, int threshold) {
        return reportService.createReport(template, language, format, threshold);
    }

    public ReportJob advanceReport(String id) {
        return reportService.advanceReport(id);
    }

    public Optional<ReportJob> reportJob(String id) {
        return reportService.reportJob(id);
    }

    public List<String> aiReportLines(String id, String template, String language) {
        return reportService.aiReportLines(id, template, language);
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
        // 评分驱动因素：高于/低于中位数的信号
        long high = signals.stream().filter(s -> s.riskScore() >= 70).count();
        long mid = signals.stream().filter(s -> s.riskScore() >= 50 && s.riskScore() < 70).count();
        long low = signals.stream().filter(s -> s.riskScore() < 50).count();
        context.add("评分构成：高危信号 " + high + " 条（>=70分）拉高综合评分，中危 " + mid + " 条（50-69分），低危/监控 " + low + " 条（<50分）。");
        context.add("信号总数：" + signals.size() + " 条，来源渠道：" + signals.stream().map(CrawlerSignal::source).distinct().count() + " 个。");
        switch (type) {
            case "supply-chain" -> {
                gisService.gisRoutes(gisService.gisPoints(signals)).stream().limit(8).forEach(route ->
                        context.add("物流路径：" + route.get("name") + " / 风险 " + route.get("riskIndex")));
                dimensionScores(signals).forEach(item ->
                        context.add("供应链维度评分：" + item.get("name") + " = " + item.get("value")));
            }
            case "enterprise-dd" ->
                    enterpriseRecordsForReport(5).forEach(profile ->
                            context.add("企业画像：" + profile.get("name") + " | " + profile.get("industry") + " | 风险 " + profile.get("riskScore") + " | " + profile.get("creditLevel")));
            default -> {
                // 评分的主要风险驱动因素
                signals.stream().filter(s -> s.riskScore() >= 60).limit(5).forEach(s ->
                        context.add("高风险驱动信号：[" + s.riskScore() + "分] " + s.source() + " | " + s.dimension() + " | " + s.title()));
            }
        }
        // 所有信号的完整详细信息供 AI 推理
        signals.stream().limit(20).forEach(signal ->
                context.add("公开源信号 [" + signal.riskScore() + "分] 来源：" + signal.source()
                        + " | 维度：" + signal.dimension()
                        + " | 标题：" + signal.title()
                        + " | 链接：" + signal.sourceUrl()));
        context.addAll(aiChatService.localKnowledgeLines());
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
                gisService.gisRoutes(gisService.gisPoints(signals)).stream().limit(6).forEach(route -> lines.add("路径：" + route.get("name") + "，风险指数 " + route.get("riskIndex") + "。"));
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
        } catch (Exception ex) {
            log.error("Failed to fetch audit logs from MySQL", ex);
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
        return dashboardService.dashboard();
    }

    public Map<String, Object> riskAnalysis(String window) {
        return dashboardService.riskAnalysis(window);
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
        return dashboardService.riskDetail(id);
    }

    private Map<String, Object> bilingualRow(String zhLabel, String enLabel, Object zhValue, Object enValue) {
        return Map.of(
                "zhLabel", zhLabel,
                "enLabel", enLabel,
                "zh", zhValue == null ? "" : String.valueOf(zhValue),
                "en", enValue == null ? "" : String.valueOf(enValue)
        );
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
        // 首先尝试内置的主要半导体公司公共数据库
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
            // 未知公司尝试查询维基百科
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
            // 第一步：搜索页面
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

            // 第二步：获取摘要和分类
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

            // 从摘要文本解析关键字段
            result.put("wikiTitle", pageTitle);
            result.put("wikiSource", "维基百科公开百科词条（英文）");
            extractWikiField(result, extract, "Founded", "成立时间");
            extractWikiField(result, extract, "Headquarters", "总部所在地");
            extractWikiField(result, extract, "Industry", "行业分类");
            extractWikiField(result, extract, "Type", "企业类型");
            extractWikiField(result, extract, "Revenue", "营收（公开披露）");
            extractWikiField(result, extract, "Employees", "员工人数（公开披露）");
            // 第一段作为简介
            String[] paras = extract.split("\n\n");
            if (paras.length > 0 && !paras[0].isBlank()) {
                result.put("description", truncate(paras[0].replaceAll("\\s+", " ").trim(), 200));
            }
        } catch (Exception ignored) {
            // 维基百科不可达或无数据：返回空值，UI 显示待接入权威源
        }
        return result;
    }

    private void extractWikiField(Map<String, Object> result, String text, String enKey, String zhKey) {
        // 在摘要中查找"键: 值"模式
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
        } catch (Exception ex) {
            log.warn("Failed to find enterprise record by keyword in MySQL, keyword={}", keyword, ex);
            return Optional.empty();
        }
    }

    private Map<String, Object> repositoryFirstEnterprise() {
        try {
            return repository.findEnterpriseRecords(1).stream().findFirst().orElse(null);
        } catch (Exception ex) {
            log.warn("Failed to fetch first enterprise record from MySQL", ex);
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
        } catch (Exception ex) {
            log.error("Failed to persist searched enterprise to MySQL, name={}", name, ex);
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
        } catch (Exception ex) {
            log.error("Failed to fetch enterprise catalog from MySQL", ex);
            return List.of();
        }
    }

    private List<Map<String, Object>> enterpriseRecordsForReport(int limit) {
        try {
            return repository.findEnterpriseRecords(limit);
        } catch (Exception ex) {
            log.warn("Failed to fetch enterprise records from MySQL", ex);
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
        List<Map<String, Object>> points = gisService.gisPoints(availableSignals);
        return Map.of(
                "layers", layers == null ? "heatmap,suppliers,ports,routes" : layers,
                "regions", regionsFromSignals(availableSignals),
                "points", points,
                "routes", gisService.gisRoutes(points),
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
        } catch (Exception ex) {
            log.warn("Failed to fetch knowledge docs from MySQL, category={}", category, ex);
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
        } catch (Exception ex) {
            log.warn("Failed to count knowledge docs in MySQL, category={}", category, ex);
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
        List<String> context = new ArrayList<>(aiChatService.localKnowledgeLines());
        context.addAll(matched.stream()
                .limit(8)
                .map(signal -> signal.source() + " | " + signal.dimension() + " | " + signal.title() + " | " + signal.sourceUrl())
                .toList());
        AiAnswer aiAnswer = aiChatService.callDeepSeek(q, context);
        List<Map<String, Object>> citations = matched.stream().limit(5).map(signal -> Map.<String, Object>of(
                        "id", signal.id(),
                        "title", signal.title(),
                        "source", signal.source(),
                        "sourceUrl", signal.sourceUrl(),
                        "score", signal.riskScore(),
                        "fetchedAt", signal.fetchedAt().toString()
                )).toList();
        return aiChatService.buildKnowledgeAnswerPayload(q, aiAnswer.answer().isBlank() ? answer : aiAnswer.answer(), aiAnswer,
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
        List<String> context = new ArrayList<>(aiChatService.localKnowledgeLines());
        context.addAll(results.stream()
                .limit(8)
                .map(result -> stringValue(result.get("source")) + " | " + stringValue(result.get("dimension")) + " | " + stringValue(result.get("title")) + " | " + stringValue(result.get("sourceUrl")))
                .toList());
        AiAnswer aiAnswer = aiChatService.callDeepSeek(q, context);
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
        return aiChatService.buildKnowledgeAnswerPayload(q, aiAnswer.answer().isBlank() ? answer : aiAnswer.answer(), aiAnswer,
                aiAnswer.called()
                        ? List.of("Query Rewrite", "Elasticsearch Retrieval", "DeepSeek Chat Completions", "Answer Synthesis")
                        : List.of("Query Rewrite", "Elasticsearch Retrieval", "Risk Scoring", "Local Answer Synthesis"),
                citations);
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
        return systemManagementService.systemOverview();
    }

    private List<Map<String, Object>> probeDataSources() {
        try {
            return healthProbeService.probeAll();
        } catch (Exception ex) {
            log.warn("Failed to probe data sources", ex);
            return List.of();
        }
    }

    private boolean aiConfigured() {
        return isAiConfigured();
    }

    public List<CrawlerSignal> availableSignals() {
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
        Map<String, String> statuses = alertService.getPublicAlertStatusesMap();
        return signals.stream()
                .sorted(Comparator.comparing(CrawlerSignal::riskScore).reversed())
                .map(signal -> new RiskAlert(signal.id(), signal.fetchedAt(), riskLevel(signal.riskScore()), signal.title(), signal.source(), signal.sourceUrl(), statuses.getOrDefault(signal.id(), "未处理"), "risk-detail.html"))
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
        } else {
            aiModelApiKeys.remove(model);
        }
        auditLogs.add("[INFO] AI model config saved for " + model + " endpoint=" + endpoint);
        return config;
    }

    public Map<String, AiModelConfig> aiModelConfigs() {
        return Map.copyOf(aiModelConfigs);
    }

    public Map<String, String> aiModelApiKeys() {
        return Map.copyOf(aiModelApiKeys);
    }

    public String getAiApiKey(String model) {
        return aiModelApiKeys.getOrDefault(model, defaultAiApiKey == null ? "" : defaultAiApiKey);
    }

    public boolean isAiConfigured() {
        String key = getAiApiKey(defaultAiModel);
        return key != null && !key.isBlank();
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
        return reportService.latestAiReport();
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
        reportService.maybeGenerateDailyReportAsync();
    }

    /** 同步生成本日 AI 报告（聚合真实数据 + 调 DeepSeek），并持久化。 */
    public Map<String, Object> generateDailyAiReport() {
        return reportService.generateDailyAiReport();
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

    @PreDestroy
    public void shutdown() {
        httpClient.close();
    }

}
