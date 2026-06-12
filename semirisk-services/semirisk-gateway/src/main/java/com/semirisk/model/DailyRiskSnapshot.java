package com.semirisk.model;

import java.time.Instant;
import java.util.List;

public record DailyRiskSnapshot(int score, String level, String summary, List<CrawlerSignal> signals, Instant calculatedAt) {
}
