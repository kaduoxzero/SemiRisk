package com.semirisk.service;

import com.semirisk.common.AiModelDefaults;
import com.semirisk.model.AiModelConfig;
import com.semirisk.repository.PreparedRiskRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AI 聊天服务。
 *
 * <p>API Key 和 Endpoint 统一从 SemiRiskStore 读取，确保单一事实源。</p>
 */
@Service
public class AiChatService {

    private final String defaultAiModel;
    private final String defaultAiEndpoint;
    private final SemiRiskStore store;
    private final ObjectMapper objectMapper;
    private final PreparedRiskRepository repository;
    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(8)).build();

    private final List<String> auditLogs;

    public AiChatService(
            @Value("${semirisk.ai.default.model:" + AiModelDefaults.DEFAULT_MODEL + "}") String defaultAiModel,
            @Value("${semirisk.ai.default.endpoint:" + AiModelDefaults.DEFAULT_ENDPOINT + "}") String defaultAiEndpoint,
            SemiRiskStore store,
            ObjectMapper objectMapper,
            PreparedRiskRepository repository) {
        this.defaultAiModel = defaultAiModel;
        this.defaultAiEndpoint = defaultAiEndpoint;
        this.store = store;
        this.objectMapper = objectMapper;
        this.repository = repository;
        this.auditLogs = new ArrayList<>();
    }

    public AiAnswer callDeepSeek(String question, List<String> contextLines) {
        // 统一从 SemiRiskStore 获取 API Key（优先动态配置，回退到 application.properties 默认值）
        String apiKey = store.getAiApiKey(defaultAiModel);
        if (apiKey == null || apiKey.isBlank()) {
            return new AiAnswer(false, "", "未配置 API Key，当前使用本地 RAG 摘要", Map.of());
        }

        // 获取 endpoint：优先动态配置，回退到默认值
        Map<String, AiModelConfig> configs = store.aiModelConfigs();
        AiModelConfig config = configs.get(defaultAiModel);
        String endpoint = config != null ? config.endpoint() : defaultAiEndpoint;

        String url = endpoint.endsWith("/chat/completions")
                ? endpoint
                : endpoint.replaceAll("/+$", "") + "/chat/completions";
        try {
            String apiModel = resolveDeepSeekApiModel(defaultAiModel);
            Map<String, Object> payload = Map.of(
                    "model", apiModel,
                    "temperature", 0.25,
                    "stream", false,
                    "messages", List.of(
                            Map.of("role", "system", "content",
                                    "你是 SemiRisk 半导体供应链风险顾问，为企业高管提供决策级分析。" +
                                    "规则：1)绝对不输出任何Markdown符号（不用#*-`---[]()）；" +
                                    "2)不使用'以下是''根据您提供的''以下为'等引导语，直接给出结论；" +
                                    "3)每段以中文序号开头（一、二、三…）；" +
                                    "4)必须引用给定上下文中的具体信号标题和分数来支撑判断；" +
                                    "5)每条建议必须注明负责部门和时限；" +
                                    "6)只能基于给定上下文回答，不编造数据。"),
                            Map.of("role", "user", "content", "任务：" + question + "\n\n当前数据上下文：\n" + String.join("\n", contextLines))
                    )
            );
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(java.time.Duration.ofSeconds(45))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                String errorBody = response.body() != null ? response.body().substring(0, Math.min(200, response.body().length())) : "(empty)";
                auditLogs.add("[WARN] DeepSeek call failed status=" + response.statusCode() + " body=" + errorBody);
                return new AiAnswer(false, "", "DeepSeek 调用失败，HTTP " + response.statusCode() + "，已回退本地 RAG 摘要",
                        Map.of("httpStatus", response.statusCode(), "error", errorBody));
            }
            JsonNode root = objectMapper.readTree(response.body());
            String answer = root.path("choices").path(0).path("message").path("content").asText("");
            if (answer.isBlank()) {
                auditLogs.add("[WARN] DeepSeek returned empty answer");
                return new AiAnswer(false, "", "DeepSeek 返回空答案，已回退本地 RAG 摘要", Map.of());
            }
            Map<String, Object> usage = new HashMap<>();
            JsonNode usageNode = root.path("usage");
            if (!usageNode.isMissingNode()) {
                usage.put("promptTokens", usageNode.path("prompt_tokens").asInt(0));
                usage.put("completionTokens", usageNode.path("completion_tokens").asInt(0));
                usage.put("totalTokens", usageNode.path("total_tokens").asInt(0));
            }
            usage.put("apiModel", apiModel);
            auditLogs.add("[INFO] DeepSeek knowledge agent called model=" + apiModel + " displayModel=" + defaultAiModel + " totalTokens=" + usage.getOrDefault("totalTokens", 0));
            return new AiAnswer(true, answer, "已调用 DeepSeek Chat Completions，模型返回成功；显示模型 " + defaultAiModel + "，实际请求模型 " + apiModel, usage);
        } catch (Exception ex) {
            auditLogs.add("[WARN] DeepSeek call exception " + ex.getClass().getSimpleName());
            return new AiAnswer(false, "", "DeepSeek 调用异常：" + ex.getClass().getSimpleName() + "，已回退本地 RAG 摘要", Map.of("error", ex.getClass().getSimpleName()));
        }
    }

    public List<String> splitAnswer(String answer) {
        if (answer == null || answer.isBlank()) return List.of();
        String cleaned = answer
                .replaceAll("(?m)^#{1,6}\\s*", "")
                .replaceAll("\\*{1,3}([^*]+)\\*{1,3}", "$1")
                .replaceAll("(?m)^[-*]{3,}\\s*$", "")
                .replaceAll("(?m)^[-*+]\\s+", "• ")
                .replaceAll("`([^`]+)`", "$1")
                .replaceAll("\\[([^]]+)]\\([^)]+\\)", "$1")
                .replace("\r", "\n");
        return java.util.Arrays.stream(cleaned.split("\\n+|(?<=[。！？；])"))
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .filter(value -> !value.matches("(?i)(以下是|根据您|根据以上|以下为|下面是|以下内容|^好的[，,]?|^当然[，,]?).*"))
                .toList();
    }

    public String resolveDeepSeekApiModel(String model) {
        if ("deepseekv4-pro".equalsIgnoreCase(model) || "deepseek-v4-pro".equalsIgnoreCase(model)) {
            return "deepseek-chat";
        }
        return model;
    }

    public List<String> localKnowledgeLines() {
        try {
            List<Map<String, Object>> docs = repository.findKnowledgeDocsByCategory("内部知识库", 20);
            if (!docs.isEmpty()) {
                return docs.stream()
                        .map(doc -> "内部知识库 | " + stringValue(doc.get("dimension")) + " | " + stringValue(doc.get("title")) + "：" + stringValue(doc.get("content")))
                        .toList();
            }
        } catch (Exception ignored) {}
        return List.of(
                "内部知识库 | 处置 | 高危供应链告警先核验公开源原文，再确认影响物料、库存覆盖天数和替代供应商。",
                "内部知识库 | 半导体 | 半导体供应链风险重点关注先进制程产能、封测排期、关键设备出口管制、物流节点拥堵和汇率/关税变化。",
                "内部知识库 | 处置 | 当公开源出现关税、罢工、港口拥堵、制裁、短缺等关键词时，优先同步采购、物流、合规三类责任人。",
                "内部知识库 | 报告 | 管理层报告需要给出事实来源、影响范围、评分依据、可选方案、负责人和闭环时间。"
        );
    }

    public Map<String, Object> buildKnowledgeAnswerPayload(String question, String answer, AiAnswer aiAnswer, List<String> trace, List<Map<String, Object>> citations) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("question", question);
        payload.put("answer", answer);
        payload.put("sections", answerSections(answer, citations));
        payload.put("nextActions", List.of("核验引用原文", "转入预警工单", "补充影响物料/供应商", "生成管理层报告"));
        payload.put("model", defaultAiModel);
        payload.put("modelStatus", aiAnswer.status());
        payload.put("aiCalled", aiAnswer.called());
        payload.put("usage", aiAnswer.usage());
        payload.put("trace", trace);
        payload.put("citations", citations);
        payload.put("answeredAt", Instant.now().toString());
        return payload;
    }

    private List<Map<String, Object>> answerSections(String answer, List<Map<String, Object>> citations) {
        String clean = answer == null || answer.isBlank() ? "暂无可用回答。" : answer.trim();
        List<String> paragraphs = splitAnswer(clean);
        List<Map<String, Object>> sections = new ArrayList<>();
        sections.add(Map.of(
                "title", "结论",
                "items", paragraphs.isEmpty() ? List.of(clean) : paragraphs.subList(0, Math.min(2, paragraphs.size()))
        ));
        List<String> basis = citations == null || citations.isEmpty()
                ? List.of("知识库未返回可引用公开源，建议先检查采集任务。")
                : citations.stream().limit(3).map(item -> stringValue(item.get("source")) + "：" + stringValue(item.get("title"))).toList();
        sections.add(Map.of("title", "依据", "items", basis));
        sections.add(Map.of(
                "title", "处置建议",
                "items", List.of("优先核验评分最高的公开源原文。", "确认影响物料、库存覆盖天数、替代供应商和责任人。", "将有效高危信号转为告警工单并进入闭环跟踪。")
        ));
        if (paragraphs.size() > 2) {
            sections.add(Map.of("title", "补充说明", "items", paragraphs.subList(2, Math.min(6, paragraphs.size()))));
        }
        return sections;
    }

    public String getDefaultAiModel() {
        return defaultAiModel;
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    public record AiAnswer(boolean called, String answer, String status, Map<String, Object> usage) {}
}
