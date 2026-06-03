package com.semirisk.ai;

import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class AiConfigService {

    private final Map<String, AiModelConfig> configs = new ConcurrentHashMap<>();

    public AiModelConfig save(String model, String endpoint, String apiKey) {
        AiModelConfig config = new AiModelConfig(model, endpoint, mask(apiKey), apiKey != null && !apiKey.isBlank(), Instant.now());
        configs.put(model, config);
        return config;
    }

    public Map<String, AiModelConfig> all() {
        return Map.copyOf(configs);
    }

    private String mask(String key) {
        if (key == null || key.length() < 8) {
            return "未配置";
        }
        return key.substring(0, 4) + "****" + key.substring(key.length() - 4);
    }

    public record AiModelConfig(String model, String endpoint, String maskedApiKey, boolean configured, Instant updatedAt) {
    }
}

