package com.semirisk.dto;

import java.util.List;
import java.util.Map;

public record EnterpriseProfileResponse(
        String name,
        String creditCode,
        String cooperationYears,
        String industry,
        int riskScore,
        String creditLevel,
        Map<String, String> business,
        List<Integer> radar,
        List<String> topology,
        List<String> events
) {
}
