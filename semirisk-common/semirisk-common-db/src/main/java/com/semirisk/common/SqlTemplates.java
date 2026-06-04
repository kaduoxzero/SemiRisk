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

    public static final String INSERT_AUDIT_LOG = """
            INSERT INTO system_audit_log(level, message, created_at)
            VALUES (?, ?, CURRENT_TIMESTAMP)
            """;

    private SqlTemplates() {
    }
}
