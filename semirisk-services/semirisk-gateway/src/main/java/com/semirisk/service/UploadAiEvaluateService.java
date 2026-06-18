package com.semirisk.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.semirisk.model.AiModelConfig;
import com.semirisk.model.UploadTask;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
import java.util.concurrent.*;

/**
 * 上传文件 AI 评估服务。
 *
 * <p>职责：提取文件文本 → 调用 AI 分析 → 写入 knowledge_doc → 索引 Elasticsearch → 更新任务状态。</p>
 */
@Service
public class UploadAiEvaluateService {

    private static final Logger log = LoggerFactory.getLogger(UploadAiEvaluateService.class);

    private static final int MAX_TEXT_CHARS = 5000;

    private final ExecutorService evalExecutor = Executors.newFixedThreadPool(3, r -> {
        Thread t = new Thread(r, "upload-ai-eval");
        t.setDaemon(true);
        return t;
    });

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(java.time.Duration.ofSeconds(10))
            .build();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final JdbcTemplate jdbcTemplate;
    private final MinioStorageService minioStorageService;
    private final KnowledgeSearchIndexService knowledgeSearchIndexService;
    private final String defaultAiModel;
    private final String defaultAiEndpoint;
    private final String defaultAiApiKey;

    public UploadAiEvaluateService(JdbcTemplate jdbcTemplate,
                                   MinioStorageService minioStorageService,
                                   KnowledgeSearchIndexService knowledgeSearchIndexService,
                                   @Value("${semirisk.ai.default.model:}") String defaultAiModel,
                                   @Value("${semirisk.ai.default.endpoint:}") String defaultAiEndpoint,
                                   @Value("${semirisk.ai.default.api-key:}") String defaultAiApiKey) {
        this.jdbcTemplate = jdbcTemplate;
        this.minioStorageService = minioStorageService;
        this.knowledgeSearchIndexService = knowledgeSearchIndexService;
        this.defaultAiModel = defaultAiModel;
        this.defaultAiEndpoint = defaultAiEndpoint;
        this.defaultAiApiKey = defaultAiApiKey;
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
        // 1. 提取文本
        String text = extractText(objectKey, filename);
        if (text == null || text.isBlank()) {
            updateTaskStatus(taskId, "文件内容为空", 0);
            return;
        }

        // 2. 截取到最大长度
        String truncated = text.length() > MAX_TEXT_CHARS ? text.substring(0, MAX_TEXT_CHARS) + "\n\n[... 文件过大，已截取前 5000 字符]" : text;

        // 3. 构建 AI prompt
        String prompt = """
                你是一个供应链风险分析专家。请分析以下数据文件内容，生成结构化风险评估摘要。
                数据文件: %s
                数据内容:
                %s
                请按以下格式输出（不要使用 Markdown 格式）：
                【数据概览】简述文件包含的字段、数据量级、主要数据类型
                【风险识别】从数据中发现的供应链风险因素（供应商集中度、交付周期异常、地理风险等）
                【风险维度】给出主要风险维度（如：供应商、物流、产能、合规、财务）
                【风险评分】给出 0-100 的综合风险评分及理由
                【处置建议】针对识别出的风险给出具体可操作的处置建议
                请保持简洁专业，总字数控制在 800 字以内。""";
        String aiPrompt = String.format(prompt, filename, truncated);

        // 4. 调用 AI
        String aiAnswer;
        try {
            aiAnswer = callAi(aiPrompt);
        } catch (Exception ex) {
            log.warn("AI 调用失败，使用本地摘要: {}", ex.getMessage());
            aiAnswer = buildLocalSummary(filename, text);
        }

        // 5. 解析 AI 结果
        Map<String, Object> result = parseAiResult(aiAnswer);
        String dimension = (String) result.getOrDefault("dimension", "综合");
        int riskScore = (int) result.getOrDefault("riskScore", 0);

        // 6. 写入 knowledge_doc
        String docId = "UPLOAD-" + taskId;
        String content = (String) result.getOrDefault("content", aiAnswer);
        insertKnowledgeDoc(docId, filename, content, dimension, riskScore, objectKey);

        // 7. 索引到 Elasticsearch
        try {
            knowledgeSearchIndexService.indexUploadedDoc(docId, filename, content, dimension, riskScore, objectKey);
        } catch (Exception ex) {
            log.warn("ES 索引失败（可选）: {}", ex.getMessage());
        }

        // 8. 更新任务状态
        updateTaskStatus(taskId, "已入库", 1);
    }

    /** 从 MinIO 提取文件文本内容。 */
    private String extractText(String objectKey, String filename) throws Exception {
        byte[] content = minioStorageService.getObject(objectKey);
        String lower = filename.toLowerCase(Locale.ROOT);

        if (lower.endsWith(".xlsx") || lower.endsWith(".xls")) {
            return parseExcelToText(content);
        } else {
            // CSV/TSV/纯文本
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

    /** 调用 AI 模型。 */
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

    /** 解析 AI 返回的结构化结果。 */
    @SuppressWarnings("unchecked")
    private Map<String, Object> parseAiResult(String answer) {
        Map<String, Object> result = new HashMap<>();
        result.put("content", answer);

        // 提取风险维度
        String dimSection = extractSection(answer, "风险维度");
        if (!dimSection.isBlank()) {
            result.put("dimension", dimSection.replaceAll("[\\s【】]", "").split("、")[0]);
        } else {
            result.put("dimension", "综合");
        }

        // 提取风险评分
        String scoreSection = extractSection(answer, "风险评分");
        if (!scoreSection.isBlank()) {
            try {
                int score = Integer.parseInt(scoreSection.replaceAll("[^0-9]", ""));
                result.put("riskScore", Math.min(100, Math.max(0, score)));
            } catch (NumberFormatException ignored) {
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
        start += label.length() + 4; // skip "【label】"
        // Find next section or end
        int next = answer.indexOf("【", start);
        return next > start ? answer.substring(start, next).trim() : answer.substring(start).trim();
    }

    /** 插入 knowledge_doc 记录。 */
    private void insertKnowledgeDoc(String docId, String title, String content,
                                     String dimension, int riskScore, String objectKey) {
        try {
            jdbcTemplate.update("""
                    INSERT INTO knowledge_doc(id, category, title, content, source, source_url, dimension, risk_score, object_key, fetched_at)
                    VALUES (?, '上传数据', ?, ?, '用户上传', ?, ?, ?, ?, NOW())
                    ON DUPLICATE KEY UPDATE
                      category = VALUES(category),
                      title = VALUES(title),
                      content = VALUES(content),
                      source = VALUES(source),
                      source_url = VALUES(source_url),
                      dimension = VALUES(dimension),
                      risk_score = VALUES(risk_score),
                      object_key = VALUES(object_key),
                      fetched_at = VALUES(fetched_at)
                    """,
                    docId, title, content, "用户上传_" + objectKey, dimension, riskScore, objectKey);
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
}
