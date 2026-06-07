package org.dromara.system.domain.risk;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.tenant.core.TenantEntity;

import java.util.Date;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("risk_data_source")
public class RiskDataSource extends TenantEntity {

    @TableId(value = "source_id")
    private Long sourceId;
    private String sourceName;
    private String sourceType;
    private String accessMode;
    private String endpoint;
    private String status;
    private Date lastSyncTime;
    private String remark;
}
