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
    risk_signal VARCHAR(64) NOT NULL,
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS report_job (
    id VARCHAR(64) PRIMARY KEY,
    template VARCHAR(64) NOT NULL,
    language VARCHAR(32) NOT NULL,
    format VARCHAR(32) NOT NULL,
    threshold INT NOT NULL,
    status VARCHAR(32) NOT NULL,
    progress INT NOT NULL,
    step VARCHAR(255) NOT NULL,
    download_url VARCHAR(512) NULL,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_report_job_created_at(created_at),
    INDEX idx_report_job_status(status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS system_user (
    id VARCHAR(64) PRIMARY KEY,
    username VARCHAR(128) NOT NULL,
    display_name VARCHAR(128) NOT NULL,
    email VARCHAR(255) NOT NULL,
    password_hash VARCHAR(255) NULL,
    role VARCHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL,
    last_login_at DATETIME NULL,
    password_updated_at DATETIME NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_system_user_username(username),
    UNIQUE KEY uk_system_user_email(email),
    INDEX idx_system_user_role(role),
    INDEX idx_system_user_status(status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS system_audit_log (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    level VARCHAR(16) NOT NULL,
    message VARCHAR(1000) NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_system_audit_log_level_time(level, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
