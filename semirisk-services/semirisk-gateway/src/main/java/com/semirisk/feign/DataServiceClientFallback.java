package com.semirisk.feign;

import org.springframework.stereotype.Component;
import java.util.Map;

@Component
public class DataServiceClientFallback implements DataServiceClient {

    @Override
    public Map<String, Object> recentRecords() {
        return Map.of("data", java.util.List.of(), "message", "data-service 暂不可达（熔断降级）");
    }

    @Override
    public Map<String, Object> refresh() {
        return Map.of("data", java.util.List.of(), "message", "data-service 暂不可达（熔断降级）");
    }
}
