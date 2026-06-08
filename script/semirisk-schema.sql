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

ALTER TABLE system_user
    ADD COLUMN IF NOT EXISTS display_name VARCHAR(128) NOT NULL DEFAULT '' AFTER username,
    ADD COLUMN IF NOT EXISTS password_hash VARCHAR(255) NULL AFTER email,
    ADD COLUMN IF NOT EXISTS last_login_at DATETIME NULL AFTER status,
    ADD COLUMN IF NOT EXISTS password_updated_at DATETIME NULL AFTER last_login_at;

UPDATE system_user
SET display_name = username
WHERE display_name = '';

DELETE FROM system_user
WHERE username IN ('admin', 'analyst', 'ops')
  AND (password_hash IS NULL OR password_hash = '');

CREATE TABLE IF NOT EXISTS system_audit_log (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    level VARCHAR(16) NOT NULL,
    message VARCHAR(1000) NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_system_audit_log_level_time(level, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Bearer Token 持久化（DB 为事实源，重启/多实例共享生效）
CREATE TABLE IF NOT EXISTS auth_token (
    token VARCHAR(96) PRIMARY KEY,
    username VARCHAR(128) NOT NULL,
    display_name VARCHAR(128) NOT NULL,
    role VARCHAR(64) NOT NULL,
    issued_at DATETIME NOT NULL,
    expires_at DATETIME NOT NULL,
    INDEX idx_auth_token_expires(expires_at),
    INDEX idx_auth_token_username(username)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 实时爬取的公开源信号持久化（真实数据，供 Dashboard/告警/GIS/知识库/风险聚合）
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 每日 AI 风险测算快照持久化
CREATE TABLE IF NOT EXISTS risk_snapshot (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    score INT NOT NULL,
    level VARCHAR(16) NOT NULL,
    summary VARCHAR(1024) NOT NULL,
    signal_count INT NOT NULL DEFAULT 0,
    calculated_at DATETIME NOT NULL,
    INDEX idx_risk_snapshot_time(calculated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 知识库文档：公开情报 / 政策法规（实时爬取） + 内部知识库 SOP（管理维护）
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 企业画像：来自真实公开源事件聚合；权威工商字段待接入（不伪造）
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- AI 本日风险分析报告持久化（真实聚合 + 模型生成）
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
