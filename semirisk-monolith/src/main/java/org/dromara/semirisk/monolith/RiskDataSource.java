package org.dromara.semirisk.monolith;

import java.time.Instant;

public class RiskDataSource {
    public Long sourceId;
    public String sourceName;
    public String sourceType;
    public String accessMode;
    public String endpoint;
    public String status;
    public Instant lastSyncTime;
    public String remark;
}
