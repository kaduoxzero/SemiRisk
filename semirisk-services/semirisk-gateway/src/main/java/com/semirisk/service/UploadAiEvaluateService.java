package com.semirisk.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.semirisk.ai.AiStructuredOutputService;
import com.semirisk.model.AiModelConfig;
import com.semirisk.model.KnowledgeDocStatus;
import com.semirisk.model.UploadTask;
import com.semirisk.config.ThreadPoolConfig;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

/**
 * 上传文件 AI 评估服务（Phase 3 重构：结构化输出优先）。
 *
 * <p>变更：
 * <ul>
 *   <li>优先使用 AiStructuredOutputService 结构化评估</li>
 *   <li>回退到原有文本解析模式</li>
 *   <li>ES 写入改用 ElasticSearchBulkWriter</li>
 * </ul>
 * </p>
 */
@Service
public class UploadAiEvaluateService {

    private static final Logger log = LoggerFactory.getLogger(UploadAiEvaluateService.class);

    private static final int MAX_TEXT_CHARS = 5000;

    private final org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor evalExecutor;
    private final ElasticSearchBulkWriter elasticSearchBulkWriter;
    private final AiStructuredOutputService structuredOutputService;

    // AI 评估需要更长超时（60s），使用自定义 HttpClient
    private final HttpClient httpClient = ThreadPoolConfig.httpClient(java.time.Duration.ofSeconds(10));
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final JdbcTemplate jdbcTemplate;
    private final MinioStorageService minioStorageService;
    private final String defaultAiModel;
    private final String defaultAiEndpoint;
    private final String defaultAiApiKey;

    public UploadAiEvaluateService(@Qualifier("semiriskAiPool") org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor evalExecutor,
                                   JdbcTemplate jdbcTemplate,
                                   MinioStorageService minioStorageService,
                                   ElasticSearchBulkWriter elasticSearchBulkWriter,
                                   AiStructuredOutputService structuredOutputService,
                                   @Value("${semirisk.ai.default.model:}") String defaultAiModel,
                                   @Value("${semirisk.ai.default.endpoint:}") String defaultAiEndpoint,
                                   @Value("${semirisk.ai.default.api-key:}") String defaultAiApiKey) {
        this.evalExecutor = evalExecutor;
        this.jdbcTemplate = jdbcTemplate;
        this.minioStorageService = minioStorageService;
        this.elasticSearchBulkWriter = elasticSearchBulkWriter;
        this.structuredOutputService = structuredOutputService;
        this.defaultAiModel = defaultAiModel;
        this.defaultAiEndpoint = defaultAiEndpoint;
        this.defaultAiApiKey = defaultAiApiKey;
    }

    @jakarta.annotation.PreDestroy
    public void shutdown() {
        httpClient.close();
    }

    /** 异步评估上传文件，不阻塞上传接口返回。 */
    public void evaluateAsync(String taskId, String objectKey, String filename) {
        evalExecutor.submit(() -> {
            try {
                evaluate(taskId, objectKey, filename);
            } catch (Exception ex) {
                log.error("AI 评估失败: taskId={}, file={}", taskId, filename, ex);
                updateTaskStatus(taskId, "AI评估失败", 0);
            }
        });
    }

    private void evaluate(String taskId, String objectKey, String filename) throws Exception {
        String docId = "UPLOAD-" + taskId;
        // 状态：PROCESSING
        updateDocStatus(docId, KnowledgeDocStatus.PROCESSING.name());

        // 1. 提取文本
        String text = extractText(objectKey, filename);
        if (text == null || text.isBlank()) {
            updateDocStatus(docId, KnowledgeDocStatus.FAILED.name());
            updateTaskStatus(taskId, "文件内容为空", 0);
            return;
        }

        // 2. 截取到最大长度
        String truncated = text.length() > MAX_TEXT_CHARS ? text.substring(0, MAX_TEXT_CHARS) + "\n\n[... 文件过大，已截取前 5000 字符]" : text;

        // 3. Phase 3: 优先尝试结构化输出
        AiStructuredOutputService.AiEvalOutput structured = structuredOutputService.evaluateUploadStructured(filename, truncated);
        String dimension;
        int riskScore;
        String content;

        try {
            if (structured != null) {
                // 使用结构化输出结果
                dimension = structured.riskDimensions != null && !structured.riskDimensions.isEmpty()
                        ? structured.riskDimensions.get(0) : "综合";
                riskScore = structured.riskScore;
                content = buildContentFromStructured(structured);
                log.info("AI eval used structured output for taskId={}, score={}", taskId, riskScore);
            } else {
                // 回退到原有文本模式
                String prompt = """
                        你是一个供应链风险分析专家。请分析以下数据文件内容，生成结构化风险评估摘要。
                        数据文件: %s
                        数据内容:
                        %s
                        请按以下格式输出（不要使用 Markdown 格式）：
                        【数据概览】简述文件包含的字段、数据量级、主要数据类型
                        【风险识别】从数据中发现的供应链风险因素
                        【风险维度】给出主要风险维度
                        【风险评分】给出 0-100 的综合风险评分及理由
                        【处置建议】针对识别出的风险给出具体可操作的处置建议
                        """;
                String aiAnswer;
                try {
                    aiAnswer = callAi(String.format(prompt, filename, truncated));
                } catch (Exception ex) {
                    log.warn("AI 调用失败，使用本地摘要: {}", ex.getMessage());
                    aiAnswer = buildLocalSummary(filename, text);
                }
                Map<String, Object> result = parseAiResult(aiAnswer);
                dimension = (String) result.getOrDefault("dimension", "综合");
                riskScore = (int) result.getOrDefault("riskScore", 0);
                content = (String) result.getOrDefault("content", aiAnswer);
            }

            // 4. 写入 knowledge_doc
            insertKnowledgeDoc(docId, filename, content, dimension, riskScore, objectKey, KnowledgeDocStatus.SUCCESS.name());

            // 5. 索引到 Elasticsearch（批量写入）
            try {
                elasticSearchBulkWriter.submit(docId, Map.of(
                        "id", docId,
                        "title", filename,
                        "content", content,
                        "source", "用户上传",
                        "sourceUrl", objectKey,
                        "dimension", dimension,
                        "riskScore", riskScore,
                        "fetchedAt", Instant.now().toString()
                ));
            } catch (Exception ex) {
                log.warn("ES bulk submit failed (optional): {}", ex.getMessage());
            }

            // 6. 更新任务状态
            updateTaskStatus(taskId, "已入库", 1);
        } catch (Exception ex) {
            updateDocStatus(docId, KnowledgeDocStatus.FAILED.name());
            log.error("AI 评估失败: taskId={}, file={}", taskId, filename, ex);
            throw ex;
        }
    }

    /** 从结构化输出组装内容字符串。 */
    private String buildContentFromStructured(AiStructuredOutputService.AiEvalOutput structured) {
        StringBuilder sb = new StringBuilder();
        sb.append("【数据概览】").append(structured.dataOverview != null ? structured.dataOverview : "无").append("\n");
        sb.append("【风险识别】\n");
        if (structured.risksIdentified != null) {
            structured.risksIdentified.forEach(r -> sb.append("- ").append(r).append("\n"));
        }
        sb.append("【风险维度】").append(structured.riskDimensions != null ? String.join("、", structured.riskDimensions) : "综合").append("\n");
        sb.append("【风险评分】").append(structured.riskScore).append(" 分（").append(structured.scoreReason != null ? structured.scoreReason : "自动评分").append("）\n");
        sb.append("【处置建议】\n");
        if (structured.recommendations != null) {
            structured.recommendations.forEach(r -> sb.append("- [").append(r.getOrDefault("priority", "中")).append("] ").append(r.getOrDefault("action", "")).append("\n"));
        }
        return sb.toString();
    }

    /** 从 MinIO 提取文件文本内容。 */
    private String extractText(String objectKey, String filename) throws Exception {
        byte[] content = minioStorageService.getObject(objectKey);
        String lower = filename.toLowerCase(Locale.ROOT);

        if (lower.endsWith(".xlsx") || lower.endsWith(".xls")) {
            return parseExcelToText(content);
        } else {
            String text = new String(content, StandardCharsets.UTF_8).replace("﻿", "");
            return text.length() > MAX_TEXT_CHARS ? text.substring(0, MAX_TEXT_CHARS) : text;
        }
    }

    private String parseExcelToText(byte[] content) throws Exception {
        try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(content))) {
            Sheet sheet = wb.getNumberOfSheets() > 0 ? wb.getSheetAt(0) : null;
            if (sheet == null) return "";
            StringBuilder sb = new StringBuilder();
            for (Row row : sheet) {
                List<String> vals = new ArrayList<>();
                for (int c = 0; c < row.getLastCellNum(); c++) {
                    Cell cell = row.getCell(c);
                    String v = cell == null ? "" : cell.toString().trim();
                    vals.add(v);
                }
                sb.append(String.join(", ", vals)).append("\n");
            }
            return sb.toString().trim();
        }
    }

    /** 调用 AI 模型（文本模式，作为结构化输出的回退）。 */
    private String callAi(String userPrompt) throws Exception {
        String apiKey = defaultAiApiKey;
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("API Key 未配置");
        }
        String endpoint = defaultAiEndpoint.endsWith("/chat/completions")
                ? defaultAiEndpoint
                : defaultAiEndpoint.replaceFirst("/+$", "") + "/chat/completions";

        Map<String, Object> body = Map.of(
                "model", defaultAiModel,
                "temperature", 0.25,
                "messages", List.of(
                        Map.of("role", "system", "content", "你是 SemiRisk 供应链风险分析助手，擅长从结构化数据中提取风险洞察。请用中文回答，不使用 Markdown 格式。"),
                        Map.of("role", "user", "content", userPrompt)
                )
        );

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(endpoint))
                .timeout(java.time.Duration.ofSeconds(60))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new RuntimeException("AI 调用失败: HTTP " + response.statusCode());
        }

        Map<String, Object> parsed = objectMapper.readValue(response.body(), new TypeReference<>() {});
        Map<String, Object> choices = (Map<String, Object>) parsed.get("choices");
        if (choices == null || ((List<?>) choices.get("message")).isEmpty()) {
            throw new RuntimeException("AI 响应格式异常");
        }
        Map<String, Object> message = (Map<String, Object>) ((List<?>) choices.get("message")).get(0);
        return (String) message.get("content");
    }

    /** AI 不可用时，生成本地摘要。 */
    private String buildLocalSummary(String filename, String text) {
        String[] lines = text.split("\\n");
        int rowCount = Math.max(0, lines.length - 1);
        String header = lines.length > 0 ? lines[0] : "未知表头";
        return "【数据概览】文件 " + filename + "，共 " + rowCount + " 行数据。\n"
                + "表头: " + header + "\n"
                + "【风险识别】文件已接收，建议结合历史数据进行交叉分析。\n"
                + "【风险维度】综合\n"
                + "【风险评分】0（无法自动评分，建议人工复核）\n"
                + "【处置建议】请将此文件与其他数据源关联分析，关注数据完整性和关键字段覆盖率。";
    }

    /** 解析 AI 返回的结构化结果（文本模式回退）。 */
    @SuppressWarnings("unchecked")
    private Map<String, Object> parseAiResult(String answer) {
        Map<String, Object> result = new HashMap<>();
        result.put("content", answer);

        String dimSection = extractSection(answer, "风险维度");
        if (!dimSection.isBlank()) {
            result.put("dimension", dimSection.replaceAll("[\\s【】]", "").split("、")[0]);
        } else {
            result.put("dimension", "综合");
        }

        String scoreSection = extractSection(answer, "风险评分");
        if (!scoreSection.isBlank()) {
            try {
                int score = Integer.parseInt(scoreSection.replaceAll("[^0-9]", ""));
                result.put("riskScore", Math.min(100, Math.max(0, score)));
            } catch (NumberFormatException ex) {
                log.debug("Failed to parse risk score from '{}': {}", scoreSection, ex.getMessage());
                result.put("riskScore", 0);
            }
        } else {
            result.put("riskScore", 0);
        }

        return result;
    }

    private String extractSection(String answer, String label) {
        int start = answer.indexOf("【" + label + "】");
        if (start < 0) return "";
        start += label.length() + 4;
        int next = answer.indexOf("【", start);
        return next > start ? answer.substring(start, next).trim() : answer.substring(start).trim();
    }

    /** 插入 knowledge_doc 记录。 */
    private void insertKnowledgeDoc(String docId, String title, String content,
                                     String dimension, int riskScore, String objectKey, String status) {
        try {
            jdbcTemplate.update("""
                    INSERT INTO knowledge_doc(id, category, title, content, source, source_url, dimension, risk_score, object_key, fetched_at, status)
                    VALUES (?, '上传数据', ?, ?, '用户上传', ?, ?, ?, ?, NOW(), ?)
                    ON DUPLICATE KEY UPDATE
                      category = VALUES(category),
                      title = VALUES(title),
                      content = VALUES(content),
                      source = VALUES(source),
                      source_url = VALUES(source_url),
                      dimension = VALUES(dimension),
                      risk_score = VALUES(risk_score),
                      object_key = VALUES(object_key),
                      fetched_at = VALUES(fetched_at),
                      status = VALUES(status)
                    """,
                    docId, title, content, "用户上传_" + objectKey, dimension, riskScore, objectKey, status);
        } catch (Exception ex) {
            log.warn("写入 knowledge_doc 失败: {}", ex.getMessage());
        }
    }

    /** 更新 upload_task 状态。 */
    private void updateTaskStatus(String taskId, String status, int rows) {
        try {
            jdbcTemplate.update("UPDATE upload_task SET status = ?, rows_count = ? WHERE id = ?",
                    status, rows, taskId);
        } catch (Exception ex) {
            log.warn("更新 upload_task 状态失败: {}", ex.getMessage());
        }
    }

    /** 更新 knowledge_doc 状态。 */
    private void updateDocStatus(String docId, String status) {
        try {
            jdbcTemplate.update("UPDATE knowledge_doc SET status = ?, update_time = CURRENT_TIMESTAMP WHERE id = ?",
                    status, docId);
        } catch (Exception ex) {
            log.warn("更新 knowledge_doc 状态失败: docId={}, status={}", docId, status, ex.getMessage());
        }
    }
}
