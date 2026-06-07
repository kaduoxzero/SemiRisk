package org.dromara.system.domain.risk;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.tenant.core.TenantEntity;

import java.math.BigDecimal;
import java.util.Date;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("risk_event")
public class RiskEvent extends TenantEntity {

    @TableId(value = "event_id")
    private Long eventId;
    private Long enterpriseId;
    private String enterpriseName;
    private String eventTitle;
    private String eventCode;
    private String category;
    private String riskLevel;
    private String status;
    private String sourceType;
    private String sourceName;
    private BigDecimal impactScore;
    private BigDecimal probabilityScore;
    private BigDecimal riskScore;
    private BigDecimal longitude;
    private BigDecimal latitude;
    private Date occurredAt;
    private String description;
    private String disposalSuggestion;
}
