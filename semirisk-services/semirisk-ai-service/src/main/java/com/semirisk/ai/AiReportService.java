package com.semirisk.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.semirisk.common.AiModelDefaults;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * AI 本日风险分析报告服务。
 *
 * <p>真实实现：从 {@code semirisk-data-service} 拉取实时公开源爬虫记录，聚合为上下文后调用 DeepSeek Chat Completions
 * 生成本日风险分析报告；未配置 Key 或模型不可达时，回退到基于真实爬取记录的本地聚合摘要，并在 {@code modelStatus}
 * 中明确说明。不再返回任何写死的静态文本。</p>
 */
@Service
public class AiReportService {

    private final AtomicReference<Map<String, Object>> latestReport = new AtomicReference<>();
    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final ObjectMapper objectMapper;
    private final String defaultModel;
    private final String defaultEndpoint;
    private final String defaultApiKey;
    private final String dataServiceUrl;

    public AiReportService(
            ObjectMapper objectMapper,
            @Value("${semirisk.ai.default.model:" + AiModelDefaults.DEFAULT_MODEL + "}") String defaultModel,
            @Value("${semirisk.ai.default.endpoint:" + AiModelDefaults.DEFAULT_ENDPOINT + "}") String defaultEndpoint,
            @Value("${semirisk.ai.default.api-key:}") String defaultApiKey,
            @Value("${semirisk.data-service.url:http://localhost:8081}") String dataServiceUrl) {
        this.objectMapper = objectMapper;
        this.defaultModel = defaultModel;
        this.defaultEndpoint = defaultEndpoint;
        this.defaultApiKey = defaultApiKey;
        this.dataServiceUrl = dataServiceUrl;
    }

    @PostConstruct
    public void init() {
        try {
            generateReport();
        } catch (Exception ignored) {
            // 启动时数据源/模型不可达不影响服务启动，下一轮定时刷新会重试。
        }
    }

    @PreDestroy
    public void destroy() {
        httpClient.close();
    }

    @Scheduled(cron = "0 0 6,12,20 * * *", zone = "Asia/Shanghai")
    public void generateReport() {
        List<Map<String, Object>> records = fetchPublicRecords();
        boolean configured = defaultApiKey != null && !defaultApiKey.isBlank();
        List<String> context = buildContext(records);
        int high = (int) records.stream().filter(r -> asInt(r.get("riskScore")) >= 80).count();
        int mid = (int) records.stream().filter(r -> asInt(r.get("riskScore")) >= 60 && asInt(r.get("riskScore")) < 80).count();
        int maxScore = records.stream().mapToInt(r -> asInt(r.get("riskScore"))).max().orElse(0);
        String level = maxScore >= 80 ? "高危" : maxScore >= 60 ? "中危" : maxScore > 0 ? "低危" : "待采集";

        AiAnswer answer = callDeepSeek(
                "请基于以下公开源情报，生成 SemiRisk 半导体供应链本日风险分析报告，包含【总体态势】【重点风险】【处置建议】三部分，分段清晰、突出事实与可执行建议。",
                context, configured);

        String summary = answer.answer().isBlank()
                ? "本日有效公开源信号 " + records.size() + " 条，综合等级 " + level + "（最高分 " + maxScore + "），高危 " + high + " 条、中危 " + mid + " 条。"
                : answer.answer();
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("title", "SemiRisk AI 本日风险分析");
        report.put("model", defaultModel);
        report.put("configured", configured);
        report.put("modelStatus", answer.status());
        report.put("aiCalled", answer.called());
        report.put("usage", answer.usage());
        report.put("signalCount", records.size());
        report.put("level", level);
        report.put("maxScore", maxScore);
        report.put("summary", summary);
        report.put("recommendation", "优先核验高分公开源原文，将高危信号转为告警工单并绑定负责人与闭环时间。");
        report.put("dataSource", "semirisk-data-service 实时公开源爬取");
        report.put("generatedAt", Instant.now().toString());
        latestReport.set(report);
    }

    public Map<String, Object> latestReport() {
        Map<String, Object> report = latestReport.get();
        if (report != null) {
            return report;
        }
        generateReport();
        return latestReport.get();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> fetchPublicRecords() {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(trimUrl(dataServiceUrl) + "/api/crawler/records/recent"))
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 300) {
                return List.of();
            }
            JsonNode root = objectMapper.readTree(response.body());
            JsonNode data = root.path("data");
            List<Map<String, Object>> records = new ArrayList<>();
            if (data.isArray()) {
                for (JsonNode node : data) {
                    if (!"OK".equalsIgnoreCase(node.path("status").asText("OK"))) {
                        continue;
                    }
                    Map<String, Object> record = new LinkedHashMap<>();
                    record.put("source", node.path("source").asText(""));
                    record.put("title", node.path("title").asText(""));
                    record.put("dimension", node.path("dimension").asText(""));
                    record.put("riskScore", node.path("riskScore").asInt(0));
                    record.put("sourceUrl", node.path("sourceUrl").asText(""));
                    records.add(record);
                }
            }
            return records;
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private List<String> buildContext(List<Map<String, Object>> records) {
        List<String> context = new ArrayList<>();
        if (records.isEmpty()) {
            context.add("公开源暂无成功采集记录，请说明当前无法给出基于真实数据的研判，并建议检查爬虫源连通性。");
            return context;
        }
        records.stream().limit(20).forEach(record -> context.add(
                "公开源：" + record.get("riskScore") + " | " + record.get("source") + " | "
                        + record.get("dimension") + " | " + record.get("title") + " | " + record.get("sourceUrl")));
        return context;
    }

    private AiAnswer callDeepSeek(String prompt, List<String> context, boolean configured) {
        if (!configured) {
            return new AiAnswer(false, "", "未配置 API Key，当前使用基于真实爬取记录的本地聚合摘要", Map.of());
        }
        String url = defaultEndpoint.endsWith("/chat/completions")
                ? defaultEndpoint
                : defaultEndpoint.replaceAll("/+$", "") + "/chat/completions";
        try {
            String apiModel = resolveApiModel(defaultModel);
            Map<String, Object> payload = Map.of(
                    "model", apiModel,
                    "temperature", 0.2,
                    "stream", false,
                    "messages", List.of(
                            Map.of("role", "system", "content", "你是 SemiRisk 半导体供应链风险分析智能体，只能依据给定公开源情报回答，输出中文，区分事实与建议。"),
                            Map.of("role", "user", "content", prompt + "\n\n公开源情报：\n" + String.join("\n", context))
                    )
            );
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(25))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + defaultApiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return new AiAnswer(false, "", "DeepSeek 调用失败 HTTP " + response.statusCode() + "，已回退本地聚合摘要", Map.of("httpStatus", response.statusCode()));
            }
            JsonNode root = objectMapper.readTree(response.body());
            String answer = root.path("choices").path(0).path("message").path("content").asText("");
            Map<String, Object> usage = new LinkedHashMap<>();
            JsonNode usageNode = root.path("usage");
            if (!usageNode.isMissingNode()) {
                usage.put("promptTokens", usageNode.path("prompt_tokens").asInt(0));
                usage.put("completionTokens", usageNode.path("completion_tokens").asInt(0));
                usage.put("totalTokens", usageNode.path("total_tokens").asInt(0));
            }
            usage.put("apiModel", apiModel);
            return new AiAnswer(true, answer, "已调用 DeepSeek Chat Completions，显示模型 " + defaultModel + "，实际请求模型 " + apiModel, usage);
        } catch (Exception ex) {
            return new AiAnswer(false, "", "DeepSeek 调用异常：" + ex.getClass().getSimpleName() + "，已回退本地聚合摘要", Map.of("error", ex.getClass().getSimpleName()));
        }
    }

    private String resolveApiModel(String model) {
        if ("deepseekv4-pro".equalsIgnoreCase(model) || "deepseek-v4-pro".equalsIgnoreCase(model)) {
            return "deepseek-v4-pro";
        }
        return model;
    }

    private String trimUrl(String url) {
        return url == null ? "" : url.replaceAll("/+$", "");
    }

    private int asInt(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (Exception ignored) {
            return 0;
        }
    }

    private record AiAnswer(boolean called, String answer, String status, Map<String, Object> usage) {
    }
}
