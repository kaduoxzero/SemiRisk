package com.semirisk.risk;

import java.time.Instant;
import java.util.List;

public record RiskSnapshot(
        int score,
        String level,
        String summary,
        List<String> reasons,
        Instant calculatedAt
) {
}

