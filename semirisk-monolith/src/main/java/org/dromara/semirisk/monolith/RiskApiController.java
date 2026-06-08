package org.dromara.semirisk.monolith;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;

@RestController
@RequestMapping("/prod-api/risk")
public class RiskApiController {
    private final RiskStore store;
    private final RiskCrawlerService crawlerService;

    public RiskApiController(RiskStore store, RiskCrawlerService crawlerService) {
        this.store = store;
        this.crawlerService = crawlerService;
    }

    @GetMapping("/event/list")
    public PageResponse<RiskEvent> listEvents(
        @RequestParam(defaultValue = "1") int pageNum,
        @RequestParam(defaultValue = "20") int pageSize,
        @RequestParam(required = false) String eventTitle,
        @RequestParam(required = false) String enterpriseName,
        @RequestParam(required = false) String riskLevel,
        @RequestParam(required = false) String status,
        @RequestParam(required = false) String category
    ) {
        Predicate<RiskEvent> filter = event ->
            contains(event.eventTitle, eventTitle)
                && contains(event.enterpriseName, enterpriseName)
                && equalsIfPresent(event.riskLevel, riskLevel)
                && equalsIfPresent(event.status, status)
                && equalsIfPresent(event.category, category);
        List<RiskEvent> rows = store.listEvents(filter);
        return page(rows, pageNum, pageSize);
    }

    @GetMapping("/event/{eventId}")
    public ApiResponse<RiskEvent> getEvent(@PathVariable Long eventId) {
        return ApiResponse.ok(store.getEvent(eventId));
    }

    @PostMapping("/event")
    public ApiResponse<Void> addEvent(@RequestBody RiskEvent event) {
        store.addManualEvent(event);
        return ApiResponse.ok();
    }

    @PutMapping("/event/handle/{eventId}")
    public ApiResponse<Void> handleEvent(@PathVariable Long eventId, @RequestBody Map<String, String> body) {
        store.updateEventStatus(eventId, body.get("status"), body.get("disposalSuggestion"));
        return ApiResponse.ok();
    }

    @GetMapping("/event/kpis")
    public ApiResponse<Map<String, Object>> kpis() {
        List<RiskEvent> events = store.listEvents(event -> true);
        long total = events.size();
        long today = events.stream().filter(event -> isToday(event.createTime)).count();
        long resolved = events.stream().filter(event -> "RESOLVED".equalsIgnoreCase(event.status)).count();
        double average = events.stream()
            .map(event -> event.riskScore)
            .filter(Objects::nonNull)
            .mapToDouble(BigDecimal::doubleValue)
            .average()
            .orElse(0D);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", total);
        result.put("today", today);
        result.put("resolved", resolved);
        result.put("resolveRate", total == 0 ? 0 : Math.round(resolved * 10000D / total) / 100D);
        result.put("currentRiskIndex", Math.round(average * 100D) / 100D);
        return ApiResponse.ok(result);
    }

    @GetMapping("/event/trend")
    public ApiResponse<List<Map<String, Object>>> trend() {
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("MM-dd").withZone(ZoneId.systemDefault());
        Map<String, List<RiskEvent>> grouped = new LinkedHashMap<>();
        store.listEvents(event -> event.occurredAt != null).stream()
            .sorted(Comparator.comparing(event -> event.occurredAt))
            .forEach(event -> grouped.computeIfAbsent(formatter.format(event.occurredAt), key -> new ArrayList<>()).add(event));
        List<Map<String, Object>> rows = new ArrayList<>();
        grouped.forEach((date, events) -> {
            double average = events.stream()
                .map(event -> event.riskScore)
                .filter(Objects::nonNull)
                .mapToDouble(BigDecimal::doubleValue)
                .average()
                .orElse(0D);
            rows.add(Map.of("date", date, "count", events.size(), "riskScore", Math.round(average * 100D) / 100D));
        });
        return ApiResponse.ok(rows);
    }

    @GetMapping("/event/gis/nodes")
    public ApiResponse<List<RiskEvent>> gisNodes() {
        return ApiResponse.ok(store.listEvents(event -> event.longitude != null && event.latitude != null));
    }

    @PostMapping("/event/report/generate")
    public ApiResponse<RiskReport> generateReport(@RequestBody Map<String, String> body) {
        List<RiskEvent> events = store.listEvents(event -> true).stream().limit(30).toList();
        RiskReport report = new RiskReport();
        report.templateType = body.getOrDefault("templateType", "供应链风险研判报告");
        report.dateRange = body.getOrDefault("dateRange", "全部真实数据");
        report.formatType = body.getOrDefault("format", "markdown");
        report.reportTitle = report.templateType + "-" + DateTimeFormatter.ofPattern("yyyyMMddHHmmss").withZone(ZoneId.systemDefault()).format(Instant.now());
        report.status = "FINISHED";
        report.content = buildReportContent(report, events);
        return ApiResponse.ok(store.addReport(report));
    }

    @GetMapping("/enterprise/profile")
    public ApiResponse<Map<String, Object>> enterpriseProfile(@RequestParam(required = false) String keyword) {
        String normalized = normalize(keyword);
        List<RiskEnterprise> enterprises = store.buildEnterprises();
        RiskEnterprise enterprise = enterprises.stream()
            .filter(item -> normalized.isBlank() || contains(item.enterpriseName, normalized) || contains(item.creditCode, normalized))
            .findFirst()
            .orElse(null);
        List<RiskEvent> events = enterprise == null
            ? List.of()
            : store.listEvents(event -> Objects.equals(event.enterpriseName, enterprise.enterpriseName));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("enterprise", enterprise);
        result.put("events", events);
        result.put("radar", radar(events));
        result.put("relations", relations(enterprise, events));
        return ApiResponse.ok(result);
    }

    @PostMapping("/enterprise/report/upload")
    public ApiResponse<Map<String, Object>> uploadCsv(@RequestParam("file") MultipartFile file) throws Exception {
        int enterpriseRows = 0;
        int eventRows = 0;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            boolean first = true;
            while ((line = reader.readLine()) != null) {
                if (first) {
                    first = false;
                    if (line.toLowerCase(Locale.ROOT).contains("enterprise")) {
                        continue;
                    }
                }
                String[] cols = parseCsvLine(line);
                if (cols.length < 6 || blank(cols[0]) || blank(cols[5])) {
                    continue;
                }
                RiskEvent event = new RiskEvent();
                event.enterpriseName = value(cols, 0);
                event.eventTitle = value(cols, 5);
                event.category = value(cols, 6);
                event.riskLevel = value(cols, 7);
                event.status = blank(value(cols, 8)) ? "UNRESOLVED" : value(cols, 8);
                event.sourceName = value(cols, 9);
                event.sourceType = "CSV_UPLOAD";
                event.riskScore = decimal(value(cols, 10));
                event.longitude = decimal(value(cols, 11));
                event.latitude = decimal(value(cols, 12));
                event.description = value(cols, 13);
                event.occurredAt = parseInstant(value(cols, 14));
                store.addManualEvent(event);
                enterpriseRows++;
                eventRows++;
            }
        }
        return ApiResponse.ok(Map.of("enterpriseRows", enterpriseRows, "eventRows", eventRows));
    }

    @GetMapping("/report/list")
    public PageResponse<RiskReport> listReports(@RequestParam(defaultValue = "1") int pageNum, @RequestParam(defaultValue = "20") int pageSize) {
        return page(store.listReports(), pageNum, pageSize);
    }

    @GetMapping("/report/{reportId}")
    public ApiResponse<RiskReport> getReport(@PathVariable Long reportId) {
        return ApiResponse.ok(store.getReport(reportId));
    }

    @GetMapping("/knowledge/list")
    public PageResponse<RiskKnowledge> listKnowledge(
        @RequestParam(defaultValue = "1") int pageNum,
        @RequestParam(defaultValue = "50") int pageSize,
        @RequestParam(required = false) String title,
        @RequestParam(required = false) String query
    ) {
        return page(store.listKnowledge(title == null ? query : title), pageNum, pageSize);
    }

    @GetMapping("/enterprise/kb/search")
    public ApiResponse<List<RiskKnowledge>> searchKnowledge(@RequestParam("query") String query) {
        return ApiResponse.ok(store.listKnowledge(query));
    }

    @PostMapping("/knowledge")
    public ApiResponse<Void> addKnowledge(@RequestBody RiskKnowledge knowledge) {
        store.addKnowledge(knowledge);
        return ApiResponse.ok();
    }

    @GetMapping("/source/list")
    public PageResponse<RiskDataSource> listSources(@RequestParam(defaultValue = "1") int pageNum, @RequestParam(defaultValue = "50") int pageSize) {
        return page(store.listSources(), pageNum, pageSize);
    }

    @PostMapping("/source")
    public ApiResponse<Void> addSource(@RequestBody RiskDataSource source) {
        store.addSource(source);
        return ApiResponse.ok();
    }

    @PostMapping("/crawler/run")
    public ApiResponse<Map<String, Object>> runCrawler() {
        return ApiResponse.ok(crawlerService.crawlNow());
    }

    @GetMapping("/crawler/status")
    public ApiResponse<Map<String, Object>> crawlerStatus() {
        return ApiResponse.ok(crawlerService.status());
    }

    private static <T> PageResponse<T> page(List<T> rows, int pageNum, int pageSize) {
        int safePage = Math.max(pageNum, 1);
        int safeSize = Math.max(Math.min(pageSize, 100), 1);
        int from = Math.min((safePage - 1) * safeSize, rows.size());
        int to = Math.min(from + safeSize, rows.size());
        return new PageResponse<>(rows.subList(from, to), rows.size());
    }

    private static String buildReportContent(RiskReport report, List<RiskEvent> events) {
        StringBuilder builder = new StringBuilder();
        builder.append("# ").append(report.reportTitle).append("\n\n");
        builder.append("- 数据来源：实时爬取与用户上传的真实事件\n");
        builder.append("- 事件总数：").append(events.size()).append("\n");
        long critical = events.stream().filter(event -> "CRITICAL".equals(event.riskLevel)).count();
        builder.append("- 严重事件：").append(critical).append("\n\n");
        builder.append("## 高风险事件\n");
        if (events.isEmpty()) {
            builder.append("暂无真实事件数据。\n");
        } else {
            events.stream().limit(10).forEach(event ->
                builder.append("- [").append(event.riskLevel).append("] ")
                    .append(event.eventTitle).append(" / ")
                    .append(event.enterpriseName).append(" / 风险分 ")
                    .append(event.riskScore).append("\n"));
        }
        return builder.toString();
    }

    private static Map<String, Integer> radar(List<RiskEvent> events) {
        Map<String, Integer> radar = new LinkedHashMap<>();
        radar.put("网络安全", average(events, "网络"));
        radar.put("物流通道", average(events, "物流"));
        radar.put("合规安全", average(events, "合规"));
        radar.put("质量水平", average(events, "质量"));
        radar.put("财务信用", average(events, "财务"));
        return radar;
    }

    private static List<Map<String, Object>> relations(RiskEnterprise enterprise, List<RiskEvent> events) {
        if (enterprise == null) {
            return List.of();
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        rows.add(Map.of("source", enterprise.enterpriseName, "target", "真实风险事件", "value", events.size()));
        events.stream().limit(8).forEach(event -> rows.add(Map.of(
            "source", enterprise.enterpriseName == null ? "" : enterprise.enterpriseName,
            "target", event.eventTitle == null ? "" : event.eventTitle,
            "value", event.riskScore == null ? BigDecimal.ZERO : event.riskScore
        )));
        return rows;
    }

    private static int average(List<RiskEvent> events, String category) {
        return (int) Math.round(events.stream()
            .filter(event -> event.category != null && event.category.contains(category))
            .map(event -> event.riskScore)
            .filter(Objects::nonNull)
            .mapToDouble(BigDecimal::doubleValue)
            .average()
            .orElse(0D));
    }

    private static boolean isToday(Instant instant) {
        if (instant == null) {
            return false;
        }
        return LocalDate.now().equals(LocalDate.ofInstant(instant, ZoneId.systemDefault()));
    }

    private static boolean contains(String value, String query) {
        String normalized = normalize(query);
        return normalized.isBlank() || (value != null && value.toLowerCase(Locale.ROOT).contains(normalized));
    }

    private static boolean equalsIfPresent(String value, String query) {
        return query == null || query.isBlank() || Objects.equals(value, query);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static String value(String[] cols, int index) {
        return index >= cols.length ? "" : cols[index].trim();
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static BigDecimal decimal(String value) {
        try {
            return blank(value) ? null : new BigDecimal(value);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static Instant parseInstant(String value) {
        if (blank(value)) {
            return Instant.now();
        }
        try {
            return Instant.parse(value);
        } catch (Exception ignored) {
            return LocalDate.parse(value).atStartOfDay(ZoneId.systemDefault()).toInstant();
        }
    }

    private static String[] parseCsvLine(String line) {
        List<String> values = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (ch == '"') {
                if (quoted && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    current.append('"');
                    i++;
                } else {
                    quoted = !quoted;
                }
            } else if (ch == ',' && !quoted) {
                values.add(current.toString());
                current.setLength(0);
            } else {
                current.append(ch);
            }
        }
        values.add(current.toString());
        return values.toArray(String[]::new);
    }
}
