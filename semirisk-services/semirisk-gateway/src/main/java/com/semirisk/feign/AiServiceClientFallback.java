package com.semirisk.feign;

import org.springframework.stereotype.Component;
import java.util.Map;

@Component
public class AiServiceClientFallback implements AiServiceClient {

    @Override
    public Map<String, Object> latestReport() {
        return Map.of("pending", true, "modelStatus", "ai-service 暂不可达（熔断降级）");
    }

    @Override
    public Map<String, Object> refreshReport() {
        return Map.of("pending", true, "modelStatus", "ai-service 暂不可达（熔断降级）");
    }

    @Override
    public Map<String, Object> saveConfig(Map<String, String> config) {
        return Map.of("success", false, "message", "ai-service 暂不可达");
    }
}
