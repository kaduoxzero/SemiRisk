package com.semirisk.feign;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;

import java.util.Map;

@FeignClient(name = "semirisk-data-service", fallback = DataServiceClientFallback.class)
public interface DataServiceClient {

    @GetMapping("/api/crawler/records/recent")
    Map<String, Object> recentRecords();

    @PostMapping("/api/crawler/refresh")
    Map<String, Object> refresh();
}
