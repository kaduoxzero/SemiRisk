package org.dromara.semirisk.monolith;

import java.time.Instant;

public class RiskReport {
    public Long reportId;
    public String reportTitle;
    public String templateType;
    public String dateRange;
    public String formatType;
    public String status;
    public String content;
    public String errorMessage;
    public Instant createTime;
}
