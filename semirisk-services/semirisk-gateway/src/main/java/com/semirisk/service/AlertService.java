package com.semirisk.service;

import com.semirisk.model.CrawlerSignal;
import com.semirisk.model.RiskAlert;
import com.semirisk.repository.PreparedRiskRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * 告警管理服务，从 {@link SemiRiskStore} 中提取。
 * 拥有告警状态映射和所有告警相关的 CRUD / 持久化逻辑。
 */
@Service
public class AlertService {

    private static final Logger log = LoggerFactory.getLogger(AlertService.class);

    private final Map<String, RiskAlert> alerts = new ConcurrentHashMap<>();
    private final Map<String, String> publicAlertStatuses = new ConcurrentHashMap<>();
    private final PreparedRiskRepository repository;

    @Autowired
    public AlertService(PreparedRiskRepository repository) {
        this.repository = repository;
    }

    /** 所有告警的公共视图（按时间降序排列）。 */
    public List<RiskAlert> alerts() {
        return alerts.values().stream()
                .sorted(Comparator.comparing(RiskAlert::time).reversed())
                .toList();
    }

    /** 根据 id 查询单条告警。 */
    public Optional<RiskAlert> alert(String id) {
        return Optional.ofNullable(alerts.get(id));
    }

    /** 更新告警状态；若告警不在内存中则回退到公共告警并持久化变更。 */
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

    /** 根据给定信号构建公共信号告警，使用已存储的状态。 */
    public List<RiskAlert> publicSignalAlerts(List<CrawlerSignal> signals) {
        return publicAlerts(signals);
    }

    /** 公共 API：根据提供的信号列表构造器构建公共信号告警。 */
    public List<RiskAlert> publicSignalAlerts(Supplier<List<CrawlerSignal>> signalSupplier) {
        return publicAlerts(signalSupplier.get());
    }

    /** 将告警状态持久化到数据库。 */
    public void persistAlertStatus(String id, String status, RiskAlert alert) {
        try {
            repository.upsertPublicAlert(id, alert.time(), alert.level(), alert.title(),
                    alert.source(), status, "risk-detail.html");
            repository.updateAlertStatus(id, status);
        } catch (Exception ex) {
            log.warn("Failed to persist alert status to MySQL: {}", ex.getMessage());
        }
    }

    /** 将爬虫信号的公共告警持久化到数据库。 */
    public void persistPublicAlerts(List<CrawlerSignal> signals) {
        try {
            for (CrawlerSignal s : signals) {
                String status = publicAlertStatuses.getOrDefault(s.id(), "未处理");
                repository.upsertPublicAlert(s.id(), s.fetchedAt(), riskLevel(s.riskScore()),
                        s.title(), s.source(), status, "risk-detail.html");
            }
        } catch (Exception ex) {
            log.warn("Failed to persist alert status to MySQL: {}", ex.getMessage());
        }
    }

    /** 启动时从数据库加载告警状态到本地状态。 */
    public void loadAlertStatusesFromDb() {
        try {
            repository.findAlerts(null, null, null, 500).forEach(row -> {
                String id = stringValue(row.get("id"));
                String status = stringValue(row.get("status"));
                if (!id.isBlank() && !status.isBlank() && !"未处理".equals(status)) {
                    publicAlertStatuses.put(id, status);
                }
            });
        } catch (Exception ex) {
            log.warn("Failed to persist alert status to MySQL: {}", ex.getMessage());
        }
    }

    // ---- 包级私有映射 getter（SemiRiskStore 使用）----

    Map<String, RiskAlert> getAlertsMap() {
        return alerts;
    }

    Map<String, String> getPublicAlertStatusesMap() {
        return publicAlertStatuses;
    }

    /** 查询单条告警的当前状态（优先内存 alerts，回退到 publicAlertStatuses，再回退到"未处理"）。 */
    public String currentStatus(String id) {
        RiskAlert alert = alerts.get(id);
        if (alert != null) {
            return alert.status();
        }
        return publicAlertStatuses.getOrDefault(id, "未处理");
    }

    // ---- 私有辅助方法（从 SemiRiskStore 提取）----

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
        } catch (Exception ex) {
            log.debug("Failed to parse int from '{}': {}", value, ex.getMessage());
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
