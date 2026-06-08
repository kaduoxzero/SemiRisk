package org.dromara.semirisk.monolith;

import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;
import java.util.stream.Collectors;

@Service
public class RiskStore {
    private final AtomicLong eventIds = new AtomicLong(1);
    private final AtomicLong reportIds = new AtomicLong(1);
    private final AtomicLong knowledgeIds = new AtomicLong(1);
    private final AtomicLong sourceIds = new AtomicLong(1);
    private final Map<Long, RiskEvent> events = new ConcurrentHashMap<>();
    private final Map<String, Long> eventCodeIndex = new ConcurrentHashMap<>();
    private final Map<Long, RiskReport> reports = new ConcurrentHashMap<>();
    private final Map<Long, RiskKnowledge> knowledge = new ConcurrentHashMap<>();
    private final Map<Long, RiskDataSource> sources = new ConcurrentHashMap<>();

    public RiskEvent upsertEvent(RiskEvent event) {
        if (event.eventCode != null && eventCodeIndex.containsKey(event.eventCode)) {
            Long existingId = eventCodeIndex.get(event.eventCode);
            RiskEvent existing = events.get(existingId);
            if (existing != null) {
                event.eventId = existing.eventId;
                event.createTime = existing.createTime;
            }
        }
        if (event.eventId == null) {
            event.eventId = eventIds.getAndIncrement();
        }
        if (event.createTime == null) {
            event.createTime = Instant.now();
        }
        if (event.status == null || event.status.isBlank()) {
            event.status = "UNRESOLVED";
        }
        if (event.riskLevel == null || event.riskLevel.isBlank()) {
            event.riskLevel = levelOf(event.riskScore);
        }
        events.put(event.eventId, event);
        if (event.eventCode != null) {
            eventCodeIndex.put(event.eventCode, event.eventId);
        }
        return event;
    }

    public RiskEvent addManualEvent(RiskEvent event) {
        event.eventCode = event.eventCode == null || event.eventCode.isBlank()
            ? "MANUAL-" + System.currentTimeMillis() + "-" + eventIds.get()
            : event.eventCode;
        event.occurredAt = event.occurredAt == null ? Instant.now() : event.occurredAt;
        return upsertEvent(event);
    }

    public List<RiskEvent> listEvents(Predicate<RiskEvent> filter) {
        return events.values().stream()
            .filter(filter)
            .sorted(Comparator.comparing((RiskEvent e) -> value(e.riskScore)).reversed()
                .thenComparing((RiskEvent e) -> e.occurredAt == null ? Instant.EPOCH : e.occurredAt, Comparator.reverseOrder()))
            .toList();
    }

    public RiskEvent getEvent(Long id) {
        return events.get(id);
    }

    public void updateEventStatus(Long id, String status, String disposalSuggestion) {
        RiskEvent event = events.get(id);
        if (event != null) {
            event.status = status == null || status.isBlank() ? event.status : status;
            event.disposalSuggestion = disposalSuggestion;
        }
    }

    public RiskReport addReport(RiskReport report) {
        report.reportId = reportIds.getAndIncrement();
        report.createTime = Instant.now();
        reports.put(report.reportId, report);
        return report;
    }

    public List<RiskReport> listReports() {
        return reports.values().stream()
            .sorted(Comparator.comparing((RiskReport r) -> r.createTime == null ? Instant.EPOCH : r.createTime).reversed())
            .toList();
    }

    public RiskReport getReport(Long id) {
        return reports.get(id);
    }

    public RiskKnowledge addKnowledge(RiskKnowledge item) {
        item.knowledgeId = knowledgeIds.getAndIncrement();
        item.createTime = Instant.now();
        item.updateTime = item.createTime;
        if (item.status == null || item.status.isBlank()) {
            item.status = "ACTIVE";
        }
        knowledge.put(item.knowledgeId, item);
        return item;
    }

    public List<RiskKnowledge> listKnowledge(String query) {
        String keyword = normalize(query);
        return knowledge.values().stream()
            .filter(item -> keyword.isBlank()
                || contains(item.title, keyword)
                || contains(item.category, keyword)
                || contains(item.keywords, keyword)
                || contains(item.content, keyword))
            .sorted(Comparator.comparing((RiskKnowledge k) -> k.updateTime == null ? Instant.EPOCH : k.updateTime).reversed())
            .toList();
    }

    public RiskDataSource upsertSource(String name, String type, String endpoint, Instant syncTime) {
        RiskDataSource source = sources.values().stream()
            .filter(item -> Objects.equals(item.sourceName, name))
            .findFirst()
            .orElseGet(() -> {
                RiskDataSource created = new RiskDataSource();
                created.sourceId = sourceIds.getAndIncrement();
                created.sourceName = name;
                sources.put(created.sourceId, created);
                return created;
            });
        source.sourceType = type;
        source.accessMode = "HTTP";
        source.endpoint = endpoint;
        source.status = "ACTIVE";
        source.lastSyncTime = syncTime;
        return source;
    }

    public RiskDataSource addSource(RiskDataSource source) {
        source.sourceId = sourceIds.getAndIncrement();
        source.status = source.status == null || source.status.isBlank() ? "ACTIVE" : source.status;
        sources.put(source.sourceId, source);
        return source;
    }

    public List<RiskDataSource> listSources() {
        return sources.values().stream()
            .sorted(Comparator.comparing((RiskDataSource s) -> s.lastSyncTime == null ? Instant.EPOCH : s.lastSyncTime).reversed())
            .toList();
    }

    public List<RiskEnterprise> buildEnterprises() {
        Map<String, List<RiskEvent>> grouped = events.values().stream()
            .filter(event -> event.enterpriseName != null && !event.enterpriseName.isBlank())
            .collect(Collectors.groupingBy(event -> event.enterpriseName, LinkedHashMap::new, Collectors.toList()));
        AtomicLong ids = new AtomicLong(1);
        List<RiskEnterprise> enterprises = new ArrayList<>();
        grouped.forEach((name, rows) -> {
            RiskEnterprise enterprise = new RiskEnterprise();
            enterprise.enterpriseId = ids.getAndIncrement();
            enterprise.enterpriseName = name;
            enterprise.industry = rows.get(0).category;
            enterprise.riskScore = rows.stream().map(row -> value(row.riskScore)).max(Integer::compareTo).orElse(0);
            enterprise.riskLevel = levelOf(BigDecimal.valueOf(enterprise.riskScore));
            enterprise.status = "ACTIVE";
            rows.stream().filter(row -> row.longitude != null && row.latitude != null).findFirst().ifPresent(row -> {
                enterprise.longitude = row.longitude;
                enterprise.latitude = row.latitude;
            });
            enterprises.add(enterprise);
        });
        enterprises.sort(Comparator.comparing((RiskEnterprise e) -> e.riskScore == null ? 0 : e.riskScore).reversed());
        return enterprises;
    }

    private static int value(BigDecimal score) {
        return score == null ? 0 : score.intValue();
    }

    private static String levelOf(BigDecimal score) {
        int value = value(score);
        if (value >= 85) return "CRITICAL";
        if (value >= 60) return "WARNING";
        return "INFO";
    }

    private static boolean contains(String value, String keyword) {
        return value != null && value.toLowerCase().contains(keyword);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase();
    }
}
