package com.semirisk.ai;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

@Service
public class AiReportService {

    private final AtomicReference<Map<String, Object>> latestReport = new AtomicReference<>();

    public AiReportService() {
        generateDailyReport();
    }

    @Scheduled(cron = "0 20 0 * * *")
    public void generateDailyReport() {
        latestReport.set(Map.of(
                "title", "SemiRisk AI 本日风险分析",
                "summary", "系统已完成本日爬虫情报聚合、风险测算和处置建议生成。配置 API Key 后可替换为真实大模型调用。",
                "recommendation", "优先检查高危港口、关键供应商现金流和稀有金属安全库存。",
                "generatedAt", Instant.now().toString()
        ));
    }

    public Map<String, Object> latestReport() {
        return latestReport.get();
    }
}

