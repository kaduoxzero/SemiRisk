package com.semirisk.service;

import com.semirisk.model.CrawlerSignal;
import com.semirisk.model.RiskAlert;
import com.semirisk.repository.PreparedRiskRepository;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Alert management service extracted from {@link SemiRiskStore}.
 * Owns alert state maps and all alert-related CRUD / persistence logic.
 */
public class AlertService {

    private final Map<String, RiskAlert> alerts = new ConcurrentHashMap<>();
    private final Map<String, String> publicAlertStatuses = new ConcurrentHashMap<>();
    private final PreparedRiskRepository repository;

    @Autowired
    public AlertService(PreparedRiskRepository repository) {
        this.repository = repository;
    }

    /** Public view of all alerts (sorted by time descending). */
    public List<RiskAlert> alerts() {
        return alerts.values().stream()
                .sorted(Comparator.comparing(RiskAlert::time).reversed())
                .toList();
    }

    /** Look up a single alert by id. */
    public Optional<RiskAlert> alert(String id) {
        return Optional.ofNullable(alerts.get(id));
    }

    /** Update alert status; if the alert is not in-memory it falls back to public alerts and persists the change. */
    public RiskAlert updateAlertStatus(String id, String status, Supplier<List<CrawlerSignal>> signalSupplier) {
        RiskAlert current = alerts.get(id);
        if (current == null) {
            return publicAlerts(signalSupplier.get()).stream()
                    .filter(alert -> alert.id().equals(id))
                    .findFirst()
                    .map(alert -> {
                        publicAlertStatuses.put(id, status);
                        persistAlertStatus(id, status, alert);
                        return new RiskAlert(alert.id(), alert.time(), alert.level(), alert.title(), alert.source(), alert.sourceUrl(), status, alert.target());
                    })
                    .orElseThrow(() -> new IllegalArgumentException("告警不存在"));
        }
        RiskAlert updated = new RiskAlert(current.id(), current.time(), current.level(), current.title(), current.source(), current.sourceUrl(), status, current.target());
        alerts.put(id, updated);
        return updated;
    }

    /** Build public signal alerts from the given signals, using stored statuses. */
    public List<RiskAlert> publicSignalAlerts(List<CrawlerSignal> signals) {
        return publicAlerts(signals);
    }

    /** Public API: build public signal alerts from the provided signal list supplier. */
    public List<RiskAlert> publicSignalAlerts(Supplier<List<CrawlerSignal>> signalSupplier) {
        return publicAlerts(signalSupplier.get());
    }

    /** Persist alert status to the database. */
    public void persistAlertStatus(String id, String status, RiskAlert alert) {
        try {
            repository.upsertPublicAlert(id, alert.time(), alert.level(), alert.title(),
                    alert.source(), status, "risk-detail.html");
            repository.updateAlertStatus(id, status);
        } catch (Exception ignored) {
        }
    }

    /** Persist public alerts from crawler signals into the database. */
    public void persistPublicAlerts(List<CrawlerSignal> signals) {
        try {
            for (CrawlerSignal s : signals) {
                String status = publicAlertStatuses.getOrDefault(s.id(), "未处理");
                repository.upsertPublicAlert(s.id(), s.fetchedAt(), riskLevel(s.riskScore()),
                        s.title(), s.source(), status, "risk-detail.html");
            }
        } catch (Exception ignored) {
        }
    }

    /** Load alert statuses from the database into local state on startup. */
    public void loadAlertStatusesFromDb() {
        try {
            repository.findAlerts(null, null, null, 500).forEach(row -> {
                String id = stringValue(row.get("id"));
                String status = stringValue(row.get("status"));
                if (!id.isBlank() && !status.isBlank() && !"未处理".equals(status)) {
                    publicAlertStatuses.put(id, status);
                }
            });
        } catch (Exception ignored) {
        }
    }

    // ---- package-private getters for the maps (used by SemiRiskStore) ----

    Map<String, RiskAlert> getAlertsMap() {
        return alerts;
    }

    Map<String, String> getPublicAlertStatusesMap() {
        return publicAlertStatuses;
    }

    // ---- private helpers (extracted from SemiRiskStore) ----

    private List<RiskAlert> publicAlerts(List<CrawlerSignal> signals) {
        return signals.stream()
                .sorted(Comparator.comparing(CrawlerSignal::riskScore).reversed())
                .map(signal -> new RiskAlert(signal.id(), signal.fetchedAt(), riskLevel(signal.riskScore()), signal.title(), signal.source(), signal.sourceUrl(), publicAlertStatuses.getOrDefault(signal.id(), "未处理"), "risk-detail.html"))
                .toList();
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

    private String riskLevel(int score) {
        if (score >= 80) {
            return "高危";
        }
        if (score >= 60) {
            return "中危";
        }
        if (score > 0) {
            return "低危";
        }
        return "待采集";
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
