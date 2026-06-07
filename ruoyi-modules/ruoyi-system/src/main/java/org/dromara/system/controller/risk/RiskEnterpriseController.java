package org.dromara.system.controller.risk;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.R;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.system.domain.risk.RiskEnterprise;
import org.dromara.system.domain.risk.RiskEvent;
import org.dromara.system.domain.risk.RiskKnowledge;
import org.dromara.system.mapper.risk.RiskEnterpriseMapper;
import org.dromara.system.mapper.risk.RiskEventMapper;
import org.dromara.system.mapper.risk.RiskKnowledgeMapper;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;

@RequiredArgsConstructor
@Tag(name = "Risk Enterprise", description = "Enterprise profile, upload and knowledge search endpoints")
@RestController
@RequestMapping("/risk/enterprise")
public class RiskEnterpriseController {

    private static final long MAX_UPLOAD_SIZE = 10 * 1024 * 1024L;

    private final RiskEnterpriseMapper enterpriseMapper;
    private final RiskEventMapper eventMapper;
    private final RiskKnowledgeMapper knowledgeMapper;

    @GetMapping("/list")
    @Operation(summary = "List risk enterprises")
    public TableDataInfo<RiskEnterprise> list(RiskEnterprise query, PageQuery pageQuery) {
        LambdaQueryWrapper<RiskEnterprise> lqw = Wrappers.lambdaQuery();
        lqw.like(StringUtils.isNotBlank(query.getEnterpriseName()), RiskEnterprise::getEnterpriseName, query.getEnterpriseName());
        lqw.eq(StringUtils.isNotBlank(query.getRiskLevel()), RiskEnterprise::getRiskLevel, query.getRiskLevel());
        lqw.eq(StringUtils.isNotBlank(query.getIndustry()), RiskEnterprise::getIndustry, query.getIndustry());
        lqw.orderByDesc(RiskEnterprise::getRiskScore);
        Page<RiskEnterprise> page = enterpriseMapper.selectPage(pageQuery.build(), lqw);
        return TableDataInfo.build(page);
    }

    @GetMapping("/profile")
    @Operation(summary = "Enterprise risk profile")
    public R<Map<String, Object>> profile(@RequestParam(required = false) String keyword) {
        LambdaQueryWrapper<RiskEnterprise> lqw = Wrappers.lambdaQuery();
        if (StringUtils.isNotBlank(keyword)) {
            lqw.like(RiskEnterprise::getEnterpriseName, keyword).or().like(RiskEnterprise::getCreditCode, keyword);
        }
        lqw.orderByDesc(RiskEnterprise::getRiskScore).last("limit 1");
        RiskEnterprise enterprise = enterpriseMapper.selectOne(lqw);
        List<RiskEvent> events = enterprise == null ? List.of() : eventMapper.selectList(Wrappers.lambdaQuery(RiskEvent.class)
            .eq(RiskEvent::getEnterpriseId, enterprise.getEnterpriseId())
            .orderByDesc(RiskEvent::getRiskScore));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("enterprise", enterprise);
        result.put("events", events);
        result.put("radar", buildRadar(events));
        result.put("relations", buildRelations(enterprise, events));
        return R.ok(result);
    }

    @PostMapping
    @Operation(summary = "Create risk enterprise")
    public R<Void> add(@RequestBody RiskEnterprise enterprise) {
        return enterpriseMapper.insert(enterprise) > 0 ? R.ok() : R.fail();
    }

    @PutMapping
    @Operation(summary = "Update risk enterprise")
    public R<Void> edit(@RequestBody RiskEnterprise enterprise) {
        return enterpriseMapper.updateById(enterprise) > 0 ? R.ok() : R.fail();
    }

    @DeleteMapping("/{enterpriseIds}")
    @Operation(summary = "Delete risk enterprises")
    public R<Void> remove(@PathVariable Long[] enterpriseIds) {
        return enterpriseMapper.deleteByIds(Arrays.asList(enterpriseIds)) > 0 ? R.ok() : R.fail();
    }

    @GetMapping("/kb/search")
    @Operation(summary = "Search risk knowledge base")
    public R<List<RiskKnowledge>> searchKnowledgeBase(@RequestParam("query") String query) {
        if (StringUtils.isBlank(query)) {
            return R.ok(List.of());
        }
        LambdaQueryWrapper<RiskKnowledge> lqw = Wrappers.lambdaQuery();
        lqw.like(RiskKnowledge::getTitle, query)
            .or().like(RiskKnowledge::getKeywords, query)
            .or().like(RiskKnowledge::getContent, query)
            .orderByDesc(RiskKnowledge::getUpdateTime);
        return R.ok(knowledgeMapper.selectList(lqw));
    }

    @PostMapping("/report/upload")
    @Operation(summary = "Upload enterprise running report CSV")
    public R<Map<String, Object>> uploadRunningReport(@RequestParam("file") MultipartFile file) throws Exception {
        validateUploadFile(file);
        int enterpriseCount = 0;
        int eventCount = 0;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            boolean first = true;
            while ((line = reader.readLine()) != null) {
                if (first) {
                    first = false;
                    if (line.toLowerCase(Locale.ROOT).contains("enterprise")) {
                        continue;
                    }
                }
                String[] cols = parseCsvLine(line);
                if (cols.length < 4 || StringUtils.isBlank(cols[0]) || StringUtils.isBlank(cols[1])) {
                    continue;
                }
                RiskEnterprise enterprise = upsertEnterprise(cols);
                enterpriseCount++;
                RiskEvent event = buildEvent(cols, enterprise);
                eventMapper.insert(event);
                eventCount++;
            }
        }
        return R.ok(Map.of("enterpriseRows", enterpriseCount, "eventRows", eventCount));
    }

    private RiskEnterprise upsertEnterprise(String[] cols) {
        String name = value(cols, 0);
        RiskEnterprise enterprise = enterpriseMapper.selectOne(Wrappers.lambdaQuery(RiskEnterprise.class)
            .eq(RiskEnterprise::getEnterpriseName, name)
            .last("limit 1"));
        if (enterprise == null) {
            enterprise = new RiskEnterprise();
            enterprise.setEnterpriseName(name);
            enterprise.setCreditCode(value(cols, 1));
            enterprise.setIndustry(value(cols, 2));
            enterprise.setRegion(value(cols, 3));
            enterprise.setSupplyChainRole(value(cols, 4));
            enterprise.setRiskLevel(value(cols, 7));
            enterprise.setRiskScore(parseInt(value(cols, 10)));
            enterprise.setLongitude(parseDecimal(value(cols, 11)));
            enterprise.setLatitude(parseDecimal(value(cols, 12)));
            enterprise.setStatus("ACTIVE");
            enterpriseMapper.insert(enterprise);
        }
        return enterprise;
    }

    private RiskEvent buildEvent(String[] cols, RiskEnterprise enterprise) {
        RiskEvent event = new RiskEvent();
        event.setEnterpriseId(enterprise.getEnterpriseId());
        event.setEnterpriseName(enterprise.getEnterpriseName());
        event.setEventTitle(value(cols, 5));
        event.setCategory(value(cols, 6));
        event.setRiskLevel(value(cols, 7));
        event.setStatus(StringUtils.blankToDefault(value(cols, 8), "UNRESOLVED"));
        event.setSourceName(value(cols, 9));
        event.setRiskScore(parseDecimal(value(cols, 10)));
        event.setLongitude(parseDecimal(value(cols, 11)));
        event.setLatitude(parseDecimal(value(cols, 12)));
        event.setDescription(value(cols, 13));
        event.setOccurredAt(parseDate(value(cols, 14)));
        event.setEventCode("EV-" + UUID.randomUUID().toString().replace("-", ""));
        return event;
    }

    private void validateUploadFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new ServiceException("upload file must not be empty");
        }
        if (file.getSize() > MAX_UPLOAD_SIZE) {
            throw new ServiceException("upload file size must not exceed 10MB");
        }
        String filename = file.getOriginalFilename();
        if (StringUtils.isBlank(filename) || !filename.toLowerCase(Locale.ROOT).endsWith(".csv")) {
            throw new ServiceException("only CSV files are allowed");
        }
    }

    private String[] parseCsvLine(String line) {
        List<String> values = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (ch == '"') {
                if (quoted && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    current.append('"');
                    i++;
                } else {
                    quoted = !quoted;
                }
            } else if (ch == ',' && !quoted) {
                values.add(current.toString());
                current.setLength(0);
            } else {
                current.append(ch);
            }
        }
        values.add(current.toString());
        return values.toArray(String[]::new);
    }

    private Map<String, Integer> buildRadar(List<RiskEvent> events) {
        Map<String, Integer> radar = new LinkedHashMap<>();
        radar.put("财务信用", avg(events, "财务"));
        radar.put("履约周期", avg(events, "物流"));
        radar.put("合规安全", avg(events, "合规"));
        radar.put("替换冗余", avg(events, "产能"));
        radar.put("质量水平", avg(events, "质量"));
        return radar;
    }

    private List<Map<String, Object>> buildRelations(RiskEnterprise enterprise, List<RiskEvent> events) {
        List<Map<String, Object>> relations = new ArrayList<>();
        if (enterprise != null) {
            relations.add(Map.of("source", enterprise.getEnterpriseName(), "target", "风险事件", "value", events.size()));
            events.stream().limit(8).forEach(e -> relations.add(Map.of("source", enterprise.getEnterpriseName(), "target", e.getEventTitle(), "value", e.getRiskScore())));
        }
        return relations;
    }

    private int avg(List<RiskEvent> events, String category) {
        List<BigDecimal> values = events.stream()
            .filter(e -> StringUtils.contains(e.getCategory(), category))
            .map(RiskEvent::getRiskScore)
            .filter(Objects::nonNull)
            .toList();
        if (values.isEmpty()) {
            return 0;
        }
        return values.stream().reduce(BigDecimal.ZERO, BigDecimal::add)
            .divide(BigDecimal.valueOf(values.size()), 0, java.math.RoundingMode.HALF_UP).intValue();
    }

    private String value(String[] cols, int index) {
        return index >= cols.length ? "" : cols[index].trim();
    }

    private BigDecimal parseDecimal(String value) {
        try {
            return StringUtils.isBlank(value) ? null : new BigDecimal(value);
        } catch (Exception e) {
            return null;
        }
    }

    private Integer parseInt(String value) {
        BigDecimal decimal = parseDecimal(value);
        return decimal == null ? null : decimal.intValue();
    }

    private Date parseDate(String value) {
        if (StringUtils.isBlank(value)) {
            return new Date();
        }
        for (String pattern : List.of("yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd")) {
            try {
                return new SimpleDateFormat(pattern).parse(value);
            } catch (Exception ignored) {
            }
        }
        return new Date();
    }
}
