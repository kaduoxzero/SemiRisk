package org.dromara.system.controller.risk;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.R;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.system.domain.risk.RiskReport;
import org.dromara.system.mapper.risk.RiskReportMapper;
import org.springframework.web.bind.annotation.*;

import java.util.Arrays;

@RequiredArgsConstructor
@Tag(name = "Risk Report", description = "AI generated risk report endpoints")
@RestController
@RequestMapping("/risk/report")
public class RiskReportController {

    private final RiskReportMapper reportMapper;

    @GetMapping("/list")
    @Operation(summary = "List risk reports")
    public TableDataInfo<RiskReport> list(PageQuery pageQuery) {
        Page<RiskReport> page = reportMapper.selectPage(pageQuery.build(), Wrappers.lambdaQuery(RiskReport.class).orderByDesc(RiskReport::getCreateTime));
        return TableDataInfo.build(page);
    }

    @GetMapping("/{reportId}")
    @Operation(summary = "Get risk report detail")
    public R<RiskReport> getInfo(@PathVariable Long reportId) {
        return R.ok(reportMapper.selectById(reportId));
    }

    @DeleteMapping("/{reportIds}")
    @Operation(summary = "Delete risk reports")
    public R<Void> remove(@PathVariable Long[] reportIds) {
        return reportMapper.deleteByIds(Arrays.asList(reportIds)) > 0 ? R.ok() : R.fail();
    }
}
