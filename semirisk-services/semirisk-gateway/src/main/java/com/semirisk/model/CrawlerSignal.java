package com.semirisk.model;

import java.time.Instant;

public record CrawlerSignal(String id, String source, String title, String dimension, int riskScore, Instant fetchedAt, String sourceUrl, String status) {
}
