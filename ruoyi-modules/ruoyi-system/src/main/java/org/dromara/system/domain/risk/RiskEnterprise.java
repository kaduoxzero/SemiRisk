package org.dromara.system.domain.risk;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.tenant.core.TenantEntity;

import java.math.BigDecimal;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("risk_enterprise")
public class RiskEnterprise extends TenantEntity {

    @TableId(value = "enterprise_id")
    private Long enterpriseId;
    private String enterpriseName;
    private String creditCode;
    private String industry;
    private String region;
    private String supplyChainRole;
    private BigDecimal longitude;
    private BigDecimal latitude;
    private Integer riskScore;
    private String riskLevel;
    private String status;
    private String remark;
}
