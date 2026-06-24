package com.semirisk.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;

/**
 * 向量检索服务（Phase 3: Vector RAG）。
 *
 * <p>基于 Elasticsearch dense_vector 实现向量检索，
 * 与关键词检索结合形成混合检索（Hybrid Search）。</p>
 *
 * <p>使用方式：
 * <ul>
 *   <li>索引文档时同时存储文本向量和原文</li>
 *   <li>查询时使用 RRF（Reciprocal Rank Fusion）融合关键词分和向量相似度分</li>
 * </ul>
 * </p>
 */
@Service
public class VectorSearchService {

    private static final Logger log = LoggerFactory.getLogger(VectorSearchService.class);
    private static final int VECTOR_DIM = 768;  // 默认向量维度（适配大多数 embedding 模型）

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final ElasticSearchBulkWriter bulkWriter;
    private final String esUrl;
    private final String indexName;
    private final String embeddingEndpoint;
    private final String embeddingApiKey;

    public VectorSearchService(
            ElasticSearchBulkWriter bulkWriter,
            @Value("${semirisk.elasticsearch.url:http://127.0.0.1:9200}") String esUrl,
            @Value("${semirisk.elasticsearch.index:semirisk_knowledge}") String indexName,
            @Value("${semirisk.embedding.endpoint:}") String embeddingEndpoint,
            @Value("${semirisk.embedding.api-key:}") String embeddingApiKey) {
        this.bulkWriter = bulkWriter;
        this.esUrl = esUrl.endsWith("/") ? esUrl.substring(0, esUrl.length() - 1) : esUrl;
        this.indexName = indexName;
        this.embeddingEndpoint = embeddingEndpoint;
        this.embeddingApiKey = embeddingApiKey;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        this.objectMapper = new ObjectMapper();
        initVectorMapping();
    }

    /**
     * 初始化向量索引 mapping（dense_vector 字段）。
     */
    private void initVectorMapping() {
        if (embeddingEndpoint == null || embeddingEndpoint.isBlank()) {
            log.info("Embedding endpoint not configured, vector search disabled");
            return;
        }
        try {
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
                          "fetchedAt": {"type": "date"},
                          "embedding": {
                            "type": "dense_vector",
                            "dims": %d,
                            "index": true,
                            "similarity": "cosine"
                          }
                        }
                      }
                    }
                    """.formatted(VECTOR_DIM);

            HttpRequest request = HttpRequest.newBuilder(URI.create(esUrl + "/" + indexName + "/_mapping"))
                    .timeout(Duration.ofSeconds(5))
                    .header("Content-Type", "application/json")
                    .PUT(HttpRequest.BodyPublishers.ofString(mapping))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200 || response.statusCode() == 400) {
                log.info("Vector index mapping initialized/exists for {}", indexName);
            }
        } catch (Exception e) {
            log.warn("Failed to init vector mapping (non-critical): {}", e.getMessage());
        }
    }

    /**
     * 获取文本向量。
     */
    public float[] getEmbedding(String text) {
        if (embeddingEndpoint == null || embeddingEndpoint.isBlank()) {
            return null;
        }
        try {
            Map<String, Object> body = Map.of(
                    "model", "default",
                    "input", List.of(truncate(text, 8191))
            );
            HttpRequest request = HttpRequest.newBuilder(URI.create(embeddingEndpoint))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + embeddingApiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                return null;
            }

            Map<String, Object> parsed = objectMapper.readValue(response.body(), new TypeReference<>() {});
            List<Object> embeddings = (List<Object>) ((Map<String, Object>) parsed.get("data")).get("embedding");
            if (embeddings == null) return null;

            float[] vector = new float[VECTOR_DIM];
            for (int i = 0; i < Math.min(VECTOR_DIM, embeddings.size()); i++) {
                vector[i] = ((Number) embeddings.get(i)).floatValue();
            }
            return vector;
        } catch (Exception e) {
            log.warn("Embedding generation failed: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 混合检索：关键词 + 向量 RRF 融合。
     */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> hybridSearch(String query, float[] queryVector, int size) {
        if (queryVector == null) {
            // 无向量则回退到纯关键词搜索
            return keywordSearch(query, size);
        }

        try {
            // 使用 ES _search 的 hybrid query (RRF)
            Map<String, Object> vectorQuery = Map.of(
                    "field", "embedding",
                    "query_vector", queryVector,
                    "k", 100,
                    "boost", "1.0"
            );

            Map<String, Object> boolQuery = Map.of(
                    "bool", Map.of(
                            "should", List.of(
                                    Map.of("multi_match", Map.of(
                                            "query", query,
                                            "fields", List.of("title^3", "content^2", "source", "dimension"),
                                            "type", "best_fields"
                                    )),
                                    vectorQuery
                            ),
                            "minimum_should_match", 1
                    )
            );

            Map<String, Object> body = Map.of(
                    "size", size,
                    "_source", Map.of("excludes", List.of("embedding")),
                    "query", boolQuery,
                    "rerank", Map.of(
                            "rrf", Map.of(
                                    "window_size", size,
                                    "rank_constants", Map.of("r1", 60.0, "r2", 60.0)
                            )
                    )
            );

            HttpRequest request = HttpRequest.newBuilder(URI.create(esUrl + "/" + indexName + "/_search"))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 300) {
                log.warn("Hybrid search failed: HTTP {}", response.statusCode());
                return keywordSearch(query, size);
            }

            Map<String, Object> parsed = objectMapper.readValue(response.body(), new TypeReference<>() {});
            Object hitsObj = ((Map<String, Object>) parsed.getOrDefault("hits", Map.of())).get("hits");
            if (!(hitsObj instanceof List<?> hits)) {
                return List.of();
            }

            return hits.stream()
                    .filter(Map.class::isInstance)
                    .map(hit -> {
                        Map<String, Object> h = (Map<String, Object>) hit;
                        Map<String, Object> source = (Map<String, Object>) h.getOrDefault("_source", Map.of());
                        source.put("searchScore", h.getOrDefault("_score", 0));
                        source.put("searchEngine", "Hybrid(ES+Vector)");
                        return source;
                    })
                    .toList();
        } catch (Exception e) {
            log.warn("Hybrid search failed, falling back to keyword search: {}", e.getMessage());
            return keywordSearch(query, size);
        }
    }

    /**
     * 纯关键词搜索（回退路径）。
     */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> keywordSearch(String query, int size) {
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
            HttpRequest request = HttpRequest.newBuilder(URI.create(esUrl + "/" + indexName + "/_search"))
                    .timeout(Duration.ofSeconds(5))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 300) {
                return List.of();
            }

            Map<String, Object> parsed = objectMapper.readValue(response.body(), new TypeReference<>() {});
            Object hitsObj = ((Map<String, Object>) parsed.getOrDefault("hits", Map.of())).get("hits");
            if (!(hitsObj instanceof List<?> hits)) {
                return List.of();
            }

            return hits.stream()
                    .filter(Map.class::isInstance)
                    .map(hit -> {
                        Map<String, Object> h = (Map<String, Object>) hit;
                        Map<String, Object> source = (Map<String, Object>) h.getOrDefault("_source", Map.of());
                        return source;
                    })
                    .toList();
        } catch (Exception e) {
            return List.of();
        }
    }

    /**
     * 索引文档（含向量）。
     */
    public void indexWithVector(String docId, Map<String, Object> document, float[] vector) {
        if (vector == null) {
            bulkWriter.submit(docId, document);
            return;
        }
        Float[] boxed = new Float[vector.length];
        for (int i = 0; i < vector.length; i++) {
            boxed[i] = vector[i];
        }
        document.put("embedding", java.util.List.of(boxed));
        bulkWriter.submit(docId, document);
    }

    private String truncate(String value, int max) {
        if (value == null) return "";
        return value.length() <= max ? value : value.substring(0, max);
    }
}
