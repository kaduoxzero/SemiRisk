package com.semirisk.feign;

import org.springframework.stereotype.Component;
import java.util.Map;

@Component
public class RiskServiceClientFallback implements RiskServiceClient {

    @Override
    public Map<String, Object> todayScore() {
        return Map.of("score", 0, "level", "待采集", "message", "risk-service 暂不可达（熔断降级）");
    }

    @Override
    public Map<String, Object> recalculate() {
        return Map.of("score", 0, "message", "risk-service 暂不可达");
    }
}
