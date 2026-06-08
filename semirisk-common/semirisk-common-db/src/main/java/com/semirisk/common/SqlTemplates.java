package com.semirisk.common;

public final class SqlTemplates {

    public static final String FIND_ALERTS = """
            SELECT id, alert_time AS time, level, title, source, status, target
            FROM risk_alert
            WHERE (? IS NULL OR title LIKE CONCAT('%', ?, '%') OR source LIKE CONCAT('%', ?, '%'))
              AND (? IS NULL OR level = ?)
              AND (? IS NULL OR status = ?)
            ORDER BY alert_time DESC
            LIMIT ?
            """;

    public static final String FIND_ENTERPRISE_BY_KEYWORD = """
            SELECT id, name, credit_code, risk_score, credit_level
            FROM enterprise_profile
            WHERE name LIKE CONCAT('%', ?, '%')
               OR credit_code LIKE CONCAT('%', ?, '%')
            ORDER BY risk_score DESC
            LIMIT ?
            """;

    public static final String UPDATE_ALERT_STATUS = """
            UPDATE risk_alert
            SET status = ?
            WHERE id = ?
            """;

    public static final String FIND_SYSTEM_USERS = """
            SELECT id, username, display_name AS displayName, email, role, status, last_login_at AS lastLoginAt
            FROM system_user
            ORDER BY username
            """;

    public static final String INSERT_SYSTEM_USER = """
            INSERT INTO system_user(id, username, display_name, email, password_hash, role, status, password_updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
            """;

    public static final String UPSERT_SYSTEM_LOGIN_USER = """
            INSERT INTO system_user(id, username, display_name, email, password_hash, role, status, password_updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
            ON DUPLICATE KEY UPDATE
              display_name = VALUES(display_name),
              email = VALUES(email),
              password_hash = VALUES(password_hash),
              role = VALUES(role),
              status = VALUES(status),
              password_updated_at = CURRENT_TIMESTAMP
            """;

    public static final String FIND_AUTH_USER_BY_USERNAME = """
            SELECT id, username, display_name AS displayName, email, password_hash AS passwordHash, role, status
            FROM system_user
            WHERE username = ?
            LIMIT 1
            """;

    public static final String COUNT_LOGIN_USERS = """
            SELECT COUNT(*)
            FROM system_user
            WHERE password_hash IS NOT NULL AND password_hash <> ''
            """;

    public static final String COUNT_SYSTEM_USER_BY_EMAIL = """
            SELECT COUNT(*)
            FROM system_user
            WHERE email = ?
            """;

    public static final String UPDATE_SYSTEM_USER_LAST_LOGIN = """
            UPDATE system_user
            SET last_login_at = CURRENT_TIMESTAMP
            WHERE id = ?
            """;

    public static final String UPDATE_SYSTEM_USER_STATUS = """
            UPDATE system_user
            SET status = ?
            WHERE id = ?
            """;

    public static final String DELETE_SYSTEM_USER = """
            DELETE FROM system_user
            WHERE id = ?
            """;

    public static final String FIND_AI_MODEL_CONFIGS = """
            SELECT model, endpoint, masked_api_key AS maskedApiKey, configured, updated_at AS updatedAt
            FROM ai_model_config
            ORDER BY model
            """;

    public static final String UPSERT_AI_MODEL_CONFIG = """
            INSERT INTO ai_model_config(model, endpoint, masked_api_key, configured, updated_at)
            VALUES (?, ?, ?, ?, ?)
            ON DUPLICATE KEY UPDATE
              endpoint = VALUES(endpoint),
              masked_api_key = VALUES(masked_api_key),
              configured = VALUES(configured),
              updated_at = VALUES(updated_at)
            """;

    public static final String FIND_UPLOAD_TASKS = """
            SELECT id, filename, file_size AS size, status, created_at AS createdAt, rows_count AS rows
            FROM upload_task
            ORDER BY created_at DESC
            LIMIT ?
            """;

    public static final String INSERT_UPLOAD_TASK = """
            INSERT INTO upload_task(id, filename, file_size, status, rows_count, created_at)
            VALUES (?, ?, ?, ?, ?, ?)
            """;

    public static final String UPDATE_UPLOAD_TASK = """
            UPDATE upload_task
            SET status = ?, rows_count = ?
            WHERE id = ?
            """;

    public static final String FIND_REPORT_JOBS = """
            SELECT id, template, language, format, threshold, status, progress, step, download_url AS downloadUrl, created_at AS createdAt
            FROM report_job
            ORDER BY created_at DESC
            LIMIT ?
            """;

    public static final String FIND_REPORT_JOB = """
            SELECT id, template, language, format, threshold, status, progress, step, download_url AS downloadUrl, created_at AS createdAt
            FROM report_job
            WHERE id = ?
            """;

    public static final String UPSERT_REPORT_JOB = """
            INSERT INTO report_job(id, template, language, format, threshold, status, progress, step, download_url, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON DUPLICATE KEY UPDATE
              status = VALUES(status),
              progress = VALUES(progress),
              step = VALUES(step),
              download_url = VALUES(download_url)
            """;

    public static final String INSERT_AUDIT_LOG = """
            INSERT INTO system_audit_log(level, message, created_at)
            VALUES (?, ?, CURRENT_TIMESTAMP)
            """;

    public static final String FIND_AUDIT_LOGS = """
            SELECT level, message, created_at AS createdAt
            FROM system_audit_log
            ORDER BY created_at DESC
            LIMIT ?
            """;

    public static final String INSERT_AUTH_TOKEN = """
            INSERT INTO auth_token(token, username, display_name, role, issued_at, expires_at)
            VALUES (?, ?, ?, ?, ?, ?)
            ON DUPLICATE KEY UPDATE
              username = VALUES(username),
              display_name = VALUES(display_name),
              role = VALUES(role),
              expires_at = VALUES(expires_at)
            """;

    public static final String FIND_AUTH_TOKEN = """
            SELECT token, username, display_name AS displayName, role, expires_at AS expiresAt
            FROM auth_token
            WHERE token = ?
            LIMIT 1
            """;

    public static final String RENEW_AUTH_TOKEN = """
            UPDATE auth_token
            SET expires_at = ?
            WHERE token = ?
            """;

    public static final String DELETE_AUTH_TOKEN = """
            DELETE FROM auth_token
            WHERE token = ?
            """;

    public static final String DELETE_EXPIRED_AUTH_TOKENS = """
            DELETE FROM auth_token
            WHERE expires_at < ?
            """;

    public static final String UPSERT_CRAWLER_SIGNAL = """
            INSERT INTO crawler_signal(id, source, source_url, title, dimension, category, risk_signal, risk_score, status, fetched_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON DUPLICATE KEY UPDATE
              source = VALUES(source),
              source_url = VALUES(source_url),
              title = VALUES(title),
              dimension = VALUES(dimension),
              category = VALUES(category),
              risk_signal = VALUES(risk_signal),
              risk_score = VALUES(risk_score),
              status = VALUES(status),
              fetched_at = VALUES(fetched_at)
            """;

    public static final String FIND_RECENT_CRAWLER_SIGNALS = """
            SELECT id, source, source_url AS sourceUrl, title, dimension, category, risk_signal AS riskSignal,
                   risk_score AS riskScore, status, fetched_at AS fetchedAt
            FROM crawler_signal
            WHERE status = 'OK' AND fetched_at >= ?
            ORDER BY risk_score DESC, fetched_at DESC
            LIMIT ?
            """;

    public static final String DELETE_OLD_CRAWLER_SIGNALS = """
            DELETE FROM crawler_signal
            WHERE fetched_at < ?
            """;

    public static final String INSERT_RISK_SNAPSHOT = """
            INSERT INTO risk_snapshot(score, level, summary, signal_count, calculated_at)
            VALUES (?, ?, ?, ?, ?)
            """;

    public static final String FIND_LATEST_RISK_SNAPSHOT = """
            SELECT score, level, summary, signal_count AS signalCount, calculated_at AS calculatedAt
            FROM risk_snapshot
            ORDER BY calculated_at DESC
            LIMIT 1
            """;

    public static final String UPSERT_KNOWLEDGE_DOC = """
            INSERT INTO knowledge_doc(id, category, title, content, source, source_url, dimension, risk_score, object_key, fetched_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON DUPLICATE KEY UPDATE
              category = VALUES(category),
              title = VALUES(title),
              content = VALUES(content),
              source = VALUES(source),
              source_url = VALUES(source_url),
              dimension = VALUES(dimension),
              risk_score = VALUES(risk_score),
              object_key = VALUES(object_key),
              fetched_at = VALUES(fetched_at)
            """;

    public static final String FIND_KNOWLEDGE_DOCS_BY_CATEGORY = """
            SELECT id, category, title, content, source, source_url AS sourceUrl, dimension,
                   risk_score AS riskScore, object_key AS objectKey, fetched_at AS fetchedAt
            FROM knowledge_doc
            WHERE category = ?
            ORDER BY fetched_at DESC
            LIMIT ?
            """;

    public static final String FIND_KNOWLEDGE_DOC_BY_ID = """
            SELECT id, category, title, content, source, source_url AS sourceUrl, dimension,
                   risk_score AS riskScore, object_key AS objectKey, fetched_at AS fetchedAt
            FROM knowledge_doc
            WHERE id = ?
            LIMIT 1
            """;

    public static final String COUNT_KNOWLEDGE_DOCS_BY_CATEGORY = """
            SELECT COUNT(*)
            FROM knowledge_doc
            WHERE category = ?
            """;

    public static final String SEARCH_KNOWLEDGE_DOCS = """
            SELECT id, category, title, content, source, source_url AS sourceUrl, dimension,
                   risk_score AS riskScore, object_key AS objectKey, fetched_at AS fetchedAt
            FROM knowledge_doc
            WHERE (? IS NULL OR title LIKE CONCAT('%', ?, '%') OR content LIKE CONCAT('%', ?, '%'))
            ORDER BY risk_score DESC, fetched_at DESC
            LIMIT ?
            """;

    public static final String UPSERT_ENTERPRISE_RECORD = """
            INSERT INTO enterprise_record(id, name, credit_code, industry, location, risk_score, credit_level,
                   source_mode, registry_status, events_json, signals_json, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON DUPLICATE KEY UPDATE
              credit_code = VALUES(credit_code),
              industry = VALUES(industry),
              location = VALUES(location),
              risk_score = VALUES(risk_score),
              credit_level = VALUES(credit_level),
              source_mode = VALUES(source_mode),
              registry_status = VALUES(registry_status),
              events_json = VALUES(events_json),
              signals_json = VALUES(signals_json),
              updated_at = VALUES(updated_at)
            """;

    public static final String FIND_ENTERPRISE_RECORDS = """
            SELECT id, name, credit_code AS creditCode, industry, location, risk_score AS riskScore,
                   credit_level AS creditLevel, source_mode AS sourceMode, registry_status AS registryStatus,
                   events_json AS eventsJson, signals_json AS signalsJson, updated_at AS updatedAt
            FROM enterprise_record
            ORDER BY risk_score DESC
            LIMIT ?
            """;

    public static final String FIND_ENTERPRISE_RECORD_BY_KEYWORD = """
            SELECT id, name, credit_code AS creditCode, industry, location, risk_score AS riskScore,
                   credit_level AS creditLevel, source_mode AS sourceMode, registry_status AS registryStatus,
                   events_json AS eventsJson, signals_json AS signalsJson, updated_at AS updatedAt
            FROM enterprise_record
            WHERE name LIKE CONCAT('%', ?, '%') OR credit_code LIKE CONCAT('%', ?, '%') OR industry LIKE CONCAT('%', ?, '%')
            ORDER BY risk_score DESC
            LIMIT 1
            """;

    public static final String UPSERT_AI_REPORT = """
            INSERT INTO ai_report(report_date, title, model, configured, model_status, summary, recommendation, body_json, generated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON DUPLICATE KEY UPDATE
              title = VALUES(title),
              model = VALUES(model),
              configured = VALUES(configured),
              model_status = VALUES(model_status),
              summary = VALUES(summary),
              recommendation = VALUES(recommendation),
              body_json = VALUES(body_json),
              generated_at = VALUES(generated_at)
            """;

    public static final String FIND_LATEST_AI_REPORT = """
            SELECT report_date AS reportDate, title, model, configured, model_status AS modelStatus,
                   summary, recommendation, body_json AS bodyJson, generated_at AS generatedAt
            FROM ai_report
            ORDER BY generated_at DESC
            LIMIT 1
            """;

    public static final String UPSERT_PUBLIC_ALERT = """
            INSERT INTO risk_alert(id, alert_time, level, title, source, status, target)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            ON DUPLICATE KEY UPDATE
              alert_time = VALUES(alert_time),
              level = VALUES(level),
              title = VALUES(title),
              source = VALUES(source),
              target = VALUES(target)
            """;

    public static final String FIND_ALERT_STATUS = """
            SELECT status
            FROM risk_alert
            WHERE id = ?
            LIMIT 1
            """;

    private SqlTemplates() {
    }
}
