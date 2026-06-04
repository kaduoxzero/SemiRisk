package com.semirisk.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.StringJoiner;

@Service
public class KnowledgeSearchIndexService {

    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final String esUrl;
    private final String indexName;
    private volatile String indexedSignature = "";

    public KnowledgeSearchIndexService(
            @Value("${semirisk.elasticsearch.url}") String esUrl,
            @Value("${semirisk.elasticsearch.index}") String indexName) {
        this.esUrl = esUrl.endsWith("/") ? esUrl.substring(0, esUrl.length() - 1) : esUrl;
        this.indexName = indexName;
    }

    public void sync(List<SemiRiskStore.CrawlerSignal> signals) {
        if (signals == null || signals.isEmpty()) {
            return;
        }
        String signature = signature(signals);
        if (signature.equals(indexedSignature)) {
            return;
        }
        try {
            ensureIndex();
            for (SemiRiskStore.CrawlerSignal signal : signals) {
                if (!"OK".equalsIgnoreCase(signal.status())) {
                    continue;
                }
                putDocument(signal);
            }
            indexedSignature = signature;
        } catch (Exception ignored) {
            // ES is optional locally; callers fall back to in-memory RAG.
        }
    }

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> search(String query, int size) {
        if (query == null || query.isBlank()) {
            query = "semiconductor supply chain risk";
        }
        try {
            Map<String, Object> body = Map.of(
                    "size", size,
                    "query", Map.of(
                            "multi_match", Map.of(
                                    "query", query,
                                    "fields", List.of("title^3", "content^2", "source", "dimension")
                            )
                    )
            );
            HttpRequest request = jsonRequest("POST", "/" + indexName + "/_search", objectMapper.writeValueAsString(body));
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 300) {
                return List.of();
            }
            Map<String, Object> parsed = objectMapper.readValue(response.body(), new TypeReference<>() {
            });
            Object hits = ((Map<String, Object>) parsed.getOrDefault("hits", Map.of())).get("hits");
            if (!(hits instanceof List<?> list)) {
                return List.of();
            }
            return list.stream()
                    .filter(Map.class::isInstance)
                    .map(item -> {
                        Map<String, Object> hit = (Map<String, Object>) item;
                        Map<String, Object> source = (Map<String, Object>) hit.getOrDefault("_source", Map.of());
                        double esScore = hit.getOrDefault("_score", 0) instanceof Number number ? number.doubleValue() : 0;
                        int similarity = (int) Math.max(1, Math.min(99, Math.round(esScore * 20)));
                        Map<String, Object> result = new HashMap<>();
                        result.put("id", String.valueOf(source.getOrDefault("id", hit.getOrDefault("_id", ""))));
                        result.put("title", String.valueOf(source.getOrDefault("title", "")));
                        result.put("source", String.valueOf(source.getOrDefault("source", "")));
                        result.put("sourceUrl", String.valueOf(source.getOrDefault("sourceUrl", "")));
                        result.put("url", String.valueOf(source.getOrDefault("sourceUrl", "")));
                        result.put("dimension", String.valueOf(source.getOrDefault("dimension", "")));
                        result.put("riskScore", source.getOrDefault("riskScore", 0));
                        result.put("fetchedAt", String.valueOf(source.getOrDefault("fetchedAt", "")));
                        result.put("summary", String.valueOf(source.getOrDefault("source", ""))
                                + " / " + source.getOrDefault("dimension", "")
                                + " / " + source.getOrDefault("fetchedAt", ""));
                        result.put("format", "WEB");
                        result.put("size", "公开网页");
                        result.put("similarity", similarity);
                        result.put("searchScore", esScore);
                        result.put("searchEngine", "Elasticsearch");
                        return result;
                    })
                    .toList();
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private void ensureIndex() throws Exception {
        String mapping = """
                {
                  "mappings": {
                    "properties": {
                      "id": {"type": "keyword"},
                      "title": {"type": "text"},
                      "content": {"type": "text"},
                      "source": {"type": "keyword"},
                      "sourceUrl": {"type": "keyword", "index": false},
                      "dimension": {"type": "keyword"},
                      "riskScore": {"type": "integer"},
                      "fetchedAt": {"type": "date"}
                    }
                  }
                }
                """;
        HttpResponse<String> response = httpClient.send(jsonRequest("PUT", "/" + indexName, mapping), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200 && response.statusCode() != 400) {
            throw new IllegalStateException("ES index creation failed: " + response.statusCode());
        }
    }

    private void putDocument(SemiRiskStore.CrawlerSignal signal) throws Exception {
        Map<String, Object> document = Map.of(
                "id", signal.id(),
                "title", signal.title(),
                "content", signal.title() + " " + signal.source() + " " + signal.dimension(),
                "source", signal.source(),
                "sourceUrl", signal.sourceUrl(),
                "dimension", signal.dimension(),
                "riskScore", signal.riskScore(),
                "fetchedAt", signal.fetchedAt().toString()
        );
        httpClient.send(jsonRequest("PUT", "/" + indexName + "/_doc/" + signal.id() + "?refresh=wait_for", objectMapper.writeValueAsString(document)), HttpResponse.BodyHandlers.discarding());
    }

    private HttpRequest jsonRequest(String method, String path, String body) {
        return HttpRequest.newBuilder(URI.create(esUrl + path))
                .timeout(Duration.ofSeconds(4))
                .header("Content-Type", "application/json")
                .method(method, HttpRequest.BodyPublishers.ofString(body))
                .build();
    }

    private String signature(List<SemiRiskStore.CrawlerSignal> signals) {
        StringJoiner joiner = new StringJoiner("|");
        signals.forEach(signal -> joiner.add(signal.id() + ":" + signal.riskScore() + ":" + signal.status()));
        return joiner.toString();
    }
}
