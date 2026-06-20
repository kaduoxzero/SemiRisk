package com.semirisk.api;

import com.semirisk.model.CrawlerSignal;
import com.semirisk.model.DailyRiskSnapshot;
import com.semirisk.model.ReportJob;
import com.semirisk.model.RiskAlert;
import com.semirisk.service.SemiRiskStore;
import com.semirisk.service.KnowledgeSearchIndexService;
import com.semirisk.service.PublicCrawlerClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Dashboard / 风险 / 报告 / GIS / 知识库 / AI 报告 — 只读查询类 API。
 */
@RestController
@RequestMapping("/api")
public class DashboardController {

    private static final Logger log = LoggerFactory.getLogger(DashboardController.class);

    private final SemiRiskStore store;
    private final PublicCrawlerClient publicCrawlerClient;
    private final KnowledgeSearchIndexService knowledgeSearchIndexService;

    @Value("${semirisk.crawler-sync.cooldown-seconds:60}")
    private int crawlerSyncCooldownSeconds;

    private volatile Instant lastCrawlerSync = Instant.EPOCH;

    public DashboardController(SemiRiskStore store, PublicCrawlerClient publicCrawlerClient,
                               KnowledgeSearchIndexService knowledgeSearchIndexService) {
        this.store = store;
        this.publicCrawlerClient = publicCrawlerClient;
        this.knowledgeSearchIndexService = knowledgeSearchIndexService;
    }

    @GetMapping("/dashboard/overview")
    public ApiResponse<Map<String, Object>> dashboard() {
        syncPublicCrawlerRecords();
        return ApiResponse.ok(store.dashboard());
    }

    @GetMapping("/risk/analysis")
    public ApiResponse<Map<String, Object>> riskAnalysis(@RequestParam(defaultValue = "24h") String window) {
        syncPublicCrawlerRecords();
        return ApiResponse.ok(store.riskAnalysis(window));
    }

    @GetMapping("/risk/events/{id}")
    public ApiResponse<Map<String, Object>> riskDetail(@PathVariable String id) {
        syncPublicCrawlerRecords();
        return ApiResponse.ok(store.riskDetail(id));
    }

    @GetMapping("/reports/templates")
    public ApiResponse<List<Map<String, String>>> reportTemplates() {
        return ApiResponse.ok(List.of(
                Map.of("id", "risk-assessment", "name", "风险评估报告", "scenario", "AI 研判风险评分、影响范围和闭环处置", "format", "PDF"),
                Map.of("id", "supply-chain", "name", "供应链分析报告", "scenario", "AI 分析物流路径、供应商韧性和替代方案", "format", "PDF"),
                Map.of("id", "enterprise-dd", "name", "企业尽调报告", "scenario", "AI 汇总企业主体、公开源事件和合作建议", "format", "PDF")
        ));
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
    public ResponseEntity<byte[]> downloadReport(@PathVariable String id) {
        syncPublicCrawlerRecords();
        ReportJob job = store.reportJob(id).orElse(null);
        String template = job == null ? "risk-assessment" : job.template();
        String language = job == null ? "中文" : job.language();
        String format = job == null ? "PDF" : job.format();
        if (format == null || format.isBlank()) format = "PDF";
        List<String> findings = store.aiReportLines(id, template, language);
        com.semirisk.common.ReportFileFactory.ReportFile report = com.semirisk.common.ReportFileFactory.build(id, template, language, format, findings);
        return ResponseEntity.ok()
                .contentType(org.springframework.http.MediaType.parseMediaType(report.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + report.filename() + "\"")
                .body(report.body());
    }

    @GetMapping("/alerts/counts")
    public ApiResponse<Map<String, Long>> alertCounts() {
        syncPublicCrawlerRecords();
        Map<String, Long> counts = new java.util.HashMap<>();
        store.publicSignalAlerts().stream()
                .filter(alert -> !"已忽略".equals(alert.status()))
                .forEach(alert -> counts.merge(alert.level(), 1L, Long::sum));
        return ApiResponse.ok(counts);
    }

    @GetMapping("/gis/map")
    public ApiResponse<Map<String, Object>> gis(@RequestParam(required = false) String layers) {
        syncPublicCrawlerRecords();
        return ApiResponse.ok(store.gis(layers));
    }

    @GetMapping("/knowledge/search")
    public ApiResponse<Map<String, Object>> knowledge(@RequestParam(required = false) String query) {
        syncPublicCrawlerRecords();
        List<Map<String, Object>> indexedResults = knowledgeSearchIndexService.search(query, 30);
        return ApiResponse.ok(store.knowledge(query, indexedResults));
    }

    @GetMapping("/ai/reports/latest")
    public ApiResponse<Map<String, Object>> latestAiReport() {
        syncPublicCrawlerRecords();
        return ApiResponse.ok(store.latestAiReport());
    }

    @GetMapping("/risk-score/today")
    public ApiResponse<DailyRiskSnapshot> todayRiskScore() {
        syncPublicCrawlerRecords();
        return ApiResponse.ok(store.dailyRiskSnapshot());
    }

    // ---- internal ----

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

    /** 定时任务：每分钟主动拉取 data-service 最新信号。 */
    @Scheduled(fixedDelayString = "${semirisk.crawler-sync.tick-ms:60000}", initialDelayString = "${semirisk.crawler-sync.initial-delay-ms:15000}")
    public void scheduledCrawlerSync() {
        try {
            syncPublicCrawlerRecords();
        } catch (Exception ex) {
            log.warn("Scheduled crawler sync failed: {}", ex.getMessage());
        }
    }
}
