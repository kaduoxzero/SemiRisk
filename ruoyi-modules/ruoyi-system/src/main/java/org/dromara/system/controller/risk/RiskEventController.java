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
import org.dromara.system.domain.risk.RiskEvent;
import org.dromara.system.domain.risk.RiskReport;
import org.dromara.system.mapper.risk.RiskEventMapper;
import org.dromara.system.mapper.risk.RiskReportMapper;
import org.dromara.system.service.risk.RiskAiService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.stream.Collectors;

@RequiredArgsConstructor
@Tag(name = "Risk Event", description = "Risk event lifecycle, KPI, trend, GIS and AI report endpoints")
@RestController
@RequestMapping("/risk/event")
public class RiskEventController {

    private final RiskEventMapper eventMapper;
    private final RiskReportMapper reportMapper;
    private final RiskAiService riskAiService;

    @GetMapping("/list")
    @Operation(summary = "List risk events")
    public TableDataInfo<RiskEvent> list(RiskEvent query, PageQuery pageQuery) {
        LambdaQueryWrapper<RiskEvent> lqw = buildWrapper(query);
        Page<RiskEvent> page = eventMapper.selectPage(pageQuery.build(), lqw);
        return TableDataInfo.build(page);
    }

    @GetMapping("/{eventId:\\d+}")
    @Operation(summary = "Get risk event detail")
    public R<RiskEvent> getInfo(@PathVariable Long eventId) {
        return R.ok(eventMapper.selectById(eventId));
    }

    @PostMapping
    @Operation(summary = "Create risk event")
    public R<Void> add(@RequestBody RiskEvent event) {
        completeScore(event);
        return eventMapper.insert(event) > 0 ? R.ok() : R.fail();
    }

    @PutMapping
    @Operation(summary = "Update risk event")
    public R<Void> edit(@RequestBody RiskEvent event) {
        completeScore(event);
        return eventMapper.updateById(event) > 0 ? R.ok() : R.fail();
    }

    @PutMapping("/handle/{eventId}")
    @Operation(summary = "Handle risk event")
    public R<Void> handle(@PathVariable Long eventId, @RequestBody Map<String, String> body) {
        RiskEvent event = new RiskEvent();
        event.setEventId(eventId);
        event.setStatus(body.getOrDefault("status", "RESOLVING"));
        event.setDisposalSuggestion(body.get("disposalSuggestion"));
        return eventMapper.updateById(event) > 0 ? R.ok() : R.fail();
    }

    @DeleteMapping("/{eventIds:[0-9,]+}")
    @Operation(summary = "Delete risk events")
    public R<Void> remove(@PathVariable Long[] eventIds) {
        return eventMapper.deleteByIds(Arrays.asList(eventIds)) > 0 ? R.ok() : R.fail();
    }

    @GetMapping("/kpis")
    @Operation(summary = "Risk overview KPI")
    public R<Map<String, Object>> kpis() {
        List<RiskEvent> events = eventMapper.selectList(Wrappers.lambdaQuery(RiskEvent.class));
        long total = events.size();
        long today = events.stream().filter(e -> isToday(e.getCreateTime())).count();
        long resolved = events.stream().filter(e -> "RESOLVED".equalsIgnoreCase(e.getStatus())).count();
        BigDecimal avg = events.stream()
            .map(RiskEvent::getRiskScore)
            .filter(Objects::nonNull)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (total > 0) {
            avg = avg.divide(BigDecimal.valueOf(total), 2, RoundingMode.HALF_UP);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", total);
        result.put("today", today);
        result.put("resolved", resolved);
        result.put("resolveRate", total == 0 ? BigDecimal.ZERO : BigDecimal.valueOf(resolved * 100).divide(BigDecimal.valueOf(total), 2, RoundingMode.HALF_UP));
        result.put("currentRiskIndex", avg);
        return R.ok(result);
    }

    @GetMapping("/trend")
    @Operation(summary = "Risk trend")
    public R<List<Map<String, Object>>> trend() {
        List<RiskEvent> events = eventMapper.selectList(Wrappers.lambdaQuery(RiskEvent.class).orderByAsc(RiskEvent::getOccurredAt));
        Map<String, List<RiskEvent>> grouped = events.stream()
            .filter(e -> e.getOccurredAt() != null)
            .collect(Collectors.groupingBy(e -> new java.text.SimpleDateFormat("MM-dd").format(e.getOccurredAt()), LinkedHashMap::new, Collectors.toList()));
        List<Map<String, Object>> result = new ArrayList<>();
        grouped.forEach((day, rows) -> {
            BigDecimal total = rows.stream().map(RiskEvent::getRiskScore).filter(Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal avg = rows.isEmpty() ? BigDecimal.ZERO : total.divide(BigDecimal.valueOf(rows.size()), 2, RoundingMode.HALF_UP);
            result.add(Map.of("date", day, "count", rows.size(), "riskScore", avg));
        });
        return R.ok(result);
    }

    @GetMapping("/gis/nodes")
    @Operation(summary = "GIS risk nodes")
    public R<List<RiskEvent>> gisNodes() {
        return R.ok(eventMapper.selectList(Wrappers.lambdaQuery(RiskEvent.class)
            .isNotNull(RiskEvent::getLongitude)
            .isNotNull(RiskEvent::getLatitude)
            .orderByDesc(RiskEvent::getRiskScore)));
    }

    @PostMapping("/report/generate")
    @Operation(summary = "Generate AI risk report")
    public R<RiskReport> generateReport(@RequestBody Map<String, String> body) {
        String templateType = body.getOrDefault("templateId", body.getOrDefault("templateType", "供应链风险研判报告"));
        String dateRange = body.getOrDefault("dateRange", "全部真实数据");
        String format = body.getOrDefault("format", "markdown");
        List<RiskEvent> events = eventMapper.selectList(Wrappers.lambdaQuery(RiskEvent.class).orderByDesc(RiskEvent::getRiskScore).last("limit 50"));
        RiskReport report = new RiskReport();
        report.setReportTitle("供应链风险研判报告-" + new java.text.SimpleDateFormat("yyyyMMddHHmmss").format(new Date()));
        report.setTemplateType(templateType);
        report.setDateRange(dateRange);
        report.setFormatType(format);
        report.setStatus("GENERATING");
        reportMapper.insert(report);
        String content = riskAiService.generateReport(templateType, dateRange, events);
        report.setContent(content);
        report.setStatus(content.startsWith(RiskAiService.FAILURE_PREFIX) ? "FAILED" : "FINISHED");
        if ("FAILED".equals(report.getStatus())) {
            report.setErrorMessage(content);
        }
        reportMapper.updateById(report);
        return R.ok(report);
    }

    private LambdaQueryWrapper<RiskEvent> buildWrapper(RiskEvent query) {
        LambdaQueryWrapper<RiskEvent> lqw = Wrappers.lambdaQuery();
        lqw.like(StringUtils.isNotBlank(query.getEventTitle()), RiskEvent::getEventTitle, query.getEventTitle());
        lqw.like(StringUtils.isNotBlank(query.getEnterpriseName()), RiskEvent::getEnterpriseName, query.getEnterpriseName());
        lqw.eq(StringUtils.isNotBlank(query.getRiskLevel()), RiskEvent::getRiskLevel, query.getRiskLevel());
        lqw.eq(StringUtils.isNotBlank(query.getStatus()), RiskEvent::getStatus, query.getStatus());
        lqw.eq(StringUtils.isNotBlank(query.getCategory()), RiskEvent::getCategory, query.getCategory());
        lqw.orderByDesc(RiskEvent::getRiskScore).orderByDesc(RiskEvent::getOccurredAt);
        return lqw;
    }

    private void completeScore(RiskEvent event) {
        if (event.getRiskScore() == null) {
            BigDecimal impact = event.getImpactScore() == null ? BigDecimal.ZERO : event.getImpactScore();
            BigDecimal probability = event.getProbabilityScore() == null ? BigDecimal.ZERO : event.getProbabilityScore();
            event.setRiskScore(impact.multiply(probability).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP));
        }
    }

    private boolean isToday(@DateTimeFormat(pattern = "yyyy-MM-dd") Date date) {
        if (date == null) {
            return false;
        }
        Calendar a = Calendar.getInstance();
        Calendar b = Calendar.getInstance();
        b.setTime(date);
        return a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR);
    }
}
