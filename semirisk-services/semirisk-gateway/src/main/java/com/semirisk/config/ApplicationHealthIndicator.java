package com.semirisk.config;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.actuate.jdbc.DataSourceHealthIndicator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;

/**
 * 自定义应用健康检查：汇总 MySQL、Redis 等关键中间件的连通性。
 * 当 SEMIRISK_HEALTH_DB_ENABLED=true 时启用数据库检查。
 */
@Component
@ConditionalOnProperty(name = "management.health.db.enabled", havingValue = "true", matchIfMissing = false)
public class ApplicationHealthIndicator implements HealthIndicator {

    private final DataSource dataSource;

    public ApplicationHealthIndicator(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public Health health() {
        DataSourceHealthIndicator indicator = new DataSourceHealthIndicator(dataSource);
        return indicator.health();
    }
}
