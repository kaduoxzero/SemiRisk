package com.semirisk.data;

import java.time.Instant;

public record CrawlerRecord(
        String id,
        String source,
        String sourceUrl,
        String title,
        String dimension,
        String riskSignal,
        int riskScore,
        Instant fetchedAt,
        String status
) {
}
