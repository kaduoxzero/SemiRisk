package org.dromara.system.domain.risk;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.tenant.core.TenantEntity;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("risk_report")
public class RiskReport extends TenantEntity {

    @TableId(value = "report_id")
    private Long reportId;
    private String reportTitle;
    private String templateType;
    private String dateRange;
    private String formatType;
    private String status;
    private String content;
    private String errorMessage;
}
