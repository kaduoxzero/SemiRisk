package com.semirisk.feign;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;

import java.util.Map;

@FeignClient(name = "semirisk-risk-service", fallback = RiskServiceClientFallback.class)
public interface RiskServiceClient {

    @GetMapping("/api/risk-score/today")
    Map<String, Object> todayScore();

    @PostMapping("/api/risk-score/recalculate")
    Map<String, Object> recalculate();
}
