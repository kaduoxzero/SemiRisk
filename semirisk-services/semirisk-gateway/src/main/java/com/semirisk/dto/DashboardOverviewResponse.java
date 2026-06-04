package com.semirisk.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record DashboardOverviewResponse(
        List<Map<String, Object>> kpis,
        List<Map<String, Object>> hotspots,
        List<RiskAlertResponse> ranking,
        List<Map<String, Object>> materials,
        List<String> stages,
        String aiSummary,
        Object dailyRisk,
        Instant refreshedAt
) {
}
