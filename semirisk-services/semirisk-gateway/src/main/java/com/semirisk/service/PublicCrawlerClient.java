package com.semirisk.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 公开源爬虫客户端：调用 data-service 拉取 RSS/Atom 信号。
 * 增加超时缓冲（2s→5s connect, 4s→10s read）和重试机制，应对中转网络波动。
 */
@Service
public class PublicCrawlerClient {

    private final RestClient restClient;

    public PublicCrawlerClient(@Value("${semirisk.data-service.url}") String dataServiceUrl) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        // 增加超时：中转网络可能延迟较大
        requestFactory.setConnectTimeout(Duration.ofSeconds(5));
        requestFactory.setReadTimeout(Duration.ofSeconds(10));
        this.restClient = RestClient.builder()
                .baseUrl(dataServiceUrl)
                .requestFactory(requestFactory)
                .build();
    }

    @SuppressWarnings("unchecked")
    public List<SemiRiskStore.CrawlerSignal> today() {
        // 重试 2 次，间隔 1s，提高成功率
        for (int attempt = 0; attempt < 2; attempt++) {
            if (attempt > 0) {
                try { Thread.sleep(1000); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); return List.of(); }
            }
            List<SemiRiskStore.CrawlerSignal> result = fetchSignals();
            if (!result.isEmpty()) {
                return result;
            }
        }
        return List.of();
    }

    @SuppressWarnings("unchecked")
    private List<SemiRiskStore.CrawlerSignal> fetchSignals() {
        try {
            Map<String, Object> response = restClient.get()
                    .uri("/api/crawler/records/recent")
                    .retrieve()
                    .body(Map.class);
            Object data = response == null ? null : response.get("data");
            if (data instanceof List<?> list) {
                return list.stream()
                        .filter(Map.class::isInstance)
                        .map(item -> toSignal((Map<String, Object>) item))
                        .toList();
            }
        } catch (Exception ex) {
            // 网络不可达或 data-service 不可用，返回空列表
        }
        return List.of();
    }

    private SemiRiskStore.CrawlerSignal toSignal(Map<String, Object> item) {
        return new SemiRiskStore.CrawlerSignal(
                text(item, "id", "CR-UNKNOWN"),
                text(item, "source", "公开源"),
                text(item, "title", "公开源暂无标题"),
                text(item, "dimension", "供应链"),
                score(item.get("riskScore")),
                instant(text(item, "fetchedAt", Instant.now().toString())),
                text(item, "sourceUrl", ""),
                text(item, "status", "UNKNOWN")
        );
    }

    private String text(Map<String, Object> item, String key, String fallback) {
        Object value = item.get(key);
        return value == null ? fallback : String.valueOf(value);
    }

    private int score(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (Exception ignored) {
            return 0;
        }
    }

    private Instant instant(String value) {
        try {
            return Instant.parse(value);
        } catch (Exception ignored) {
            return Instant.now();
        }
    }
}
