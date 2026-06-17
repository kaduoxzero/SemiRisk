package com.semirisk.config;

import com.semirisk.model.CrawlerSignal;
import com.semirisk.model.DailyRiskSnapshot;
import com.semirisk.service.SemiRiskStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

import java.util.List;
import java.util.function.Supplier;

/**
 * 提供 Supplier Bean，将 SemiRiskStore 的易失状态桥接到注入的服务（ReportService、DashboardService）。
 *
 * 在 SemiRiskStore 引用上使用 @Lazy 打破循环依赖：
 * SemiRiskStore → services → suppliers → SemiRiskStore。
 */
@Configuration
public class GatewayConfig {

    @Bean
    Supplier<DailyRiskSnapshot> dailyRiskSnapshotSupplier(@Lazy SemiRiskStore store) {
        return store::dailyRiskSnapshot;
    }

    @Bean
    Supplier<List<CrawlerSignal>> crawlerSignalsSupplier(@Lazy SemiRiskStore store) {
        return store::availableSignals;
    }
}
