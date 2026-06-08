package org.dromara.semirisk.monolith;

import java.math.BigDecimal;
import java.time.Instant;

public class RiskEvent {
    public Long eventId;
    public String enterpriseName;
    public String eventTitle;
    public String eventCode;
    public String category;
    public String riskLevel;
    public String status;
    public String sourceType;
    public String sourceName;
    public String sourceUrl;
    public BigDecimal riskScore;
    public BigDecimal longitude;
    public BigDecimal latitude;
    public Instant occurredAt;
    public Instant createTime;
    public String description;
    public String disposalSuggestion;
}
