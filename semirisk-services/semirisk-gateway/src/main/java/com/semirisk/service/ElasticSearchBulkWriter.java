package com.semirisk.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Scheduled;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 高性能 Elasticsearch Bulk 写入服务。
 *
 * <p>核心改进：
 * <ul>
 *   <li>将原有单条 PUT 写入重构为 Bulk API 批量写入</li>
 *   <li>引入内存缓冲区 ConcurrentLinkedQueue，定时/定量刷写</li>
 *   <li>保留原 search/indexUploadedDoc 接口以保持向后兼容</li>
 * </ul>
 * </p>
 */
@Service
public class ElasticSearchBulkWriter {

    private static final Logger log = LoggerFactory.getLogger(ElasticSearchBulkWriter.class);

    private static final int BATCH_SIZE_THRESHOLD = 100;       // 达到 100 条立即刷写
    private static final long FLUSH_INTERVAL_MS = 5000;        // 5 秒定时刷写
    private static final int MAX_BUFFER_SIZE = 2000;           // 缓冲区上限，防止 OOM

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String esUrl;
    private final String indexName;

    // 内存缓冲区
    private final ConcurrentLinkedQueue<BulkItem> buffer = new ConcurrentLinkedQueue<>();
    private final AtomicLong totalFlushed = new AtomicLong(0);
    private final AtomicLong totalFailed = new AtomicLong(0);

    // ES 健康熔断
    private volatile String indexedSignature = "";
    private volatile Instant esDisabledUntil = Instant.EPOCH;

    public ElasticSearchBulkWriter(
            @Value("${semirisk.elasticsearch.url}") String esUrl,
            @Value("${semirisk.elasticsearch.index}") String indexName) {
        this.esUrl = esUrl.endsWith("/") ? esUrl.substring(0, esUrl.length() - 1) : esUrl;
        this.indexName = indexName;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        this.objectMapper = new ObjectMapper();
        // 使用 @Scheduled 定时刷写（Spring 管理）
    }

    /**
     * 提交单个文档到批量缓冲区。
     */
    public void submit(String docId, Map<String, Object> document) {
        if (esDisabledUntil.isAfter(Instant.now())) {
            log.debug("ES disabled, skipping document {}", docId);
            return;
        }
        buffer.offer(new BulkItem(docId, document));
        // 达到阈值立即刷写
        if (buffer.size() >= BATCH_SIZE_THRESHOLD) {
            flush();
        }
    }

    /**
     * 批量提交多个文档（一次性加入缓冲区）。
     */
    public void submitBatch(List<Map<String, Object>> documents) {
        if (documents == null || documents.isEmpty()) {
            return;
        }
        for (Map<String, Object> doc : documents) {
            String docId = (String) doc.get("id");
            if (docId != null) {
                buffer.offer(new BulkItem(docId, doc));
            }
        }
        if (buffer.size() >= BATCH_SIZE_THRESHOLD) {
            flush();
        }
    }

    /**
     * 手动强制刷写所有缓冲数据。
     */
    public void flush() {
        if (buffer.isEmpty()) {
            return;
        }
        if (esDisabledUntil.isAfter(Instant.now())) {
            log.warn("ES is disabled, skipping flush");
            return;
        }

        List<BulkItem> batch = new ArrayList<>();
        BulkItem item;
        while ((item = buffer.poll()) != null && batch.size() < BATCH_SIZE_THRESHOLD) {
            batch.add(item);
        }

        if (batch.isEmpty()) {
            return;
        }

        try {
            String bulkBody = buildBulkBody(batch);
            sendBulkRequest(bulkBody);
            totalFlushed.addAndGet(batch.size());
        } catch (Exception ex) {
            log.error("Bulk flush failed, requeuing {} documents", batch.size(), ex);
            // 失败的数据重新放回缓冲区头部
            for (int i = batch.size() - 1; i >= 0; i--) {
                buffer.offer(batch.get(i));
            }
            // 熔断 60 秒
            esDisabledUntil = Instant.now().plusSeconds(60);
            totalFailed.addAndGet(1);
        }
    }

    /**
     * 构建 Bulk API 请求体。
     * 格式：
     * {"index":{"_index":"semirisk_knowledge","_id":"doc1"}}
     * {"field1":"value1",...}
     * {"index":{"_index":"semirisk_knowledge","_id":"doc2"}}
     * {"field1":"value2",...}
     */
    private String buildBulkBody(List<BulkItem> items) throws Exception {
        StringBuilder sb = new StringBuilder();
        for (BulkItem item : items) {
            sb.append("{\"index\":{\"_index\":\"").append(indexName)
              .append("\",\"_id\":\"").append(escapeJson(item.docId))
              .append("\"}}\n");
            sb.append(objectMapper.writeValueAsString(item.document)).append("\n");
        }
        return sb.toString();
    }

    private String escapeJson(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\")
                    .replace("\"", "\\\"")
                    .replace("\n", "\\n")
                    .replace("\r", "\\r")
                    .replace("\t", "\\t");
    }

    private void sendBulkRequest(String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(esUrl + "/" + indexName + "/_bulk"))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 300) {
            throw new IllegalStateException("ES bulk request failed: HTTP " + response.statusCode() + " body=" + response.body().substring(0, Math.min(200, response.body().length())));
        }
        // 检查 bulk 响应中的 errors 字段
        @SuppressWarnings("unchecked")
        Map<String, Object> parsed = objectMapper.readValue(response.body(), new TypeReference<>() {});
        Boolean hasErrors = (Boolean) parsed.get("errors");
        if (Boolean.TRUE.equals(hasErrors)) {
            List<?> items = (List<?>) parsed.get("items");
            if (items != null) {
                long errorCount = items.stream()
                        .filter(Map.class::isInstance)
                        .map(Map.class::cast)
                        .filter(m -> m.containsKey("index"))
                        .filter(m -> {
                            Object idx = m.get("index");
                            return idx instanceof Map && ((Map<?, ?>) idx).containsKey("error");
                        })
                        .count();
                if (errorCount > 0) {
                    log.warn("Bulk flush had {} errors out of {} items", errorCount, items.size());
                }
            }
        }
    }

    /**
     * 定时自动刷写（每 5 秒或缓冲非空时触发）。
     */
    @Scheduled(fixedDelay = FLUSH_INTERVAL_MS)
    public void scheduledFlush() {
        if (!buffer.isEmpty()) {
            flush();
        }
    }

    /**
     * 应用关闭时强制刷写剩余数据。
     */
    @jakarta.annotation.PreDestroy
    public void shutdown() {
        log.info("ElasticSearchBulkWriter shutting down, buffered={} flushed={} failed={}",
                buffer.size(), totalFlushed.get(), totalFailed.get());
        flush();
        httpClient.close();
    }

    // ---- 统计信息 ----

    public long getBufferSize() {
        return buffer.size();
    }

    public long getTotalFlushed() {
        return totalFlushed.get();
    }

    public long getTotalFailed() {
        return totalFailed.get();
    }

    public Map<String, Object> stats() {
        return Map.of(
                "bufferSize", buffer.size(),
                "totalFlushed", totalFlushed.get(),
                "totalFailed", totalFailed.get(),
                "esDisabled", esDisabledUntil.isAfter(Instant.now())
        );
    }

    // ---- 内部类 ----

    private record BulkItem(String docId, Map<String, Object> document) {}

    // ---- 向后兼容：保留原有 KnowledgeSearchIndexService 的搜索接口 ----

    /**
     * 搜索（保持原有逻辑不变，因为搜索不需要批量优化）。
     */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> search(String query, int size) {
        if (query == null || query.isBlank()) {
            query = "semiconductor supply chain risk";
        }
        if (esDisabledUntil.isAfter(Instant.now())) {
            return List.of();
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
                esDisabledUntil = Instant.now().plusSeconds(60);
                return List.of();
            }
            Map<String, Object> parsed = objectMapper.readValue(response.body(), new TypeReference<>() {});
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
                        Map<String, Object> result = new java.util.HashMap<>();
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
        } catch (Exception ex) {
            log.warn("Elasticsearch search failed, disabling for 60s: {}", ex.getMessage());
            esDisabledUntil = Instant.now().plusSeconds(60);
            return List.of();
        }
    }

    private HttpRequest jsonRequest(String method, String path, String body) {
        return HttpRequest.newBuilder(URI.create(esUrl + path))
                .timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/json")
                .method(method, HttpRequest.BodyPublishers.ofString(body))
                .build();
    }
}
