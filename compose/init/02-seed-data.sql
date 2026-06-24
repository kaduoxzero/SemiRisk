-- SemiRisk 种子数据初始化
-- 包含管理员账户、企业名单、知识文档、告警记录、AI模型配置等

USE semirisk;

-- ============================================================================
-- 1. 默认管理员账户 (admin/admin123)
-- ============================================================================
-- 使用 BCrypt 加密的密码: $2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy
INSERT INTO system_user (id, username, display_name, email, password_hash, role, status, password_updated_at)
VALUES ('U-ADMIN-001', 'admin', '系统管理员', 'admin@semirisk.com',
        '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy', 'ROLE_ADMIN', '启用', NOW())
ON DUPLICATE KEY UPDATE
    display_name = VALUES(display_name),
    email = VALUES(email),
    password_hash = VALUES(password_hash),
    role = VALUES(role),
    status = VALUES(status),
    password_updated_at = VALUES(password_updated_at);

-- 创建分析师账户 (analyst/analyst123)
INSERT INTO system_user (id, username, display_name, email, password_hash, role, status, password_updated_at)
VALUES ('U-ANALYST-001', 'analyst', '风险分析师', 'analyst@semirisk.com',
        '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy', 'ROLE_ANALYST', '启用', NOW())
ON DUPLICATE KEY UPDATE
    display_name = VALUES(display_name),
    email = VALUES(email),
    password_hash = VALUES(password_hash),
    role = VALUES(role),
    status = VALUES(status),
    password_updated_at = VALUES(password_updated_at);

-- 创建操作员账户 (operator/operator123)
INSERT INTO system_user (id, username, display_name, email, password_hash, role, status, password_updated_at)
VALUES ('U-OPERATOR-001', 'operator', '运维操作员', 'operator@semirisk.com',
        '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy', 'ROLE_OPERATOR', '启用', NOW())
ON DUPLICATE KEY UPDATE
    display_name = VALUES(display_name),
    email = VALUES(email),
    password_hash = VALUES(password_hash),
    role = VALUES(role),
    status = VALUES(status),
    password_updated_at = VALUES(password_updated_at);

-- ============================================================================
-- 2. 企业_watchlist_ (8家半导体相关企业)
-- ============================================================================
INSERT INTO enterprise_record (id, name, credit_code, industry, location, risk_score, credit_level,
                               source_mode, registry_status, events_json, signals_json, updated_at)
VALUES
    ('E-TSMC-001', '台积电 (TSMC)', 'TW90478932', '半导体制造', '中国台湾', 15, '良好',
     '公开源事件聚合', '存续', NULL, NULL, NOW()),
    ('E-SMIC-001', '中芯国际 (SMIC)', 'CN91310000600247685P', '半导体制造', '中国大陆', 45, '关注',
     '公开源事件聚合', '存续', NULL, NULL, NOW()),
    ('E-YMTC-001', '长江存储 (YMTC)', 'CN91420000MA49Y2XQ7K', '闪存制造', '中国大陆', 55, '关注',
     '公开源事件聚合', '存续', NULL, NULL, NOW()),
    ('E-ASML-001', '阿斯麦 (ASML)', 'NL273002758B01', '半导体设备', '荷兰', 30, '良好',
     '公开源事件聚合', '存续', NULL, NULL, NOW()),
    ('E-AMAT-001', '应用材料 (Applied Materials)', 'US7112102045', '半导体设备', '美国', 25, '良好',
     '公开源事件聚合', '存续', NULL, NULL, NOW()),
    ('E-NVIDIA-001', '英伟达 (NVIDIA)', 'US67066G1040', 'GPU/AI芯片', '美国', 20, '良好',
     '公开源事件聚合', '存续', NULL, NULL, NOW()),
    ('E-SAMSUNG-001', '三星电子 (Samsung Electronics)', 'KR2708100394801', '半导体制造', '韩国', 35, '良好',
     '公开源事件聚合', '存续', NULL, NULL, NOW()),
    ('E-MAERSK-001', '马士基 (Maersk)', 'DK11085712', '国际物流', '丹麦', 40, '关注',
     '公开源事件聚合', '存续', NULL, NULL, NOW())
ON DUPLICATE KEY UPDATE
    risk_score = VALUES(risk_score),
    credit_level = VALUES(credit_level),
    updated_at = VALUES(updated_at);

-- 同步到 enterprise_profile 表 (企业画像)
INSERT INTO enterprise_profile (id, name, credit_code, risk_score, credit_level)
VALUES
    ('E-TSMC-001', '台积电 (TSMC)', 'TW90478932', 15, '良好'),
    ('E-SMIC-001', '中芯国际 (SMIC)', 'CN91310000600247685P', 45, '关注'),
    ('E-YMTC-001', '长江存储 (YMTC)', 'CN91420000MA49Y2XQ7K', 55, '关注'),
    ('E-ASML-001', '阿斯麦 (ASML)', 'NL273002758B01', 30, '良好'),
    ('E-AMAT-001', '应用材料 (Applied Materials)', 'US7112102045', 25, '良好'),
    ('E-NVIDIA-001', '英伟达 (NVIDIA)', 'US67066G1040', 20, '良好'),
    ('E-SAMSUNG-001', '三星电子 (Samsung Electronics)', 'KR2708100394801', 35, '良好'),
    ('E-MAERSK-001', '马士基 (Maersk)', 'DK11085712', 40, '关注')
ON DUPLICATE KEY UPDATE
    risk_score = VALUES(risk_score),
    credit_level = VALUES(credit_level);

-- ============================================================================
-- 3. 知识文档 (4份SOP文档)
-- ============================================================================
INSERT INTO knowledge_doc (id, category, title, content, source, source_url, dimension, risk_score, fetched_at, status)
VALUES
    ('KD-SOP-001', 'KNOWLEDGE_INTERNAL', '高风险供应链预警响应SOP',
     '当系统检测到供应链风险等级达到"高"时，应在2小时内完成以下响应：1) 通知相关责任人；2) 启动应急预案；3) 评估影响范围；4) 制定应对措施。',
     '内部管理', 'http://internal/sops/high-risk-response', '供应链', 0, NOW(), '已发布'),
    ('KD-SOP-002', 'KNOWLEDGE_INTERNAL', '半导体供应链重点关注领域',
     '半导体供应链重点关注以下领域：1) 晶圆制造产能；2) 光刻机等关键设备；3) EDA工具授权；4) 稀有材料供应；5) 物流运输通道。',
     '内部管理', 'http://internal/sops/focus-areas', '供应链', 0, NOW(), '已发布'),
    ('KD-SOP-003', 'KNOWLEDGE_INTERNAL', '关键词-责任人映射规则',
     '建立关键词与责任人的映射关系：1) "出口管制" -> 法务部；2) "产能限制" -> 采购部；3) "技术封锁" -> 研发部；4) "物流中断" -> 物流部。',
     '内部管理', 'http://internal/sops/responsibility-mapping', '管理', 0, NOW(), '已发布'),
    ('KD-SOP-004', 'KNOWLEDGE_INTERNAL', '管理层报告撰写规范',
     '管理层报告应包含：1) 执行摘要（500字以内）；2) 风险等级分布；3) 重点事件详述；4) 应对建议；5) 数据图表。',
     '内部管理', 'http://internal/sops/report-standards', '管理', 0, NOW(), '已发布')
ON DUPLICATE KEY UPDATE
    title = VALUES(title),
    content = VALUES(content),
    fetched_at = VALUES(fetched_at),
    status = VALUES(status);

-- ============================================================================
-- 4. 示例告警记录
-- ============================================================================
INSERT INTO risk_alert (id, alert_time, level, title, source, status, target, created_at, updated_at)
VALUES
    ('A-DEMO-001', DATE_SUB(NOW(), INTERVAL 2 HOUR), '高', '某国出台新的半导体出口管制措施', '公开情报', '未处理', '全球半导体供应链', NOW(), NOW()),
    ('A-DEMO-002', DATE_SUB(NOW(), INTERVAL 1 HOUR), '中', '主要晶圆代工厂产能利用率下降至85%', '行业报告', '未处理', '晶圆制造', NOW(), NOW()),
    ('A-DEMO-003', NOW(), '低', '某物流通道出现短暂延误', '新闻监测', '未处理', '国际物流', NOW(), NOW())
ON DUPLICATE KEY UPDATE
    alert_time = VALUES(alert_time),
    level = VALUES(level),
    title = VALUES(title),
    source = VALUES(source),
    status = VALUES(status),
    target = VALUES(target),
    updated_at = VALUES(updated_at);

-- ============================================================================
-- 5. AI 模型配置
-- ============================================================================
INSERT INTO ai_model_config (model, endpoint, masked_api_key, configured, updated_at)
VALUES
    ('deepseekv4-pro', 'https://api.deepseek.com/v1', 'sk-************', 1, NOW()),
    ('gpt-4', 'https://api.openai.com/v1', 'sk-************', 0, NOW())
ON DUPLICATE KEY UPDATE
    endpoint = VALUES(endpoint),
    configured = VALUES(configured),
    updated_at = VALUES(updated_at);

-- ============================================================================
-- 6. 系统审计日志
-- ============================================================================
INSERT INTO system_audit_log (level, message, created_at)
VALUES
    ('INFO', '系统初始化完成，种子数据已加载', NOW()),
    ('INFO', '创建默认管理员账户: admin', NOW()),
    ('INFO', '加载8家重点企业监控名单', NOW()),
    ('INFO', '导入4份内部知识文档', NOW()),
    ('INFO', '创建3条示例告警记录', NOW());

-- ============================================================================
-- 7. 密码重置令牌表（如果不存在）
-- ============================================================================
CREATE TABLE IF NOT EXISTS password_reset_tokens (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    token VARCHAR(96) NOT NULL UNIQUE,
    email VARCHAR(255) NOT NULL,
    expires_at DATETIME NOT NULL,
    consumed TINYINT(1) NOT NULL DEFAULT 0,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_password_reset_token (token),
    INDEX idx_password_reset_email (email)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
