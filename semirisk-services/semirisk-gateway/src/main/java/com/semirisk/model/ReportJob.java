package com.semirisk.model;

import java.time.Instant;

public record ReportJob(String id, String template, String language, String format, int threshold, String status, int progress, String step, String downloadUrl, Instant createdAt) {
}
