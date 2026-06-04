package com.semirisk.risk;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

@Service
public class RiskComputationService {

    private final RestClient restClient;
    private final AtomicReference<RiskSnapshot> snapshot = new AtomicReference<>();

    public RiskComputationService(@Value("${semirisk.data-service.url}") String dataServiceUrl) {
        this.restClient = RestClient.builder().baseUrl(dataServiceUrl).build();
    }

    @PostConstruct
    public void init() {
        recalculate();
    }

    @Scheduled(cron = "0 10 0 * * *")
    public void recalculate() {
        List<Map<String, Object>> records = fetchCrawlerRecords().stream()
                .filter(record -> "OK".equalsIgnoreCase(String.valueOf(record.getOrDefault("status", "OK"))))
                .toList();
        if (records.isEmpty()) {
            snapshot.set(new RiskSnapshot(0, "待采集", "公开源暂未成功采集，风险测算等待 data-service 刷新。", List.of(), Instant.now()));
            return;
        }
        int max = records.stream()
                .map(record -> record.get("riskScore"))
                .filter(Number.class::isInstance)
                .map(Number.class::cast)
                .mapToInt(Number::intValue)
                .max()
                .orElse(0);
        int score = Math.min(96, Math.max(35, max + records.size() * 3));
        String level = score >= 80 ? "高危" : score >= 60 ? "中危" : "低危";
        String summary = "AI 自动测算结果：" + level + "，今日风险分 " + score + "，由公开网站爬虫记录和规则评分共同驱动。";
        List<String> reasons = records.stream()
                .limit(5)
                .map(record -> String.valueOf(record.getOrDefault("title", "供应链情报信号")))
                .toList();
        snapshot.set(new RiskSnapshot(score, level, summary, reasons, Instant.now()));
    }

    public RiskSnapshot snapshot() {
        return snapshot.get();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> fetchCrawlerRecords() {
        try {
            Map<String, Object> response = restClient.get()
                    .uri("/api/crawler/records/today")
                    .retrieve()
                    .body(Map.class);
            Object data = response == null ? null : response.get("data");
            if (data instanceof List<?> list) {
                return (List<Map<String, Object>>) list;
            }
        } catch (Exception ignored) {
            // Local fallback keeps the service runnable when the data service is not up.
        }
        return List.of();
    }
}
