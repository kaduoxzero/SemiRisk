package com.semirisk.service;

import com.semirisk.ai.AiStructuredOutputService;
import com.semirisk.common.AiModelDefaults;
import com.semirisk.model.AiModelConfig;
import com.semirisk.repository.PreparedRiskRepository;
import com.semirisk.config.ThreadPoolConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.semirisk.util.SafeLogger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AI 聊天服务（Phase 3 重构：优先结构化输出，回退到文本模式）。
 *
 * <p>变更：
 * <ul>
 *   <li>新增 callDeepSeekStructured() 使用 JSON Schema 强制结构化输出</li>
 *   <li>原有 callDeepSeek() 保留作为回退路径</li>
 *   <li>自动降级：结构化失败 → 文本模式 → 本地摘要</li>
 * </ul>
 * </p>
 */
@Service
public class AiChatService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(AiChatService.class);

    private final String defaultAiModel;
    private final String defaultAiEndpoint;
    private final SemiRiskStore store;
    private final ObjectMapper objectMapper;
    private final PreparedRiskRepository repository;
    private final AiStructuredOutputService structuredOutputService;

    private final List<String> auditLogs;

    public AiChatService(
            @Value("${semirisk.ai.default.model:" + AiModelDefaults.DEFAULT_MODEL + "}") String defaultAiModel,
            @Value("${semirisk.ai.default.endpoint:" + AiModelDefaults.DEFAULT_ENDPOINT + "}") String defaultAiEndpoint,
            SemiRiskStore store,
            ObjectMapper objectMapper,
            PreparedRiskRepository repository,
            AiStructuredOutputService structuredOutputService) {
        this.defaultAiModel = defaultAiModel;
        this.defaultAiEndpoint = defaultAiEndpoint;
        this.store = store;
        this.objectMapper = objectMapper;
        this.repository = repository;
        this.structuredOutputService = structuredOutputService;
        this.auditLogs = new ArrayList<>();
    }

    /**
     * 调用 DeepSeek（优先结构化输出）。
     */
    public AiAnswer callDeepSeek(String question, List<String> contextLines) {
        String apiKey = store.getAiApiKey(defaultAiModel);
        if (apiKey == null || apiKey.isBlank()) {
            return new AiAnswer(false, "", "未配置 API Key，当前使用本地 RAG 摘要", Map.of());
        }

        // Phase 3: 优先尝试结构化输出
        AiStructuredOutputService.AiQaOutput structured = structuredOutputService.askQuestionStructured(question, contextLines);
        if (structured != null) {
            String answer = buildAnswerFromStructured(structured);
            Map<String, Object> usage = new HashMap<>();
            usage.put("mode", "structured");
            usage.put("confidence", structured.confidence);
            auditLogs.add("[INFO] DeepSeek structured QA called model=" + defaultAiModel);
            return new AiAnswer(true, answer, "已调用 DeepSeek 结构化输出", usage);
        }

        // 回退到原有文本模式
        return callDeepSeekText(question, contextLines);
    }

    /** 将结构化输出组装为自然语言答案。 */
    private String buildAnswerFromStructured(AiStructuredOutputService.AiQaOutput structured) {
        StringBuilder sb = new StringBuilder();
        sb.append(structured.conclusion != null ? structured.conclusion : "暂无结论");
        if (structured.evidence != null && !structured.evidence.isEmpty()) {
            sb.append("\n\n依据：");
            for (var ev : structured.evidence) {
                sb.append("\n- [").append(ev.source != null ? ev.source : "?").append("] ")
                  .append(ev.title != null ? ev.title : "?")
                  .append(" (").append(ev.relevance != null ? ev.relevance : "相关").append(")");
            }
        }
        if (structured.suggestedActions != null && !structured.suggestedActions.isEmpty()) {
            sb.append("\n\n建议行动：");
            for (int i = 0; i < structured.suggestedActions.size(); i++) {
                sb.append("\n").append(i + 1).append(". ").append(structured.suggestedActions.get(i));
            }
        }
        return sb.toString();
    }

    /** 原有文本模式（保留作为回退）。 */
    private AiAnswer callDeepSeekText(String question, List<String> contextLines) {
        String apiKey = store.getAiApiKey(defaultAiModel);
        if (apiKey == null || apiKey.isBlank()) {
            return new AiAnswer(false, "", "未配置 API Key", Map.of());
        }

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
                                    "规则：1)绝对不输出任何Markdown符号；" +
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
            HttpResponse<String> response = ThreadPoolConfig.sharedHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                String errorBody = response.body() != null ? response.body().substring(0, Math.min(200, response.body().length())) : "(empty)";
                auditLogs.add("[WARN] DeepSeek call failed status=" + response.statusCode() + " body=" + errorBody);
                return new AiAnswer(false, "", "DeepSeek 调用失败，HTTP " + response.statusCode(),
                        Map.of("httpStatus", response.statusCode(), "error", errorBody));
            }
            JsonNode root = objectMapper.readTree(response.body());
            String answer = root.path("choices").path(0).path("message").path("content").asText("");
            if (answer.isBlank()) {
                auditLogs.add("[WARN] DeepSeek returned empty answer");
                return new AiAnswer(false, "", "DeepSeek 返回空答案", Map.of());
            }
            Map<String, Object> usage = new HashMap<>();
            JsonNode usageNode = root.path("usage");
            if (!usageNode.isMissingNode()) {
                usage.put("promptTokens", usageNode.path("prompt_tokens").asInt(0));
                usage.put("completionTokens", usageNode.path("completion_tokens").asInt(0));
                usage.put("totalTokens", usageNode.path("total_tokens").asInt(0));
            }
            usage.put("apiModel", apiModel);
            usage.put("mode", "text");
            auditLogs.add("[INFO] DeepSeek knowledge agent called model=" + apiModel + " displayModel=" + defaultAiModel + " totalTokens=" + usage.getOrDefault("totalTokens", 0));
            return new AiAnswer(true, answer, "已调用 DeepSeek Chat Completions（文本模式）", usage);
        } catch (Exception ex) {
            auditLogs.add("[WARN] DeepSeek call exception " + ex.getClass().getSimpleName());
            return new AiAnswer(false, "", "DeepSeek 调用异常：" + ex.getClass().getSimpleName(), Map.of("error", ex.getClass().getSimpleName()));
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
            return "deepseek-v4-pro";
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
        } catch (Exception ex) {
            SafeLogger.debug(log, "Failed to load internal knowledge docs", ex);
        }
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
