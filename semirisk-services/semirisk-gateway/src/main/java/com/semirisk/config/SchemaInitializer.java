package com.semirisk.config;

import com.semirisk.repository.PreparedRiskRepository;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 启动时容错建表。
 *
 * <p>在应用上下文就绪后尝试创建本项目新增的持久化表（全部 {@code CREATE TABLE IF NOT EXISTS}，幂等），
 * 使「所有信息入库 MySQL」无需手动执行 schema 脚本即可生效。MySQL 暂不可达时整段 try/catch 跳过，
 * 不影响 Gateway 在无 VM 情况下启动（与既有本地兜底一致）。</p>
 */
@Component
public class SchemaInitializer {

    private final JdbcTemplate jdbcTemplate;

    public SchemaInitializer(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    private static final List<String> DDL = List.of(
            """
            CREATE TABLE IF NOT EXISTS auth_token (
                token VARCHAR(96) PRIMARY KEY,
                username VARCHAR(128) NOT NULL,
                display_name VARCHAR(128) NOT NULL,
                role VARCHAR(64) NOT NULL,
                issued_at DATETIME NOT NULL,
                expires_at DATETIME NOT NULL,
                INDEX idx_auth_token_expires(expires_at),
                INDEX idx_auth_token_username(username)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """,
            """
            CREATE TABLE IF NOT EXISTS crawler_signal (
                id VARCHAR(64) PRIMARY KEY,
                source VARCHAR(256) NOT NULL,
                source_url VARCHAR(1024) NOT NULL DEFAULT '',
                title VARCHAR(1024) NOT NULL,
                dimension VARCHAR(64) NOT NULL DEFAULT '供应链',
                category VARCHAR(32) NOT NULL DEFAULT '公开情报',
                risk_signal VARCHAR(64) NOT NULL DEFAULT '监控信号',
                risk_score INT NOT NULL DEFAULT 0,
                status VARCHAR(16) NOT NULL DEFAULT 'OK',
                fetched_at DATETIME NOT NULL,
                updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                INDEX idx_crawler_signal_fetched(fetched_at),
                INDEX idx_crawler_signal_category(category),
                INDEX idx_crawler_signal_score(risk_score),
                INDEX idx_crawler_signal_status(status)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """,
            """
            CREATE TABLE IF NOT EXISTS risk_snapshot (
                id BIGINT PRIMARY KEY AUTO_INCREMENT,
                score INT NOT NULL,
                level VARCHAR(16) NOT NULL,
                summary VARCHAR(1024) NOT NULL,
                signal_count INT NOT NULL DEFAULT 0,
                calculated_at DATETIME NOT NULL,
                INDEX idx_risk_snapshot_time(calculated_at)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """,
            """
            CREATE TABLE IF NOT EXISTS knowledge_doc (
                id VARCHAR(64) PRIMARY KEY,
                category VARCHAR(32) NOT NULL,
                title VARCHAR(1024) NOT NULL,
                content TEXT NOT NULL,
                source VARCHAR(256) NOT NULL,
                source_url VARCHAR(1024) NOT NULL DEFAULT '',
                dimension VARCHAR(64) NOT NULL DEFAULT '供应链',
                risk_score INT NOT NULL DEFAULT 0,
                object_key VARCHAR(512) NULL,
                fetched_at DATETIME NOT NULL,
                INDEX idx_knowledge_doc_category(category),
                INDEX idx_knowledge_doc_fetched(fetched_at)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """,
            """
            CREATE TABLE IF NOT EXISTS enterprise_record (
                id VARCHAR(64) PRIMARY KEY,
                name VARCHAR(255) NOT NULL,
                credit_code VARCHAR(64) NOT NULL DEFAULT '',
                industry VARCHAR(128) NOT NULL DEFAULT '',
                location VARCHAR(128) NOT NULL DEFAULT '',
                risk_score INT NOT NULL DEFAULT 0,
                credit_level VARCHAR(16) NOT NULL DEFAULT '待采集',
                source_mode VARCHAR(64) NOT NULL DEFAULT '公开源事件聚合',
                registry_status VARCHAR(32) NOT NULL DEFAULT '待接入权威源',
                events_json JSON NULL,
                signals_json JSON NULL,
                updated_at DATETIME NOT NULL,
                UNIQUE KEY uk_enterprise_record_name(name),
                INDEX idx_enterprise_record_risk(risk_score)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """,
            """
            CREATE TABLE IF NOT EXISTS ai_report (
                report_date VARCHAR(32) PRIMARY KEY,
                title VARCHAR(255) NOT NULL,
                model VARCHAR(128) NOT NULL,
                configured TINYINT(1) NOT NULL DEFAULT 0,
                model_status VARCHAR(512) NOT NULL DEFAULT '',
                summary TEXT NOT NULL,
                recommendation TEXT NOT NULL,
                body_json JSON NULL,
                generated_at DATETIME NOT NULL
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """,
            """
            CREATE TABLE IF NOT EXISTS risk_alert (
                id VARCHAR(64) PRIMARY KEY,
                alert_time DATETIME NOT NULL,
                level VARCHAR(16) NOT NULL,
                title VARCHAR(255) NOT NULL,
                source VARCHAR(128) NOT NULL,
                status VARCHAR(32) NOT NULL,
                target VARCHAR(128) NOT NULL,
                created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                INDEX idx_risk_alert_filter(level, status, alert_time),
                INDEX idx_risk_alert_source(source)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """,
            """
            CREATE TABLE IF NOT EXISTS upload_task (
                id VARCHAR(64) PRIMARY KEY,
                filename VARCHAR(255) NOT NULL,
                file_size BIGINT NOT NULL,
                status VARCHAR(32) NOT NULL,
                rows_count INT NOT NULL DEFAULT 0,
                warnings_json JSON NULL,
                created_at DATETIME NOT NULL,
                updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                INDEX idx_upload_task_created_at(created_at)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """
    );

    @EventListener(ContextRefreshedEvent.class)
    @Order(10)
    public void ensureSchema() {
        for (String ddl : DDL) {
            try {
                jdbcTemplate.execute(ddl);
            } catch (Exception ignored) {
                // MySQL 暂不可达或权限不足时跳过，不影响启动；脚本 script/semirisk-schema.sql 仍可手动应用。
            }
        }
    }
}
