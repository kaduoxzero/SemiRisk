package com.semirisk.data;

import java.time.Instant;

public record CrawlerRecord(
        String id,
        String source,
        String title,
        String riskSignal,
        int riskScore,
        Instant fetchedAt
) {
}

