CREATE DATABASE IF NOT EXISTS semirisk DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE semirisk;

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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS enterprise_profile (
    id VARCHAR(64) PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    credit_code VARCHAR(64) NOT NULL,
    risk_score INT NOT NULL,
    credit_level VARCHAR(16) NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_enterprise_credit_code(credit_code),
    INDEX idx_enterprise_name(name),
    INDEX idx_enterprise_risk_score(risk_score)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS crawler_record (
    id VARCHAR(64) PRIMARY KEY,
    source VARCHAR(512) NOT NULL,
    title VARCHAR(512) NOT NULL,
    signal VARCHAR(64) NOT NULL,
    risk_score INT NOT NULL,
    fetched_at DATETIME NOT NULL,
    INDEX idx_crawler_record_fetched_at(fetched_at),
    INDEX idx_crawler_record_risk_score(risk_score)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS ai_model_config (
    model VARCHAR(128) PRIMARY KEY,
    endpoint VARCHAR(512) NOT NULL,
    masked_api_key VARCHAR(64) NOT NULL,
    configured TINYINT(1) NOT NULL DEFAULT 0,
    updated_at DATETIME NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS system_audit_log (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    level VARCHAR(16) NOT NULL,
    message VARCHAR(1000) NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_system_audit_log_level_time(level, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT INTO risk_alert(id, alert_time, level, title, source, status, target)
VALUES
('RA-20260603-001', NOW() - INTERVAL 40 MINUTE, '高危', '新加坡港拥堵影响封测物料交付', 'GIS Agent', '未处理', 'risk-detail.html'),
('RA-20260603-002', NOW() - INTERVAL 2 HOUR, '中危', '稀有金属报价连续三日上行', 'Price Agent', '处理中', 'risk-detail.html'),
('RA-20260603-003', NOW() - INTERVAL 5 HOUR, '低危', '供应商工商信息发生变更', 'Compliance Agent', '未处理', 'enterprise-profile.html')
ON DUPLICATE KEY UPDATE
alert_time = VALUES(alert_time),
level = VALUES(level),
title = VALUES(title),
source = VALUES(source),
status = VALUES(status),
target = VALUES(target);

INSERT INTO enterprise_profile(id, name, credit_code, risk_score, credit_level)
VALUES
('EP-001', '安芯半导体供应链有限公司', '91310000MA1SEMIR01', 72, 'A'),
('EP-002', '华南晶圆材料有限公司', '91440000MA1SEMIR02', 61, 'A-')
ON DUPLICATE KEY UPDATE
name = VALUES(name),
risk_score = VALUES(risk_score),
credit_level = VALUES(credit_level);
