package com.semirisk.model;

import java.time.Instant;

public record AiModelConfig(String model, String endpoint, String maskedApiKey, boolean configured, Instant updatedAt) {
}
