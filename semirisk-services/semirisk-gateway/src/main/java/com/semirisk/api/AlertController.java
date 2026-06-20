package com.semirisk.api;

import com.semirisk.model.RiskAlert;
import com.semirisk.service.SemiRiskStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 告警管理 API：列表查询、状态更新、批量处置。
 */
@RestController
@RequestMapping("/api/alerts")
public class AlertController {

    private static final Logger log = LoggerFactory.getLogger(AlertController.class);

    private final SemiRiskStore store;

    public AlertController(SemiRiskStore store) {
        this.store = store;
    }

    @GetMapping
    public ApiResponse<List<?>> alerts(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String level,
            @RequestParam(required = false) String status) {
        String kw = keyword == null ? "" : keyword.trim();
        List<RiskAlert> publicAlerts = filterAlerts(store.publicSignalAlerts(), kw, level, status);
        if (!publicAlerts.isEmpty()) {
            return ApiResponse.ok((List<?>) publicAlerts);
        }
        if (!store.dailyRiskSnapshot().signals().isEmpty() || "待采集".equals(store.dailyRiskSnapshot().level())) {
            return ApiResponse.ok(List.of());
        }
        return ApiResponse.ok(List.<RiskAlert>of());
    }

    @PutMapping("/{id}/ignore")
    public ApiResponse<RiskAlert> ignoreAlert(@PathVariable String id) {
        RiskAlert alert = store.updateAlertStatus(id, "已忽略");
        return ApiResponse.ok("告警已忽略", alert);
    }

    @PostMapping("/batch-process")
    public ApiResponse<Map<String, Object>> batchProcess(@RequestBody BatchRequest request) {
        request.ids().forEach(id -> store.updateAlertStatus(id, "处理中"));
        return ApiResponse.ok("批量处理指令下发成功", Map.of("processed", request.ids().size()));
    }

    private List<RiskAlert> filterAlerts(List<RiskAlert> alerts, String keyword, String level, String status) {
        return alerts.stream()
                .filter(alert -> keyword == null || keyword.isBlank() || alert.title().contains(keyword) || alert.source().contains(keyword))
                .filter(alert -> level == null || level.isBlank() || alert.level().equals(level))
                .filter(alert -> status == null || status.isBlank() ? !"已忽略".equals(alert.status()) : alert.status().equals(status))
                .toList();
    }

    public record BatchRequest(List<String> ids) {}
}
