package org.dromara.system.controller.risk;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.R;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.system.domain.risk.RiskDataSource;
import org.dromara.system.mapper.risk.RiskDataSourceMapper;
import org.springframework.web.bind.annotation.*;

import java.util.Arrays;

@RequiredArgsConstructor
@Tag(name = "Risk Data Source", description = "Risk data source management endpoints")
@RestController
@RequestMapping("/risk/source")
public class RiskDataSourceController {

    private final RiskDataSourceMapper sourceMapper;

    @GetMapping("/list")
    @Operation(summary = "List risk data sources")
    public TableDataInfo<RiskDataSource> list(PageQuery pageQuery) {
        Page<RiskDataSource> page = sourceMapper.selectPage(pageQuery.build(), Wrappers.lambdaQuery(RiskDataSource.class).orderByDesc(RiskDataSource::getUpdateTime));
        return TableDataInfo.build(page);
    }

    @PostMapping
    @Operation(summary = "Create risk data source")
    public R<Void> add(@RequestBody RiskDataSource source) {
        return sourceMapper.insert(source) > 0 ? R.ok() : R.fail();
    }

    @PutMapping
    @Operation(summary = "Update risk data source")
    public R<Void> edit(@RequestBody RiskDataSource source) {
        return sourceMapper.updateById(source) > 0 ? R.ok() : R.fail();
    }

    @DeleteMapping("/{sourceIds}")
    @Operation(summary = "Delete risk data sources")
    public R<Void> remove(@PathVariable Long[] sourceIds) {
        return sourceMapper.deleteByIds(Arrays.asList(sourceIds)) > 0 ? R.ok() : R.fail();
    }
}
