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

    private SqlTemplates() {
    }
}
