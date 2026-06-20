package com.semirisk.util;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Shared utility for report-related helpers used by both {@link com.semirisk.service.ReportService}
 * and {@link com.semirisk.service.SemiRiskStore}.
 *
 * <p>Eliminates duplicate methods: truncate, asInt, stringValue, toInstant, readJson, writeJson,
 * riskLevel, dimensionScores, normalizeReportTemplate, reportTitle, reportPrompt,
 * reportContext, fallbackReportLines.</p>
 */
public final class ReportUtils {

    private static final Logger log = LoggerFactory.getLogger(ReportUtils.class);

    private ReportUtils() {}

    // ---- String / type helpers ----

    public static String truncate(String value, int max) {
        if (value == null) return "";
        return value.length() <= max ? value : value.substring(0, max);
    }

    public static String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    public static int asInt(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (Exception ex) {
            log.debug("Failed to parse int from '{}': {}", value, ex.getMessage());
            return 0;
        }
    }

    public static Instant toInstant(Object value) {
        if (value == null) return Instant.now();
        if (value instanceof Instant instant) return instant;
        if (value instanceof java.sql.Timestamp timestamp) return timestamp.toInstant();
        if (value instanceof java.time.LocalDateTime localDateTime) {
            return localDateTime.atZone(java.time.ZoneId.systemDefault()).toInstant();
        }
        try {
            return Instant.parse(String.valueOf(value));
        } catch (Exception ex) {
            log.debug("Failed to parse Instant from '{}': {}", value, ex.getMessage());
            return Instant.now();
        }
    }

    // ---- JSON helpers ----

    @SuppressWarnings("unchecked")
    public static <T> T readJson(Object value, TypeReference<T> type, T fallback, ObjectMapper mapper) {
        if (value == null) return fallback;
        try {
            return mapper.readValue(String.valueOf(value), type);
        } catch (Exception ex) {
            log.debug("JSON read failed for value: {}", ex.getMessage());
            return fallback;
        }
    }

    public static String writeJson(Object value, ObjectMapper mapper) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception ex) {
            log.warn("JSON write failed for value of type {}: {}",
                    value == null ? "null" : value.getClass().getSimpleName(), ex.getMessage());
            return "[]";
        }
    }

    // ---- Risk level helpers ----

    public static String riskLevel(int score) {
        if (score >= 80) return "高危";
        if (score >= 60) return "中危";
        if (score > 0) return "低危";
        return "待采集";
    }

    public static String riskLevelLabel(int score) {
        if (score >= 75) return "高危信号";
        if (score >= 60) return "中危信号";
        return "监控信号";
    }

    // ---- Report template helpers ----

    public static String normalizeReportTemplate(String template) {
        if ("supply-chain".equalsIgnoreCase(template)) return "supply-chain";
        if ("enterprise-dd".equalsIgnoreCase(template)) return "enterprise-dd";
        return "risk-assessment";
    }

    public static String reportTitle(String type) {
        return switch (normalizeReportTemplate(type)) {
            case "supply-chain" -> "SemiRisk AI 供应链分析报告";
            case "enterprise-dd" -> "SemiRisk AI 企业尽调报告";
            default -> "SemiRisk AI 风险评估报告";
        };
    }

    public static String reportPrompt(String type, String language) {
        String lang = language == null || language.isBlank() ? "中文" : language;
        String base = """
                你是一名资深半导体供应链风险顾问，为企业高管撰写决策级风险报告。
                要求：
                1. 直接给出结论，不要有"以下是""根据您提供的""以下为"等引导语。
                2. 不使用任何 Markdown 符号（不用#、*、**、-、`、---）。纯文字段落，每段以中文编号开头（一、二、三...）。
                3. 必须明确指出是哪些具体信号/事件/政策导致了当前分数升高或降低，给出具体来源和标题。
                4. 每条建议必须可操作，给出负责部门（采购/供应链/法务/财务/高管）和处置时限（24h/3天/7天/下季度）。
                5. 报告长度：8-12个自然段，每段3-5句话。
                """;
        return switch (normalizeReportTemplate(type)) {
            case "supply-chain" -> base + "撰写语言：" + lang + "。\n报告类型：供应链韧性分析报告。聚焦：物流路径中断风险、关键供应商集中度、库存安全水位、替代采购方案和跨部门协同行动计划。";
            case "enterprise-dd" -> base + "撰写语言：" + lang + "。\n报告类型：企业尽调风险报告。聚焦：企业主体资质、公开源负面事件、经营稳定性信号、合作风险评级（低/中/高）、具体合作条款建议和需人工补充核验的信息清单。";
            default -> base + "撰写语言：" + lang + "。\n报告类型：综合风险评估报告。聚焦：当前综合评分的驱动因素（哪些信号拉高/拉低了分数）、各维度风险排名、未来7-30天走势研判、优先级排序的处置行动清单。";
        };
    }
}
