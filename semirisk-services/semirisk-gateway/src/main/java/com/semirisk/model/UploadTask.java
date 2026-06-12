package com.semirisk.model;

import java.time.Instant;
import java.util.List;

public record UploadTask(String id, String filename, long size, String status, Instant createdAt, int rows, List<String> warnings) {
}
