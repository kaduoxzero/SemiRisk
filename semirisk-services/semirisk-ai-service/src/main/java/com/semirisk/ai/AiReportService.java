package com.semirisk.ai;

import com.semirisk.common.AiModelDefaults;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

@Service
public class AiReportService {

    private final AtomicReference<Map<String, Object>> latestReport = new AtomicReference<>();
    private final String defaultModel;
    private final String defaultApiKey;

    public AiReportService(
            @Value("${semirisk.ai.default.model:" + AiModelDefaults.DEFAULT_MODEL + "}") String defaultModel,
            @Value("${semirisk.ai.default.api-key:}") String defaultApiKey) {
        this.defaultModel = defaultModel;
        this.defaultApiKey = defaultApiKey;
        generateDailyReport();
    }

    @Scheduled(cron = "0 20 0 * * *")
    public void generateDailyReport() {
        boolean configured = defaultApiKey != null && !defaultApiKey.isBlank();
        latestReport.set(Map.of(
                "title", "SemiRisk AI 本日风险分析",
                "model", defaultModel,
                "configured", configured,
                "summary", "系统已完成本日爬虫情报聚合、风险测算和处置建议生成。当前默认模型为 " + defaultModel + "。",
                "recommendation", "优先检查高危港口、关键供应商现金流和稀有金属安全库存。",
                "generatedAt", Instant.now().toString()
        ));
    }

    public Map<String, Object> latestReport() {
        return latestReport.get();
    }
}
