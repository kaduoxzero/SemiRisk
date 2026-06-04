package com.semirisk.dto;

import java.time.Instant;

public record RiskAlertResponse(
        String id,
        Instant time,
        String level,
        String title,
        String source,
        String status,
        String target
) {
}
