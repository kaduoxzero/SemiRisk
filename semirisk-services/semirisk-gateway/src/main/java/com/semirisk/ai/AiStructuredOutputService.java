package com.semirisk.ai;

import com.fasterxml.jackson.annotation.JsonProperty;
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
import java.util.List;
import java.util.Map;

/**
 * AI 结构化输出服务（Phase 3）。
 *
 * <p>核心改进：
 * <ul>
 *   <li>使用 DeepSeek Response Format (JSON Schema) 确保 AI 输出 100% 格式稳定</li>
 *   <li>无需 Markdown 清洗和正则解析</li>
 *   <li>所有 AI 场景统一走结构化输出</li>
 * </ul>
 * </p>
 */
@Service
public class AiStructuredOutputService {

    private static final Logger log = LoggerFactory.getLogger(AiStructuredOutputService.class);

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final String defaultAiModel;
    private final String defaultAiEndpoint;
    private final String defaultAiApiKey;

    public AiStructuredOutputService(
            @Value("${semirisk.ai.default.model:}") String defaultAiModel,
            @Value("${semirisk.ai.default.endpoint:}") String defaultAiEndpoint,
            @Value("${semirisk.ai.default.api-key:}") String defaultAiApiKey) {
        this.defaultAiModel = defaultAiModel;
        this.defaultAiEndpoint = defaultAiEndpoint;
        this.defaultAiApiKey = defaultAiApiKey;
    }

    // =========================================================================
    // 1. AI 报告生成（结构化输出）
    // =========================================================================

    /** AI 报告结构化输出 DTO */
    public static class AiReportOutput {
        @JsonProperty("conclusion")
        public String conclusion;

        @JsonProperty("drivingFactors")
        public List<String> drivingFactors;

        @JsonProperty("riskRanking")
        public List<Map<String, Object>> riskRanking;

        @JsonProperty("forecast")
        public String forecast;

        @JsonProperty("actionPlan")
        public List<ActionItem> actionPlan;

        public static class ActionItem {
            @JsonProperty("department")
            public String department;
            @JsonProperty("action")
            public String action;
            @JsonProperty("deadline")
            public String deadline;
        }
    }

    /** 结构化报告生成 */
    @SuppressWarnings("unchecked")
    public AiReportOutput generateReportStructured(String prompt, List<String> contextLines) {
        String apiKey = defaultAiApiKey;
        if (apiKey == null || apiKey.isBlank()) {
            log.warn("API Key not configured, returning null for structured report");
            return null;
        }

        String endpoint = defaultAiEndpoint.endsWith("/chat/completions")
                ? defaultAiEndpoint
                : defaultAiEndpoint.replaceAll("/+$", "") + "/chat/completions";

        // 使用 JSON Schema 强制结构化输出
        Map<String, Object> payload = Map.of(
                "model", defaultAiModel,
                "temperature", 0.1,         // 低温度确保稳定性
                "response_format", Map.of(
                        "type", "json_schema",
                        "json_schema", Map.of(
                                "name", "AiReportOutput",
                                "strict", true,
                                "schema", Map.of(
                                        "type", "object",
                                        "required", List.of("conclusion", "drivingFactors", "riskRanking", "forecast", "actionPlan"),
                                        "properties", Map.of(
                                                "conclusion", Map.of("type", "string", "description", "综合风险结论，一句话总结当前态势"),
                                                "drivingFactors", Map.of(
                                                        "type", "array",
                                                        "items", Map.of("type", "string"),
                                                        "description", "评分驱动因素列表"
                                                ),
                                                "riskRanking", Map.of(
                                                        "type", "array",
                                                        "items", Map.of(
                                                                "type", "object",
                                                                "properties", Map.of(
                                                                        "dimension", Map.of("type", "string"),
                                                                        "score", Map.of("type", "integer"),
                                                                        "signalCount", Map.of("type", "integer")
                                                                )
                                                        )
                                                ),
                                                "forecast", Map.of("type", "string", "description", "未来 7-30 天走势研判"),
                                                "actionPlan", Map.of(
                                                        "type", "array",
                                                        "items", Map.of(
                                                                "type", "object",
                                                                "required", List.of("department", "action", "deadline"),
                                                                "properties", Map.of(
                                                                        "department", Map.of("type", "string"),
                                                                        "action", Map.of("type", "string"),
                                                                        "deadline", Map.of("type", "string")
                                                                )
                                                        )
                                                )
                                        )
                                )
                        )
                ),
                "messages", List.of(
                        Map.of("role", "system", "content",
                                "你是 SemiRisk 半导体供应链风险顾问。必须以严格的 JSON 格式输出，不要包含任何额外文本。"),
                        Map.of("role", "user", "content",
                                "任务：" + prompt + "\n\n数据上下文：\n" + String.join("\n", contextLines))
                )
        );

        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint))
                    .timeout(Duration.ofSeconds(60))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.warn("Structured AI report failed HTTP {}, falling back to text", response.statusCode());
                return null;
            }

            Map<String, Object> parsed = objectMapper.readValue(response.body(), Map.class);
            List<Map<String, Object>> choices = (List<Map<String, Object>>) parsed.get("choices");
            if (choices == null || choices.isEmpty()) {
                return null;
            }
            String content = (String) choices.get(0).get("message");
            if (content == null || content.isBlank()) {
                return null;
            }

            // 直接反序列化为 DTO，无需任何清洗
            return objectMapper.readValue(content, AiReportOutput.class);
        } catch (Exception ex) {
            log.error("Structured report generation failed, falling back", ex);
            return null;
        }
    }

    // =========================================================================
    // 2. 文件评估结构化输出
    // =========================================================================

    /** 文件评估结构化输出 DTO */
    public static class AiEvalOutput {
        @JsonProperty("dataOverview")
        public String dataOverview;

        @JsonProperty("risksIdentified")
        public List<String> risksIdentified;

        @JsonProperty("riskDimensions")
        public List<String> riskDimensions;

        @JsonProperty("riskScore")
        public int riskScore;

        @JsonProperty("scoreReason")
        public String scoreReason;

        @JsonProperty("recommendations")
        public List<Map<String, String>> recommendations;
    }

    @SuppressWarnings("unchecked")
    public AiEvalOutput evaluateUploadStructured(String filename, String fileText) {
        String apiKey = defaultAiApiKey;
        if (apiKey == null || apiKey.isBlank()) {
            return null;
        }

        String endpoint = defaultAiEndpoint.endsWith("/chat/completions")
                ? defaultAiEndpoint
                : defaultAiEndpoint.replaceAll("/+$", "") + "/chat/completions";

        String truncated = fileText.length() > 3000 ? fileText.substring(0, 3000) + "[...]" : fileText;

        Map<String, Object> payload = Map.of(
                "model", defaultAiModel,
                "temperature", 0.1,
                "response_format", Map.of(
                        "type", "json_schema",
                        "json_schema", Map.of(
                                "name", "AiEvalOutput",
                                "strict", true,
                                "schema", Map.of(
                                        "type", "object",
                                        "required", List.of("dataOverview", "risksIdentified", "riskDimensions", "riskScore", "scoreReason", "recommendations"),
                                        "properties", Map.of(
                                                "dataOverview", Map.of("type", "string"),
                                                "risksIdentified", Map.of("type", "array", "items", Map.of("type", "string")),
                                                "riskDimensions", Map.of("type", "array", "items", Map.of("type", "string")),
                                                "riskScore", Map.of("type", "integer"),
                                                "scoreReason", Map.of("type", "string"),
                                                "recommendations", Map.of(
                                                        "type", "array",
                                                        "items", Map.of(
                                                                "type", "object",
                                                                "properties", Map.of(
                                                                        "action", Map.of("type", "string"),
                                                                        "priority", Map.of("type", "string")
                                                                )
                                                        )
                                                )
                                        )
                                )
                        )
                ),
                "messages", List.of(
                        Map.of("role", "system", "content", "你是供应链风险分析专家。必须输出严格 JSON。"),
                        Map.of("role", "user", "content",
                                "分析以下文件内容的供应链风险：\n文件名：" + filename + "\n内容：\n" + truncated)
                )
        );

        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint))
                    .timeout(Duration.ofSeconds(60))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                return null;
            }

            Map<String, Object> parsed = objectMapper.readValue(response.body(), Map.class);
            List<Map<String, Object>> choices = (List<Map<String, Object>>) parsed.get("choices");
            if (choices == null || choices.isEmpty()) {
                return null;
            }
            String content = (String) choices.get(0).get("message");
            if (content == null || content.isBlank()) {
                return null;
            }
            return objectMapper.readValue(content, AiEvalOutput.class);
        } catch (Exception ex) {
            log.error("Structured eval failed", ex);
            return null;
        }
    }

    // =========================================================================
    // 3. 本日风险结构化输出
    // =========================================================================

    /** 本日风险分析结构化输出 DTO */
    public static class DailyRiskOutput {
        @JsonProperty("overallStatus")
        public String overallStatus;

        @JsonProperty("score")
        public int score;

        @JsonProperty("level")
        public String level;

        @JsonProperty("keyEvents")
        public List<KeyEvent> keyEvents;

        @JsonProperty("recommendations")
        public List<String> recommendations;

        public static class KeyEvent {
            @JsonProperty("title")
            public String title;
            @JsonProperty("source")
            public String source;
            @JsonProperty("score")
            public int score;
            @JsonProperty("impact")
            public String impact;
        }
    }

    @SuppressWarnings("unchecked")
    public DailyRiskOutput generateDailyRiskStructured(List<String> signalSummaries) {
        String apiKey = defaultAiApiKey;
        if (apiKey == null || apiKey.isBlank()) {
            return null;
        }

        String endpoint = defaultAiEndpoint.endsWith("/chat/completions")
                ? defaultAiEndpoint
                : defaultAiEndpoint.replaceAll("/+$", "") + "/chat/completions";

        Map<String, Object> payload = Map.of(
                "model", defaultAiModel,
                "temperature", 0.1,
                "response_format", Map.of(
                        "type", "json_schema",
                        "json_schema", Map.of(
                                "name", "DailyRiskOutput",
                                "strict", true,
                                "schema", Map.of(
                                        "type", "object",
                                        "required", List.of("overallStatus", "score", "level", "keyEvents", "recommendations"),
                                        "properties", Map.of(
                                                "overallStatus", Map.of("type", "string"),
                                                "score", Map.of("type", "integer"),
                                                "level", Map.of("type", "string"),
                                                "keyEvents", Map.of(
                                                        "type", "array",
                                                        "items", Map.of(
                                                                "type", "object",
                                                                "properties", Map.of(
                                                                        "title", Map.of("type", "string"),
                                                                        "source", Map.of("type", "string"),
                                                                        "score", Map.of("type", "integer"),
                                                                        "impact", Map.of("type", "string")
                                                                )
                                                        )
                                                ),
                                                "recommendations", Map.of("type", "array", "items", Map.of("type", "string"))
                                        )
                                )
                        )
                ),
                "messages", List.of(
                        Map.of("role", "system", "content", "你是半导体供应链风险顾问。必须输出严格 JSON。"),
                        Map.of("role", "user", "content", "分析本日供应链风险：\n" + String.join("\n", signalSummaries))
                )
        );

        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint))
                    .timeout(Duration.ofSeconds(60))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                return null;
            }

            Map<String, Object> parsed = objectMapper.readValue(response.body(), Map.class);
            List<Map<String, Object>> choices = (List<Map<String, Object>>) parsed.get("choices");
            if (choices == null || choices.isEmpty()) {
                return null;
            }
            String content = (String) choices.get(0).get("message");
            return objectMapper.readValue(content, DailyRiskOutput.class);
        } catch (Exception ex) {
            log.error("Structured daily risk failed", ex);
            return null;
        }
    }

    // =========================================================================
    // 4. 问答结构化输出
    // =========================================================================

    /** 问答结构化输出 DTO */
    public static class AiQaOutput {
        @JsonProperty("conclusion")
        public String conclusion;

        @JsonProperty("evidence")
        public List<EvidenceItem> evidence;

        @JsonProperty("confidence")
        public String confidence;

        @JsonProperty("suggestedActions")
        public List<String> suggestedActions;

        public static class EvidenceItem {
            @JsonProperty("source")
            public String source;
            @JsonProperty("title")
            public String title;
            @JsonProperty("relevance")
            public String relevance;
        }
    }

    @SuppressWarnings("unchecked")
    public AiQaOutput askQuestionStructured(String question, List<String> contextLines) {
        String apiKey = defaultAiApiKey;
        if (apiKey == null || apiKey.isBlank()) {
            return null;
        }

        String endpoint = defaultAiEndpoint.endsWith("/chat/completions")
                ? defaultAiEndpoint
                : defaultAiEndpoint.replaceAll("/+$", "") + "/chat/completions";

        Map<String, Object> payload = Map.of(
                "model", defaultAiModel,
                "temperature", 0.1,
                "response_format", Map.of(
                        "type", "json_schema",
                        "json_schema", Map.of(
                                "name", "AiQaOutput",
                                "strict", true,
                                "schema", Map.of(
                                        "type", "object",
                                        "required", List.of("conclusion", "evidence", "confidence", "suggestedActions"),
                                        "properties", Map.of(
                                                "conclusion", Map.of("type", "string"),
                                                "evidence", Map.of(
                                                        "type", "array",
                                                        "items", Map.of(
                                                                "type", "object",
                                                                "properties", Map.of(
                                                                        "source", Map.of("type", "string"),
                                                                        "title", Map.of("type", "string"),
                                                                        "relevance", Map.of("type", "string")
                                                                )
                                                        )
                                                ),
                                                "confidence", Map.of("type", "string"),
                                                "suggestedActions", Map.of("type", "array", "items", Map.of("type", "string"))
                                        )
                                )
                        )
                ),
                "messages", List.of(
                        Map.of("role", "system", "content", "你是供应链风险顾问。必须输出严格 JSON。"),
                        Map.of("role", "user", "content", question + "\n\n上下文：\n" + String.join("\n", contextLines))
                )
        );

        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint))
                    .timeout(Duration.ofSeconds(60))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                return null;
            }

            Map<String, Object> parsed = objectMapper.readValue(response.body(), Map.class);
            List<Map<String, Object>> choices = (List<Map<String, Object>>) parsed.get("choices");
            if (choices == null || choices.isEmpty()) {
                return null;
            }
            String content = (String) choices.get(0).get("message");
            return objectMapper.readValue(content, AiQaOutput.class);
        } catch (Exception ex) {
            log.error("Structured QA failed", ex);
            return null;
        }
    }
}
