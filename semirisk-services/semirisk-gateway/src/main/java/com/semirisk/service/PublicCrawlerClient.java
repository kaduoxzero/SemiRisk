package com.semirisk.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@Service
public class PublicCrawlerClient {

    private final RestClient restClient;

    public PublicCrawlerClient(@Value("${semirisk.data-service.url}") String dataServiceUrl) {
        this.restClient = RestClient.builder().baseUrl(dataServiceUrl).build();
    }

    @SuppressWarnings("unchecked")
    public List<SemiRiskStore.CrawlerSignal> today() {
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
        } catch (Exception ignored) {
            // The UI will show a clear no-data state instead of fabricated live values.
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
