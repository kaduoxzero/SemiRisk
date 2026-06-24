package com.semirisk.api;

import com.semirisk.model.RiskAlert;
import com.semirisk.service.AlertService;
import com.semirisk.service.SemiRiskStore;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/alerts")
public class AlertController {

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
        return ApiResponse.ok((List<?>) publicAlerts);
    }

    @PutMapping("/{id}/ignore")
    public ApiResponse<RiskAlert> ignoreAlert(@PathVariable String id) {
        String currentStatus = store.currentAlertStatus(id);
        if (!AlertService.STATUS_UNHANDLED.equals(currentStatus)) {
            throw new IllegalArgumentException("当前状态为「" + currentStatus + "」，不能重复忽略");
        }
        RiskAlert alert = store.updateAlertStatus(id, AlertService.STATUS_IGNORED);
        return ApiResponse.ok("告警已忽略", alert);
    }

    @PutMapping("/{id}/restore")
    public ApiResponse<RiskAlert> restoreAlert(@PathVariable String id) {
        String currentStatus = store.currentAlertStatus(id);
        if (!AlertService.STATUS_IGNORED.equals(currentStatus)) {
            throw new IllegalArgumentException("只有已忽略告警可以恢复");
        }
        RiskAlert alert = store.updateAlertStatus(id, AlertService.STATUS_UNHANDLED);
        return ApiResponse.ok("告警已恢复为未处理", alert);
    }

    @PutMapping("/{id}/handle")
    public ApiResponse<RiskAlert> handleAlert(@PathVariable String id) {
        String currentStatus = store.currentAlertStatus(id);
        if (AlertService.STATUS_IGNORED.equals(currentStatus) || AlertService.STATUS_HANDLED.equals(currentStatus)) {
            throw new IllegalArgumentException("当前状态不能标记为已处理");
        }
        RiskAlert alert = store.updateAlertStatus(id, AlertService.STATUS_HANDLED);
        return ApiResponse.ok("告警已标记为已处理", alert);
    }

    @PostMapping("/batch-process")
    public ApiResponse<Map<String, Object>> batchProcess(@RequestBody BatchRequest request) {
        List<String> ids = request == null || request.ids() == null ? List.of() : request.ids();
        int processed = 0;
        int skipped = 0;
        for (String id : ids) {
            if (store.isAlertActionable(id)) {
                store.updateAlertStatus(id, AlertService.STATUS_PROCESSING);
                processed++;
            } else {
                skipped++;
            }
        }
        return ApiResponse.ok("批量处理完成", Map.of("processed", processed, "skipped", skipped));
    }

    private List<RiskAlert> filterAlerts(List<RiskAlert> alerts, String keyword, String level, String status) {
        return alerts.stream()
                .filter(alert -> keyword == null || keyword.isBlank() || alert.title().contains(keyword) || alert.source().contains(keyword))
                .filter(alert -> level == null || level.isBlank() || alert.level().equals(level))
                .filter(alert -> status == null || status.isBlank()
                        ? !AlertService.STATUS_IGNORED.equals(alert.status())
                        : alert.status().equals(status))
                .toList();
    }

    public record BatchRequest(List<String> ids) {}
}
