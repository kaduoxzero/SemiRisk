package com.semirisk.feign;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.Map;

@FeignClient(name = "semirisk-ai-service", fallback = AiServiceClientFallback.class)
public interface AiServiceClient {

    @GetMapping("/api/ai/reports/latest")
    Map<String, Object> latestReport();

    @PostMapping("/api/ai/reports/refresh")
    Map<String, Object> refreshReport();

    @PostMapping("/api/ai/models/config")
    Map<String, Object> saveConfig(@RequestBody Map<String, String> config);
}
