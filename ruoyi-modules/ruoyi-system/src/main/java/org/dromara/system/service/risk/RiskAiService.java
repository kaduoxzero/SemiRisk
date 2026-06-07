package org.dromara.system.service.risk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.system.domain.risk.RiskEvent;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
public class RiskAiService {

    public static final String FAILURE_PREFIX = "AI service request failed:";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build();

    @Value("${risk.ai.service-url:http://127.0.0.1:18088/analyze}")
    private String serviceUrl;

    public String generateReport(String templateType, String dateRange, List<RiskEvent> events) {
        if (StringUtils.isBlank(serviceUrl)) {
            return FAILURE_PREFIX + " service url is not configured.";
        }
        try {
            Map<String, Object> payload = new HashMap<>();
            payload.put("templateType", templateType);
            payload.put("dateRange", dateRange);
            payload.put("events", compactEvents(events));
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(serviceUrl))
                .timeout(Duration.ofSeconds(90))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)))
                .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return FAILURE_PREFIX + " HTTP " + response.statusCode() + " " + response.body();
            }
            JsonNode root = objectMapper.readTree(response.body());
            JsonNode content = root.path("content");
            if (content.isMissingNode() || content.isNull() || StringUtils.isBlank(content.asText())) {
                return FAILURE_PREFIX + " response content is empty.";
            }
            return content.asText();
        } catch (Exception e) {
            return FAILURE_PREFIX + " " + e.getMessage();
        }
    }

    private List<Map<String, Object>> compactEvents(List<RiskEvent> events) {
        if (events == null) {
            return List.of();
        }
        return events.stream()
            .filter(Objects::nonNull)
            .sorted(Comparator.comparing(RiskEvent::getRiskScore, Comparator.nullsLast(Comparator.reverseOrder())))
            .limit(120)
            .map(this::compactEvent)
            .toList();
    }

    private Map<String, Object> compactEvent(RiskEvent event) {
        Map<String, Object> row = new HashMap<>();
        row.put("eventTitle", event.getEventTitle());
        row.put("enterpriseName", event.getEnterpriseName());
        row.put("category", event.getCategory());
        row.put("riskLevel", event.getRiskLevel());
        row.put("status", event.getStatus());
        row.put("sourceName", event.getSourceName());
        row.put("riskScore", event.getRiskScore());
        row.put("occurredAt", event.getOccurredAt());
        row.put("description", event.getDescription());
        return row;
    }
}
