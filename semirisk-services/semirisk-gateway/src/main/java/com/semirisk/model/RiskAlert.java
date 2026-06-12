package com.semirisk.model;

import java.time.Instant;

public record RiskAlert(String id, Instant time, String level, String title, String source, String sourceUrl, String status, String target) {
    public RiskAlert(String id, Instant time, String level, String title, String source, String status, String target) {
        this(id, time, level, title, source, "", status, target);
    }
}
