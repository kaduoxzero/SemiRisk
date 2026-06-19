package com.semirisk.service;

import com.semirisk.model.CrawlerSignal;
import com.semirisk.model.DailyRiskSnapshot;
import com.semirisk.model.RiskAlert;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

/**
 * 仪表盘和风险分析服务。
 *
 * 从共享风险状态（DailyRiskSnapshot + CrawlerSignal 构造器）读取数据，
 * 生成仪表盘/KPI、时间序列窗口分析以及带双语翻译的单信号详情视图。
 */
@Service
public class DashboardService {

    private final TranslationService translationService;
    private final GisService gisService;
    private final AlertService alertService;
    private final Supplier<DailyRiskSnapshot> snapshotSupplier;
    private final Supplier<List<CrawlerSignal>> signalsSupplier;

    public DashboardService(TranslationService translationService,
                            GisService gisService,
                            AlertService alertService,
                            @Lazy Supplier<DailyRiskSnapshot> snapshotSupplier,
                            @Lazy Supplier<List<CrawlerSignal>> signalsSupplier) {
        this.translationService = translationService;
        this.gisService = gisService;
        this.alertService = alertService;
        this.snapshotSupplier = snapshotSupplier;
        this.signalsSupplier = signalsSupplier;
    }

    // ---------------------------------------------------------------------
    // 公共 API
    // ---------------------------------------------------------------------

    /**
     * KPI 汇总、热点计数、排名、维度材料和流水线阶段。
     */
    public Map<String, Object> dashboard() {
        DailyRiskSnapshot snapshot = snapshotSupplier.get();
        List<CrawlerSignal> availableSignals = availableSignals();
        long highCount = availableSignals.stream().filter(signal -> signal.riskScore() >= 80).count();
        long handled = publicSignalAlerts().stream().filter(alert -> "处理中".equals(alert.status()) || "已处理".equals(alert.status())).count();
        long totalEvents = availableSignals.size();
        String closureRate = totalEvents == 0 ? "0%" : String.format(Locale.ROOT, "%.1f%%", handled * 100.0 / totalEvents);
        Map<String, Object> dashboard = new LinkedHashMap<>();
        dashboard.put("kpis", List.of(
                Map.of("name", "公开源事件数", "value", availableSignals.size(), "trend", "公开网站"),
                Map.of("name", "今日新增", "value", snapshot.signals().size(), "trend", "爬虫记录"),
                Map.of("name", "高危信号", "value", highCount, "trend", "规则评分"),
                Map.of("name", "闭环处理率", "value", closureRate, "trend", "告警处置")
        ));
        dashboard.put("hotspots", gisService().gisPoints(availableSignals).stream().limit(4).toList());
        dashboard.put("ranking", publicAlerts(availableSignals).stream().limit(5).toList());
        dashboard.put("materials", dimensionScores(availableSignals));
        dashboard.put("stages", availableSignals.isEmpty()
                ? List.of("公开源采集:待采集", "规则评分:待采集", "AI测算:待采集", "处置闭环:待派发")
                : List.of("公开源采集:已完成", "规则评分:" + snapshot.level(), "AI测算:" + snapshot.level(), "处置闭环:待派发"));
        dashboard.put("aiSummary", snapshot.summary());
        dashboard.put("aiReport", latestAiReport());
        dashboard.put("dailyRisk", snapshot);
        dashboard.put("dataMode", availableSignals.isEmpty() ? "WAITING_PUBLIC_SOURCE" : "PUBLIC_CRAWLED");
        dashboard.put("dataSource", "semirisk-data-service 公开 RSS 采集");
        dashboard.put("refreshedAt", Instant.now().toString());
        return dashboard;
    }

    /**
     * 时间序列窗口分析（24小时 / 7天 / 30天）。
     */
    public Map<String, Object> riskAnalysis(String window) {
        String normalizedWindow = normalizeWindow(window);
        String windowLabel = windowLabel(normalizedWindow);
        List<CrawlerSignal> allSignals = availableSignals();
        List<CrawlerSignal> availableSignals = windowedSignals(allSignals, normalizedWindow);
        int score = windowScore(availableSignals, normalizedWindow);
        String level = riskLevel(score);
        long highCount = availableSignals.stream().filter(signal -> signal.riskScore() >= 80).count();
        long midCount = availableSignals.stream().filter(signal -> signal.riskScore() >= 60 && signal.riskScore() < 80).count();
        double average = availableSignals.stream().mapToInt(CrawlerSignal::riskScore).average().orElse(0);
        Map<String, Object> analysis = new LinkedHashMap<>();
        analysis.put("window", normalizedWindow);
        analysis.put("windowLabel", windowLabel);
        analysis.put("score", score);
        analysis.put("level", level);
        analysis.put("summary", availableSignals.isEmpty()
                ? windowLabel + "暂无公开源命中，当前风险研判等待下一轮爬虫刷新。"
                : windowLabel + "纳入 " + availableSignals.size() + " 条公开源信号，综合评分 " + score + "（" + level + "），高危 " + highCount + " 条，中危 " + midCount + " 条。");
        analysis.put("dimensions", dimensionScores(availableSignals));
        analysis.put("sources", sourceScores(availableSignals));
        analysis.put("metrics", Map.of(
                "signalCount", availableSignals.size(),
                "allSignalCount", allSignals.size(),
                "highCount", highCount,
                "midCount", midCount,
                "avgScore", Math.round(average),
                "sourceCount", availableSignals.stream().map(CrawlerSignal::source).distinct().count(),
                "dimensionCount", availableSignals.stream().map(CrawlerSignal::dimension).distinct().count()
        ));
        analysis.put("timeline", availableSignals.stream().limit(8).map(signal -> Map.<String, Object>of(
                "time", signal.fetchedAt().toString(),
                "source", signal.source(),
                "dimension", signal.dimension(),
                "score", signal.riskScore(),
                "title", signal.title(),
                "url", signal.sourceUrl()
        )).toList());
        analysis.put("reasoning", availableSignals.isEmpty()
                ? List.of("数据输入: 公开源暂无成功采集记录", "逻辑关联: 暂停自动推理", "风险结论: 等待下一次爬虫刷新")
                : List.of(
                "时间窗口: " + windowLabel + "，使用 " + windowMethod(normalizedWindow),
                "风险密度: " + availableSignals.size() + " 条信号来自 " + availableSignals.stream().map(CrawlerSignal::source).distinct().count() + " 个公开源",
                "维度聚焦: " + topDimension(availableSignals),
                "最高信号: " + availableSignals.get(0).source() + " / " + availableSignals.get(0).title()
        ));
        analysis.put("solutions", List.of(
                Map.of("name", "人工复核公开源原文", "feasibility", availableSignals.isEmpty() ? 0 : ("24h".equals(normalizedWindow) ? 94 : 88), "owner", "风险分析师", "deadline", "24h".equals(normalizedWindow) ? "2小时内" : "当日"),
                Map.of("name", "将高危信号转为告警工单", "feasibility", availableSignals.isEmpty() ? 0 : 86, "owner", "预警运营", "deadline", "1个工作日"),
                Map.of("name", "按维度同步采购/物流负责人", "feasibility", availableSignals.isEmpty() ? 0 : ("30d".equals(normalizedWindow) ? 91 : 78), "owner", "供应链协同", "deadline", "周会前")
        ));
        return analysis;
    }

    /**
     * 带双语翻译的详细信号信息。
     */
    public Map<String, Object> riskDetail(String id) {
        DailyRiskSnapshot snapshot = snapshotSupplier.get();
        if (snapshot == null) {
            return buildMissingDetail(id);
        }
        Optional<CrawlerSignal> signal = snapshot.signals().stream().filter(item -> item.id().equals(id)).findFirst();
        if (signal.isPresent()) {
            CrawlerSignal current = signal.get();
            String level = riskLevel(current.riskScore());
            Map<String, String> translation = translationService.titleTranslation(current.title());
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("alertOnly", true);
            detail.put("id", current.id());
            detail.put("type", current.dimension());
            detail.put("firstSeen", current.fetchedAt().toString());
            detail.put("level", level);
            detail.put("source", current.source());
            detail.put("sourceUrl", current.sourceUrl());
            detail.put("originalTitle", current.title());
            detail.put("title", translation.get("zh"));
            detail.put("titleEn", translation.get("en"));
            detail.put("riskScore", current.riskScore());
            detail.put("crawlerStatus", current.status());
            detail.put("translation", translation);
            detail.put("bilingualRows", List.of(
                    bilingualRow("告警编号", "Alert ID", current.id(), current.id()),
                    bilingualRow("发布时间", "Published At", current.fetchedAt().toString(), current.fetchedAt().toString()),
                    bilingualRow("风险等级", "Risk Level", level, translationService.levelName(level)),
                    bilingualRow("风险维度", "Risk Dimension", current.dimension(), translationService.dimensionName(current.dimension())),
                    bilingualRow("告警状态", "Alert Status", currentStatus(current.id()), translationService.statusName(currentStatus(current.id()))),
                    bilingualRow("公开来源", "Public Source", current.source(), current.source()),
                    bilingualRow("中文译文", "Chinese Translation", translation.get("zh"), translation.get("zh")),
                    bilingualRow("英文原文/译文", "English Original/Translation", translation.get("en"), translation.get("en"))
            ));
            return detail;
        }
        return buildMissingDetail(id);
    }

    // ---------------------------------------------------------------------
    // 从 SemiRiskStore 共享的辅助方法
    // ---------------------------------------------------------------------

    private String currentStatus(String id) {
        // 从 AlertService 读取真实的告警状态（包含 publicAlertStatuses 持久化状态）
        return alertService.currentStatus(id);
    }

    private List<CrawlerSignal> availableSignals() {
        DailyRiskSnapshot snapshot = snapshotSupplier.get();
        if (snapshot == null) {
            return List.of();
        }
        return snapshot.signals().stream()
                .filter(signal -> "OK".equalsIgnoreCase(signal.status()))
                .sorted(Comparator.comparing(CrawlerSignal::riskScore).reversed())
                .toList();
    }

    private List<RiskAlert> publicSignalAlerts() {
        return publicAlerts(availableSignals());
    }

    private List<RiskAlert> publicAlerts(List<CrawlerSignal> signals) {
        return signals.stream()
                .sorted(Comparator.comparing(CrawlerSignal::riskScore).reversed())
                .map(signal -> new RiskAlert(signal.id(), signal.fetchedAt(), riskLevel(signal.riskScore()), signal.title(), signal.source(), signal.sourceUrl(), currentStatus(signal.id()), "risk-detail.html"))
                .toList();
    }

    private String normalizeWindow(String window) {
        if ("7d".equalsIgnoreCase(window)) {
            return "7d";
        }
        if ("30d".equalsIgnoreCase(window)) {
            return "30d";
        }
        return "24h";
    }

    private String windowLabel(String window) {
        return switch (window) {
            case "7d" -> "近7天";
            case "30d" -> "近30天";
            default -> "近24小时";
        };
    }

    private String windowMethod(String window) {
        return switch (window) {
            case "7d" -> "最高分、平均分与信号密度加权，突出一周内重复出现的风险";
            case "30d" -> "最高分、平均分与维度覆盖度加权，突出月度结构性风险";
            default -> "最高风险信号优先，突出即时告警处置";
        };
    }

    private List<CrawlerSignal> windowedSignals(List<CrawlerSignal> signals, String window) {
        if (signals.isEmpty()) {
            return List.of();
        }
        long hours = switch (window) {
            case "7d" -> 24L * 7L;
            case "30d" -> 24L * 30L;
            default -> 24L;
        };
        Instant cutoff = Instant.now().minus(hours, ChronoUnit.HOURS);
        List<CrawlerSignal> filtered = signals.stream()
                .filter(signal -> !signal.fetchedAt().isBefore(cutoff))
                .toList();
        return filtered.isEmpty() ? signals.stream().limit(Math.min(6, signals.size())).toList() : filtered;
    }

    private int windowScore(List<CrawlerSignal> signals, String window) {
        if (signals.isEmpty()) {
            return 0;
        }
        int max = signals.stream().mapToInt(CrawlerSignal::riskScore).max().orElse(0);
        double avg = signals.stream().mapToInt(CrawlerSignal::riskScore).average().orElse(0);
        long high = signals.stream().filter(signal -> signal.riskScore() >= 80).count();
        long dimensions = signals.stream().map(CrawlerSignal::dimension).distinct().count();
        int score = switch (window) {
            case "7d" -> (int) Math.round(max * 0.68 + avg * 0.32 + Math.min(8, high * 2));
            case "30d" -> (int) Math.round(max * 0.55 + avg * 0.25 + Math.min(18, dimensions * 4));
            default -> max;
        };
        return Math.max(0, Math.min(100, score));
    }

    private List<Map<String, Object>> dimensionScores(List<CrawlerSignal> signals) {
        Map<String, Integer> scores = new LinkedHashMap<>();
        for (CrawlerSignal signal : signals) {
            scores.merge(signal.dimension(), signal.riskScore(), Math::max);
        }
        return scores.entrySet().stream()
                .map(entry -> Map.<String, Object>of("name", entry.getKey(), "index", entry.getValue(), "value", entry.getValue()))
                .toList();
    }

    private List<Map<String, Object>> sourceScores(List<CrawlerSignal> signals) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (CrawlerSignal signal : signals) {
            counts.merge(signal.source(), 1, Integer::sum);
        }
        return counts.entrySet().stream()
                .map(entry -> Map.<String, Object>of("name", entry.getKey(), "value", entry.getValue()))
                .toList();
    }

    private String topDimension(List<CrawlerSignal> signals) {
        return dimensionScores(signals).stream()
                .max(Comparator.comparing(item -> asInt(item.get("value"))))
                .map(item -> item.get("name") + " / " + item.get("value"))
                .orElse("暂无维度");
    }

    private Map<String, Object> bilingualRow(String zhLabel, String enLabel, Object zhValue, Object enValue) {
        return Map.of(
                "zhLabel", zhLabel,
                "enLabel", enLabel,
                "zh", zhValue == null ? "" : String.valueOf(zhValue),
                "en", enValue == null ? "" : String.valueOf(enValue)
        );
    }

    private Map<String, Object> buildMissingDetail(String id) {
        Map<String, Object> missing = new LinkedHashMap<>();
        missing.put("alertOnly", true);
        missing.put("id", id);
        missing.put("type", "待采集");
        missing.put("firstSeen", "");
        missing.put("level", "待采集");
        missing.put("status", "未找到公开源记录");
        missing.put("source", "公开源");
        missing.put("sourceUrl", "");
        missing.put("originalTitle", "公开源暂无匹配记录");
        missing.put("title", "公开源暂无匹配记录");
        missing.put("titleEn", "No matching public-source alert was found");
        missing.put("riskScore", 0);
        missing.put("bilingualRows", List.of(
                bilingualRow("告警编号", "Alert ID", id, id),
                bilingualRow("状态", "Status", "未找到公开源记录", "No matching public-source alert was found")
        ));
        return missing;
    }

    private int asInt(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (Exception ignored) {
            return 0;
        }
    }

    private String riskLevel(int score) {
        if (score >= 80) {
            return "高危";
        }
        if (score >= 60) {
            return "中危";
        }
        if (score > 0) {
            return "低危";
        }
        return "待采集";
    }

    // ---------------------------------------------------------------------
    // 从 SemiRiskStore 委托的依赖
    // ---------------------------------------------------------------------

    private GisService gisService() {
        return gisService;
    }

    private Map<String, Object> latestAiReport() {
        // 通过 SemiRiskStore 桥接委托给 ReportService。
        // 在日报生成前返回待处理占位符。
        Map<String, Object> pending = new LinkedHashMap<>();
        String today = java.time.LocalDate.now().toString();
        pending.put("reportDate", today);
        pending.put("title", "SemiRisk AI 本日风险分析");
        pending.put("pending", true);
        pending.put("generatedAt", Instant.now().toString());
        return pending;
    }
}
