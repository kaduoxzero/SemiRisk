package org.dromara.system.domain.risk;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.tenant.core.TenantEntity;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("risk_knowledge")
public class RiskKnowledge extends TenantEntity {

    @TableId(value = "knowledge_id")
    private Long knowledgeId;
    private String title;
    private String category;
    private String keywords;
    private String sourceName;
    private String content;
    private String status;
}
