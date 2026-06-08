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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
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
    private final Map<String, Instant> customFetchTimes = new HashMap<>();

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
        List<String> errors = new ArrayList<>();
        store.beginBulk();
        try {
            crawlSource("cisaKev", counts, errors, this::crawlCisaKev);
            crawlSource("usgsEarthquake", counts, errors, this::crawlUsgsEarthquakes);
            lastRunAt = started;
            int total = counts.values().stream().mapToInt(Integer::intValue).sum();
            if (errors.isEmpty()) {
                lastStatus = "FINISHED";
                lastMessage = "crawler finished";
            } else if (total > 0) {
                lastStatus = "PARTIAL";
                lastMessage = String.join("; ", errors);
            } else {
                lastStatus = "FAILED";
                lastMessage = String.join("; ", errors);
            }
        } finally {
            store.endBulk();
        }
        Map<String, Object> result = status();
        result.put("counts", counts);
        result.put("errors", errors);
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

    public synchronized Map<String, Object> crawlCustomUri(String url) throws Exception {
        URI uri = URI.create(url);
        if (!List.of("http", "https").contains(uri.getScheme())) {
            throw new IllegalArgumentException("仅支持 HTTP/HTTPS URI");
        }
        Instant now = Instant.now();
        Instant last = customFetchTimes.get(url);
        if (last != null && Duration.between(last, now).getSeconds() < 60) {
            throw new IllegalStateException("同一 URI 至少间隔 60 秒，避免高频爬取");
        }
        customFetchTimes.put(url, now);
        store.beginBulk();
        try {
            String body = get(url);
            JsonNode root = objectMapper.readTree(body);
            int count;
            String type;
            if (root.has("vulnerabilities")) {
                count = importCisaKev(url, root);
                type = "CISA_KEV_COMPAT";
            } else if (root.has("features")) {
                count = importUsgsEarthquakes(url, root);
                type = "GEOJSON_COMPAT";
            } else {
                throw new IllegalArgumentException("暂不支持该 JSON 结构，仅支持 CISA KEV 与 GeoJSON features");
            }
            lastRunAt = now;
            lastStatus = "FINISHED";
            lastMessage = "custom uri crawler finished";
            return Map.of("uri", url, "type", type, "count", count, "rateLimit", "同一 URI 60 秒一次");
        } finally {
            store.endBulk();
        }
    }

    private int crawlCisaKev() throws Exception {
        return crawlCisaKev(properties.getCisaKevUrl());
    }

    private int crawlCisaKev(String url) throws Exception {
        String body = get(url);
        JsonNode root = objectMapper.readTree(body);
        return importCisaKev(url, root);
    }

    private int importCisaKev(String url, JsonNode root) {
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
                event.sourceUrl = url;
                event.occurredAt = parseDate(text(row, "dateAdded"));
                event.description = join(text(row, "product"), text(row, "shortDescription"));
                event.disposalSuggestion = text(row, "requiredAction");
                store.upsertEvent(event);
                count++;
            }
        }
        store.upsertSource("CISA Known Exploited Vulnerabilities", "JSON", url, syncTime);
        return count;
    }

    private void crawlSource(String name, Map<String, Integer> counts, List<String> errors, SourceCrawler crawler) {
        try {
            counts.put(name, crawler.crawl());
        } catch (Exception ex) {
            counts.put(name, 0);
            errors.add(name + ": " + ex.getMessage());
        }
    }

    @FunctionalInterface
    private interface SourceCrawler {
        int crawl() throws Exception;
    }

    private int crawlUsgsEarthquakes() throws Exception {
        return crawlUsgsEarthquakes(properties.getUsgsEarthquakeUrl());
    }

    private int crawlUsgsEarthquakes(String url) throws Exception {
        String body = get(url);
        JsonNode root = objectMapper.readTree(body);
        return importUsgsEarthquakes(url, root);
    }

    private int importUsgsEarthquakes(String url, JsonNode root) {
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
                event.sourceUrl = propertiesNode.path("url").asText(url);
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
        store.upsertSource("USGS Significant Earthquakes", "GeoJSON", url, syncTime);
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
