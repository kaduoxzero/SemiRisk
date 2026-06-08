package org.dromara.semirisk.monolith;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class RiskCrawlerService {
    private final RiskStore store;
    private final CrawlerProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private volatile Instant lastRunAt;
    private volatile String lastStatus = "WAITING";
    private volatile String lastMessage = "";

    public RiskCrawlerService(RiskStore store, CrawlerProperties properties, ObjectMapper objectMapper) {
        this.store = store;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(properties.getRequestTimeoutSeconds()))
            .build();
    }

    @EventListener(ApplicationReadyEvent.class)
    public void crawlOnStartup() {
        if (properties.isStartupEnabled()) {
            crawlNow();
        }
    }

    @Scheduled(fixedDelayString = "${semirisk.crawler.fixed-delay-ms:1800000}")
    public void crawlOnSchedule() {
        crawlNow();
    }

    public synchronized Map<String, Object> crawlNow() {
        Instant started = Instant.now();
        Map<String, Integer> counts = new LinkedHashMap<>();
        try {
            counts.put("cisaKev", crawlCisaKev());
            counts.put("usgsEarthquake", crawlUsgsEarthquakes());
            lastRunAt = started;
            lastStatus = "FINISHED";
            lastMessage = "crawler finished";
        } catch (Exception ex) {
            lastRunAt = started;
            lastStatus = "FAILED";
            lastMessage = ex.getMessage();
        }
        Map<String, Object> result = status();
        result.put("counts", counts);
        return result;
    }

    public Map<String, Object> status() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("lastRunAt", lastRunAt);
        status.put("lastStatus", lastStatus);
        status.put("lastMessage", lastMessage);
        status.put("sources", store.listSources());
        return status;
    }

    private int crawlCisaKev() throws Exception {
        String body = get(properties.getCisaKevUrl());
        JsonNode root = objectMapper.readTree(body);
        JsonNode rows = root.path("vulnerabilities");
        int count = 0;
        Instant syncTime = Instant.now();
        if (rows.isArray()) {
            for (JsonNode row : rows) {
                String cve = text(row, "cveID");
                String title = text(row, "vulnerabilityName");
                if (cve.isBlank() || title.isBlank()) {
                    continue;
                }
                RiskEvent event = new RiskEvent();
                event.eventCode = "CISA-KEV-" + cve;
                event.enterpriseName = text(row, "vendorProject");
                event.eventTitle = cve + " " + title;
                event.category = "网络安全";
                event.riskScore = cisaScore(text(row, "dateAdded"));
                event.riskLevel = event.riskScore.intValue() >= 92 ? "CRITICAL" : "WARNING";
                event.status = "UNRESOLVED";
                event.sourceType = "CISA_KEV";
                event.sourceName = "CISA Known Exploited Vulnerabilities";
                event.sourceUrl = properties.getCisaKevUrl();
                event.occurredAt = parseDate(text(row, "dateAdded"));
                event.description = join(text(row, "product"), text(row, "shortDescription"));
                event.disposalSuggestion = text(row, "requiredAction");
                store.upsertEvent(event);
                count++;
            }
        }
        store.upsertSource("CISA Known Exploited Vulnerabilities", "JSON", properties.getCisaKevUrl(), syncTime);
        return count;
    }

    private int crawlUsgsEarthquakes() throws Exception {
        String body = get(properties.getUsgsEarthquakeUrl());
        JsonNode root = objectMapper.readTree(body);
        JsonNode rows = root.path("features");
        int count = 0;
        Instant syncTime = Instant.now();
        if (rows.isArray()) {
            for (JsonNode row : rows) {
                JsonNode propertiesNode = row.path("properties");
                String title = propertiesNode.path("title").asText("");
                long time = propertiesNode.path("time").asLong(0L);
                if (title.isBlank() || time <= 0) {
                    continue;
                }
                BigDecimal magnitude = BigDecimal.valueOf(propertiesNode.path("mag").asDouble(0D));
                BigDecimal score = magnitude.multiply(BigDecimal.valueOf(15)).min(BigDecimal.valueOf(100)).setScale(2, RoundingMode.HALF_UP);
                RiskEvent event = new RiskEvent();
                event.eventCode = "USGS-" + row.path("id").asText(String.valueOf(time));
                event.enterpriseName = "USGS Geological Hazards";
                event.eventTitle = title;
                event.category = "物流通道";
                event.riskScore = score;
                event.riskLevel = score.intValue() >= 85 ? "CRITICAL" : score.intValue() >= 60 ? "WARNING" : "INFO";
                event.status = "UNRESOLVED";
                event.sourceType = "USGS_GEOJSON";
                event.sourceName = "USGS Significant Earthquakes";
                event.sourceUrl = propertiesNode.path("url").asText(properties.getUsgsEarthquakeUrl());
                event.occurredAt = Instant.ofEpochMilli(time);
                event.description = join(propertiesNode.path("place").asText(""), "Magnitude " + magnitude);
                JsonNode coordinates = row.path("geometry").path("coordinates");
                if (coordinates.isArray() && coordinates.size() >= 2) {
                    event.longitude = BigDecimal.valueOf(coordinates.get(0).asDouble());
                    event.latitude = BigDecimal.valueOf(coordinates.get(1).asDouble());
                }
                store.upsertEvent(event);
                count++;
            }
        }
        store.upsertSource("USGS Significant Earthquakes", "GeoJSON", properties.getUsgsEarthquakeUrl(), syncTime);
        return count;
    }

    private String get(String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(Duration.ofSeconds(properties.getRequestTimeoutSeconds()))
            .header("Accept", "application/json")
            .header("User-Agent", "SemiRisk-Monolith/1.0")
            .GET()
            .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException(url + " returned HTTP " + response.statusCode());
        }
        return response.body();
    }

    private static String text(JsonNode row, String field) {
        return row.path(field).asText("").trim();
    }

    private static Instant parseDate(String value) {
        if (value == null || value.isBlank()) {
            return Instant.now();
        }
        return LocalDate.parse(value).atStartOfDay().toInstant(ZoneOffset.UTC);
    }

    private static BigDecimal cisaScore(String dateAdded) {
        Instant added = parseDate(dateAdded);
        long ageDays = Math.max(0, Duration.between(added, Instant.now()).toDays());
        int score = ageDays <= 30 ? 98 : ageDays <= 180 ? 92 : 88;
        return BigDecimal.valueOf(score);
    }

    private static String join(String first, String second) {
        if (first == null || first.isBlank()) {
            return second == null ? "" : second;
        }
        if (second == null || second.isBlank()) {
            return first;
        }
        return first + " / " + second;
    }
}
