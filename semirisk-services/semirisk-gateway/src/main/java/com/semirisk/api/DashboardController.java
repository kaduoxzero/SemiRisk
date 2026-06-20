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
