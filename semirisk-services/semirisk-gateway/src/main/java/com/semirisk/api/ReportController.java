package com.semirisk.api;

import com.semirisk.model.CrawlerSignal;
import com.semirisk.model.DailyRiskSnapshot;
import com.semirisk.model.ReportJob;
import com.semirisk.service.KnowledgeSearchIndexService;
import com.semirisk.service.PublicCrawlerClient;
import com.semirisk.service.SemiRiskStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 报告生成与爬虫同步 API — 不属于 domain controllers 的剩余端点。
 * <p>
 * 其余端点已拆分为：
 * AuthController（认证）、DashboardController（仪表盘/风险）、
 * AlertController（告警）、EnterpriseController（企业/知识）、
 * SystemController（系统管理）、DataController（数据上传）。
 * </p>
 */
@RestController
@RequestMapping("/api")
@Validated
public class ReportController {

    private static final Logger log = LoggerFactory.getLogger(ReportController.class);

    private final SemiRiskStore store;
    private final PublicCrawlerClient publicCrawlerClient;
    private final KnowledgeSearchIndexService knowledgeSearchIndexService;

    @Value("${semirisk.crawler-sync.cooldown-seconds:60}")
    private int crawlerSyncCooldownSeconds;

    private volatile Instant lastCrawlerSync = Instant.EPOCH;

    public ReportController(SemiRiskStore store, PublicCrawlerClient publicCrawlerClient,
                            KnowledgeSearchIndexService knowledgeSearchIndexService) {
        this.store = store;
        this.publicCrawlerClient = publicCrawlerClient;
        this.knowledgeSearchIndexService = knowledgeSearchIndexService;
    }

    // -----------------------------------------------------------------
    // 报告模板
    // -----------------------------------------------------------------

    @GetMapping("/reports/templates")
    public List<Map<String, String>> reportTemplates() {
        return List.of(
                Map.of("id", "risk-assessment", "name", "风险评估报告",
                        "scenario", "AI 研判风险评分、影响范围和闭环处置", "format", "PDF"),
                Map.of("id", "supply-chain", "name", "供应链分析报告",
                        "scenario", "AI 分析物流路径、供应商韧性和替代方案", "format", "PDF"),
                Map.of("id", "enterprise-dd", "name", "企业尽调报告",
                        "scenario", "AI 汇总企业主体、公开源事件和合作建议", "format", "PDF")
        );
    }

    @PostMapping("/reports/jobs")
    public ApiResponse<ReportJob> createReport(@Valid @RequestBody ReportRequest request) {
        String fmt = request.format() == null || request.format().isBlank() ? "PDF" : request.format().toUpperCase();
        ReportJob job = store.createReport(request.template(), request.language(), fmt, request.threshold());
        return ApiResponse.ok("报告生成任务已启动", job);
    }

    @GetMapping("/reports/jobs/{id}")
    public ApiResponse<?> reportJob(@PathVariable String id) {
        if (store.reportJob(id).isPresent()) {
            ReportJob advanced = store.advanceReport(id);
            return ApiResponse.ok(advanced);
        }
        return ApiResponse.fail("报告任务不存在");
    }

    @GetMapping("/reports/{id}/download")
    public ResponseEntity<byte[]> downloadReport(@PathVariable String id) throws IOException {
        syncPublicCrawlerRecords();
        ReportJob job = store.reportJob(id).orElse(null);
        String template = job == null ? "risk-assessment" : job.template();
        String language = job == null ? "中文" : job.language();
        String format = job == null ? "PDF" : job.format();
        if (format == null || format.isBlank()) format = "PDF";
        List<String> findings = store.aiReportLines(id, template, language);
        com.semirisk.common.ReportFileFactory.ReportFile report =
                com.semirisk.common.ReportFileFactory.build(id, template, language, format, findings);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(report.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + report.filename() + "\"")
                .body(report.body());
    }

    // -----------------------------------------------------------------
    // 风险事件处置
    // -----------------------------------------------------------------

    @PostMapping("/risk/events/{id}/assign")
    public ApiResponse<Map<String, Object>> assignRisk(@PathVariable String id,
                                                        @RequestBody(required = false) Map<String, String> body) {
        String owner = body == null ? "默认负责人" : body.getOrDefault("owner", "默认负责人");
        try {
            store.updateAlertStatus(id, "处理中");
        } catch (IllegalArgumentException ex) {
            log.debug("Unknown risk event ID '{}', continuing with deterministic response", id);
        }
        return ApiResponse.ok("负责人已指派", Map.of("id", id, "owner", owner, "status", "处理中"));
    }

    @PostMapping("/risk/events/{id}/dispatch-report")
    public ApiResponse<Map<String, Object>> dispatchReport(@PathVariable String id) {
        try {
            store.updateAlertStatus(id, "处理中");
        } catch (IllegalArgumentException ex) {
            log.debug("Unknown risk event ID '{}', continuing with deterministic response", id);
        }
        return ApiResponse.ok("处置报告已下发", Map.of("id", id, "status", "处理中"));
    }

    // -----------------------------------------------------------------
    // 风险评分
    // -----------------------------------------------------------------

    @GetMapping("/risk-score/today")
    public ApiResponse<DailyRiskSnapshot> todayRiskScore() {
        syncPublicCrawlerRecords();
        return ApiResponse.ok(store.dailyRiskSnapshot());
    }

    @PostMapping("/risk-score/recalculate")
    public ApiResponse<DailyRiskSnapshot> recalculateRiskScore() {
        syncPublicCrawlerRecords();
        return ApiResponse.ok("AI 风险自动测算完成", store.dailyRiskSnapshot());
    }

    // -----------------------------------------------------------------
    // 爬虫同步（定时 + 手动）
    // -----------------------------------------------------------------

    /** 定时任务：每分钟主动拉取 data-service 最新信号，写入网关风险快照。 */
    @Scheduled(fixedDelayString = "${semirisk.crawler-sync.tick-ms:60000}", initialDelayString = "${semirisk.crawler-sync.initial-delay-ms:15000}")
    public void scheduledCrawlerSync() {
        try {
            syncPublicCrawlerRecords();
        } catch (Exception ex) {
            log.warn("Scheduled crawler sync failed: {}", ex.getMessage());
        }
    }

    // -----------------------------------------------------------------
    // 私有方法
    // -----------------------------------------------------------------

    private void syncPublicCrawlerRecords() {
        Instant now = Instant.now();
        if (lastCrawlerSync.isAfter(now.minusSeconds(crawlerSyncCooldownSeconds))) {
            return;
        }
        lastCrawlerSync = now;
        List<CrawlerSignal> records = publicCrawlerClient.today();
        store.refreshDailyRiskRecords(records);
        knowledgeSearchIndexService.sync(records);
    }

    public record ReportRequest(@NotBlank String template, @NotBlank String language, @NotBlank String format, int threshold) {
    }
}
