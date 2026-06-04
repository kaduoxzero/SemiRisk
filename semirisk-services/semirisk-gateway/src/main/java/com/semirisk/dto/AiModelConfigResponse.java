package com.semirisk.dto;

import java.time.Instant;

public record AiModelConfigResponse(
        String model,
        String endpoint,
        String maskedApiKey,
        boolean configured,
        Instant updatedAt
) {
}
