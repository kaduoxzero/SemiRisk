package com.semirisk.service;

import com.semirisk.model.CrawlerSignal;
import com.semirisk.model.RiskAlert;
import com.semirisk.repository.PreparedRiskRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

@Service
public class AlertService {

    public static final String STATUS_UNHANDLED = "未处理";
    public static final String STATUS_PROCESSING = "处理中";
    public static final String STATUS_HANDLED = "已处理";
    public static final String STATUS_IGNORED = "已忽略";

    private static final Logger log = LoggerFactory.getLogger(AlertService.class);

    private final Map<String, RiskAlert> alerts = new ConcurrentHashMap<>();
    private final Map<String, String> publicAlertStatuses = new ConcurrentHashMap<>();
    private final PreparedRiskRepository repository;

    @Autowired
    public AlertService(PreparedRiskRepository repository) {
        this.repository = repository;
    }

    public List<RiskAlert> alerts() {
        return alerts.values().stream()
                .sorted(Comparator.comparing(RiskAlert::time).reversed())
                .toList();
    }

    public Optional<RiskAlert> alert(String id) {
        return Optional.ofNullable(alerts.get(id));
    }

    public RiskAlert updateAlertStatus(String id, String status, Supplier<List<CrawlerSignal>> signalSupplier) {
        RiskAlert current = alerts.get(id);
        if (current == null) {
            return publicAlerts(signalSupplier.get()).stream()
                    .filter(alert -> alert.id().equals(id))
                    .findFirst()
                    .map(alert -> updatePublicAlert(alert, status))
                    .orElseThrow(() -> new IllegalArgumentException("告警不存在"));
        }
        RiskAlert updated = new RiskAlert(current.id(), current.time(), current.level(), current.title(),
                current.source(), current.sourceUrl(), status, current.target());
        alerts.put(id, updated);
        persistAlertStatus(id, status, updated);
        return updated;
    }

    public List<RiskAlert> publicSignalAlerts(List<CrawlerSignal> signals) {
        return publicAlerts(signals);
    }

    public List<RiskAlert> publicSignalAlerts(Supplier<List<CrawlerSignal>> signalSupplier) {
        return publicAlerts(signalSupplier.get());
    }

    public void persistAlertStatus(String id, String status, RiskAlert alert) {
        try {
            repository.upsertPublicAlert(id, alert.time(), alert.level(), alert.title(),
                    alert.source(), status, "risk-detail.html");
            repository.updateAlertStatus(id, status);
        } catch (Exception ex) {
            log.warn("Failed to persist alert status to MySQL: {}", ex.getMessage());
        }
    }

    public void persistPublicAlerts(List<CrawlerSignal> signals) {
        try {
            for (CrawlerSignal s : signals) {
                String status = publicAlertStatuses.getOrDefault(s.id(), STATUS_UNHANDLED);
                repository.upsertPublicAlert(s.id(), s.fetchedAt(), riskLevel(s.riskScore()),
                        s.title(), s.source(), status, "risk-detail.html");
            }
        } catch (Exception ex) {
            log.warn("Failed to persist alert status to MySQL: {}", ex.getMessage());
        }
    }

    public void loadAlertStatusesFromDb() {
        try {
            repository.findAlerts(null, null, null, 500).forEach(row -> {
                String id = stringValue(row.get("id"));
                String status = normalizeStatus(stringValue(row.get("status")));
                if (!id.isBlank() && !status.isBlank() && !STATUS_UNHANDLED.equals(status)) {
                    publicAlertStatuses.put(id, status);
                }
            });
        } catch (Exception ex) {
            log.warn("Failed to load alert statuses from MySQL: {}", ex.getMessage());
        }
    }

    Map<String, RiskAlert> getAlertsMap() {
        return alerts;
    }

    Map<String, String> getPublicAlertStatusesMap() {
        return publicAlertStatuses;
    }

    public String currentStatus(String id) {
        RiskAlert alert = alerts.get(id);
        if (alert != null) {
            return normalizeStatus(alert.status());
        }
        return publicAlertStatuses.getOrDefault(id, STATUS_UNHANDLED);
    }

    public boolean isActionable(String id) {
        return STATUS_UNHANDLED.equals(currentStatus(id));
    }

    private RiskAlert updatePublicAlert(RiskAlert alert, String status) {
        publicAlertStatuses.put(alert.id(), status);
        persistAlertStatus(alert.id(), status, alert);
        return new RiskAlert(alert.id(), alert.time(), alert.level(), alert.title(),
                alert.source(), alert.sourceUrl(), status, alert.target());
    }

    private List<RiskAlert> publicAlerts(List<CrawlerSignal> signals) {
        return signals.stream()
                .filter(signal -> signal.riskScore() > 0 && signal.riskScore() != 35)
                .sorted(Comparator.comparing(CrawlerSignal::riskScore).reversed())
                .map(signal -> new RiskAlert(signal.id(), signal.fetchedAt(), riskLevel(signal.riskScore()),
                        signal.title(), signal.source(), signal.sourceUrl(),
                        publicAlertStatuses.getOrDefault(signal.id(), STATUS_UNHANDLED), "risk-detail.html"))
                .toList();
    }

    private String normalizeStatus(String status) {
        if (STATUS_PROCESSING.equals(status) || STATUS_HANDLED.equals(status) || STATUS_IGNORED.equals(status)) {
            return status;
        }
        return STATUS_UNHANDLED;
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
