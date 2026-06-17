package com.semirisk.config;

import com.semirisk.model.CrawlerSignal;
import com.semirisk.model.DailyRiskSnapshot;
import com.semirisk.service.SemiRiskStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;
import org.springframework.context.annotation.Lazy;

import java.util.List;
import java.util.function.Supplier;

/**
 * Provides supplier beans that bridge SemiRiskStore's volatile state
 * to injected services (ReportService, DashboardService).
 *
 * Uses @Lazy on the SemiRiskStore reference to break the circular
 * dependency: SemiRiskStore → services → suppliers → SemiRiskStore.
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
