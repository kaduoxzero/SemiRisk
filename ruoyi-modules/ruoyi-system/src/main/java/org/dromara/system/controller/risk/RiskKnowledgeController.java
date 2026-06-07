package org.dromara.system.controller.risk;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.R;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.system.domain.risk.RiskKnowledge;
import org.dromara.system.mapper.risk.RiskKnowledgeMapper;
import org.springframework.web.bind.annotation.*;

import java.util.Arrays;

@RequiredArgsConstructor
@Tag(name = "Risk Knowledge", description = "Risk knowledge base endpoints")
@RestController
@RequestMapping("/risk/knowledge")
public class RiskKnowledgeController {

    private final RiskKnowledgeMapper knowledgeMapper;

    @GetMapping("/list")
    @Operation(summary = "List risk knowledge")
    public TableDataInfo<RiskKnowledge> list(RiskKnowledge query, PageQuery pageQuery) {
        LambdaQueryWrapper<RiskKnowledge> lqw = Wrappers.lambdaQuery();
        lqw.like(StringUtils.isNotBlank(query.getTitle()), RiskKnowledge::getTitle, query.getTitle());
        lqw.eq(StringUtils.isNotBlank(query.getCategory()), RiskKnowledge::getCategory, query.getCategory());
        lqw.orderByDesc(RiskKnowledge::getUpdateTime);
        Page<RiskKnowledge> page = knowledgeMapper.selectPage(pageQuery.build(), lqw);
        return TableDataInfo.build(page);
    }

    @PostMapping
    @Operation(summary = "Create risk knowledge")
    public R<Void> add(@RequestBody RiskKnowledge knowledge) {
        return knowledgeMapper.insert(knowledge) > 0 ? R.ok() : R.fail();
    }

    @PutMapping
    @Operation(summary = "Update risk knowledge")
    public R<Void> edit(@RequestBody RiskKnowledge knowledge) {
        return knowledgeMapper.updateById(knowledge) > 0 ? R.ok() : R.fail();
    }

    @DeleteMapping("/{knowledgeIds}")
    @Operation(summary = "Delete risk knowledge")
    public R<Void> remove(@PathVariable Long[] knowledgeIds) {
        return knowledgeMapper.deleteByIds(Arrays.asList(knowledgeIds)) > 0 ? R.ok() : R.fail();
    }
}
