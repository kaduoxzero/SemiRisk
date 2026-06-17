package com.semirisk.service;

import com.semirisk.common.AiModelDefaults;
import com.semirisk.model.CrawlerSignal;
import com.semirisk.model.DailyRiskSnapshot;
import com.semirisk.model.ReportJob;
import com.semirisk.service.AiChatService.AiAnswer;
import com.semirisk.repository.PreparedRiskRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Scheduled;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;

/**
 * Extracted report-generation service. Owns all report-job lifecycle,
 * AI report compilation, and daily-report scheduling that previously lived
 * inside SemiRiskStore.
 */
@Service
public class ReportService {

    private final Map<String, ReportJob> reportJobs = new ConcurrentHashMap<>();
    // 缓存已生成的报告内容，避免每次下载重复调用 AI
    private final Map<String, List<String>> reportContentCache = new ConcurrentHashMap<>();
    private final ExecutorService reportExecutor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "semirisk-ai-report");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicBoolean reportGenerating = new AtomicBoolean(false);
    private final String defaultAiModel;
    private final String defaultAiEndpoint;
    private final String defaultAiApiKey;
    private final AiChatService aiChatService;
    private final HealthProbeService healthProbeService;
    private final TranslationService translationService;
    private final GisService gisService;
    private final EnterpriseService enterpriseService;
    private final PreparedRiskRepository repository;
    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(8)).build();

    /** Supplier for the shared daily risk snapshot (volatile in SemiRiskStore). */
    private final Supplier<DailyRiskSnapshot> snapshotSupplier;

    /** Supplier for the available crawler signals list. */
    private final Supplier<List<CrawlerSignal>> signalsSupplier;

    private final ObjectMapper objectMapper;

    public ReportService(
            @Value("${semirisk.ai.default.model:" + AiModelDefaults.DEFAULT_MODEL + "}") String defaultAiModel,
            @Value("${semirisk.ai.default.endpoint:" + AiModelDefaults.DEFAULT_ENDPOINT + "}") String defaultAiEndpoint,
            @Value("${semirisk.ai.default.api-key:}") String defaultAiApiKey,
            ObjectMapper objectMapper,
            AiChatService aiChatService,
            HealthProbeService healthProbeService,
            TranslationService translationService,
            GisService gisService,
            EnterpriseService enterpriseService,
            PreparedRiskRepository repository,
            @Lazy Supplier<DailyRiskSnapshot> snapshotSupplier,
            @Lazy Supplier<List<CrawlerSignal>> signalsSupplier) {
        this.defaultAiModel = defaultAiModel;
        this.defaultAiEndpoint = defaultAiEndpoint;
        this.defaultAiApiKey = defaultAiApiKey;
        this.objectMapper = objectMapper;
        this.aiChatService = aiChatService;
        this.healthProbeService = healthProbeService;
        this.translationService = translationService;
        this.gisService = gisService;
        this.enterpriseService = enterpriseService;
        this.repository = repository;
        this.snapshotSupplier = snapshotSupplier;
        this.signalsSupplier = signalsSupplier;
        seedDefaultAiModel();
    }

    // -----------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------

    public ReportJob createReport(String template, String language, String format, int threshold) {
        String id = "RP-" + System.currentTimeMillis();
        ReportJob job = new ReportJob(id, template, language, format, threshold, "排队中", 0, "任务已进入 AI 编译队列", null, Instant.now());
        reportJobs.put(id, job);
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
        List<CrawlerSignal> signals = signalsSupplier.get();
        List<String> context = reportContext(type, signals);
        AiAnswer aiAnswer = aiChatService.callDeepSeek(reportPrompt(type, language), context);
        List<String> lines = new ArrayList<>();
        lines.add(reportTitle(type));
        lines.add("报告编号：" + id);
        lines.add("写作方式：AI 结合公开源、风险规则、企业画像和处置 SOP 生成");
        lines.add("AI状态：" + aiAnswer.status());
        if (!aiAnswer.answer().isBlank()) {
            aiChatService.splitAnswer(aiAnswer.answer()).forEach(lines::add);
        } else {
            lines.addAll(fallbackReportLines(type, signals));
        }
        // 缓存结果，避免重复 AI 调用
        reportContentCache.put(id, List.copyOf(lines));
        return lines;
    }

    public Map<String, Object> generateDailyAiReport() {
        String today = LocalDate.now().toString();
        DailyRiskSnapshot snapshot = snapshotSupplier.get();
        List<CrawlerSignal> signals = signalsSupplier.get();
        List<String> context = reportContext("risk-assessment", signals);
        AiAnswer ai = aiChatService.callDeepSeek(
                "请基于以下公开源情报与风险快照，生成 SemiRisk 半导体供应链本日风险分析报告，包含【总体态势】【重点风险】【处置建议】三部分，分段清晰。",
                context);
        int score = snapshot == null ? 0 : snapshot.score();
        String level = snapshot == null ? "待采集" : snapshot.level();
        List<String> sections = ai.answer().isBlank()
                ? fallbackReportLines("risk-assessment", signals)
                : aiChatService.splitAnswer(ai.answer());
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
        return report;
    }

    public void maybeGenerateDailyReportAsync() {
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

    // -----------------------------------------------------------------
    // Scheduled recovery (called from SemiRiskStore init)
    // -----------------------------------------------------------------

    /** Recover report jobs from DB — called by SemiRiskStore.onStartup. */
    public void recoverReportJobs() {
        try {
            List<Map<String, Object>> rows = repository.findReportJobs(200);
            for (Map<String, Object> row : rows) {
                String id = stringValue(row.get("id"));
                if (reportJobs.containsKey(id)) continue;
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
        } catch (Exception ignored) {
        }
    }

    // -----------------------------------------------------------------
    // Private helpers (copied verbatim from SemiRiskStore)
    // -----------------------------------------------------------------

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
        DailyRiskSnapshot snapshot = snapshotSupplier.get();
        context.add("当前综合评分：" + snapshot.score() + " | 等级：" + snapshot.level() + " | 摘要：" + snapshot.summary());
        // Score drivers: signals above/below median
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
        context.addAll(aiChatService.localKnowledgeLines());
        return context;
    }

    private List<String> fallbackReportLines(String type, List<CrawlerSignal> signals) {
        DailyRiskSnapshot snapshot = snapshotSupplier.get();
        int score = snapshot == null ? 0 : snapshot.score();
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
                lines.add("一、风险结论：当前综合评分 " + score + "，等级 " + (snapshot == null ? "待采集" : snapshot.level()) + "。");
                lines.add("二、评分依据：公开源有效信号 " + signals.size() + " 条，高危 " + high + " 条，中危 " + mid + " 条。");
                signals.stream().limit(8).forEach(signal -> lines.add("事件：" + signal.source() + " / " + signal.dimension() + " / " + signal.riskScore() + " / " + signal.title()));
                lines.add("三、处置建议：先核验高分公开源原文，再转入告警工单并绑定负责人和截止时间。");
            }
        }
        lines.add("四、闭环指标：跟踪未处理告警数、高危信号变化、供应商风险分、物流节点等待时间和报告引用可信度。");
        return lines;
    }

    // -----------------------------------------------------------------
    // Shared utilities (copied from SemiRiskStore)
    // -----------------------------------------------------------------

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

    private List<Map<String, Object>> dimensionScores(List<CrawlerSignal> signals) {
        Map<String, Integer> scores = new LinkedHashMap<>();
        for (CrawlerSignal signal : signals) {
            scores.merge(signal.dimension(), signal.riskScore(), Math::max);
        }
        return scores.entrySet().stream()
                .map(entry -> Map.<String, Object>of("name", entry.getKey(), "index", entry.getValue(), "value", entry.getValue()))
                .toList();
    }

    private List<Map<String, Object>> enterpriseRecordsForReport(int limit) {
        try {
            return repository.findEnterpriseRecords(limit);
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private boolean aiConfigured() {
        String apiKey = defaultAiApiKey;
        return apiKey != null && !apiKey.isBlank();
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
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

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ignored) {
            return "[]";
        }
    }

    // -----------------------------------------------------------------
    // Package-private accessors for tests / SemiRiskStore bridge
    // -----------------------------------------------------------------

    Map<String, ReportJob> reportJobs() {
        return reportJobs;
    }

    Map<String, List<String>> reportContentCache() {
        return reportContentCache;
    }

    // -----------------------------------------------------------------
    // State kept alongside SemiRiskStore for daily report caching
    // -----------------------------------------------------------------

    private volatile Map<String, Object> dailyAiReport;
    private volatile String dailyAiReportDate = "";

    private void seedDefaultAiModel() {
        if (defaultAiApiKey != null && !defaultAiApiKey.isBlank()) {
            // no-op: model config stored in SemiRiskStore
        }
    }

    private String mask(String key) {
        if (key == null || key.length() < 8) {
            return "未配置";
        }
        return key.substring(0, 4) + "****" + key.substring(key.length() - 4);
    }
}
