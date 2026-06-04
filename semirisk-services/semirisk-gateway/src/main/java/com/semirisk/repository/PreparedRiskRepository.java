package com.semirisk.repository;

import com.semirisk.common.SqlTemplates;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@Repository
public class PreparedRiskRepository {

    private final JdbcTemplate jdbcTemplate;

    public PreparedRiskRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<Map<String, Object>> findAlerts(String keyword, String level, String status, int limit) {
        return jdbcTemplate.queryForList(
                SqlTemplates.FIND_ALERTS,
                keyword, keyword, keyword,
                level, level,
                status, status,
                limit
        );
    }

    public List<Map<String, Object>> findEnterpriseProfiles(String keyword, int limit) {
        return jdbcTemplate.queryForList(
                SqlTemplates.FIND_ENTERPRISE_BY_KEYWORD,
                keyword,
                keyword,
                limit
        );
    }

    public int insertAuditLog(String level, String message) {
        return jdbcTemplate.update(SqlTemplates.INSERT_AUDIT_LOG, level, message);
    }

    public int updateAlertStatus(String id, String status) {
        return jdbcTemplate.update(SqlTemplates.UPDATE_ALERT_STATUS, status, id);
    }

    public List<Map<String, Object>> findSystemUsers() {
        return jdbcTemplate.queryForList(SqlTemplates.FIND_SYSTEM_USERS);
    }

    public int insertSystemUser(String id, String username, String email, String role, String status) {
        return jdbcTemplate.update(SqlTemplates.INSERT_SYSTEM_USER, id, username, email, role, status);
    }

    public int updateSystemUserStatus(String id, String status) {
        return jdbcTemplate.update(SqlTemplates.UPDATE_SYSTEM_USER_STATUS, status, id);
    }

    public int deleteSystemUser(String id) {
        return jdbcTemplate.update(SqlTemplates.DELETE_SYSTEM_USER, id);
    }

    public List<Map<String, Object>> findAiModelConfigs() {
        return jdbcTemplate.queryForList(SqlTemplates.FIND_AI_MODEL_CONFIGS);
    }

    public int upsertAiModelConfig(String model, String endpoint, String maskedApiKey, boolean configured, Instant updatedAt) {
        return jdbcTemplate.update(SqlTemplates.UPSERT_AI_MODEL_CONFIG, model, endpoint, maskedApiKey, configured, updatedAt);
    }

    public List<Map<String, Object>> findUploadTasks(int limit) {
        return jdbcTemplate.queryForList(SqlTemplates.FIND_UPLOAD_TASKS, limit);
    }

    public int insertUploadTask(String id, String filename, long size, String status, int rows, Instant createdAt) {
        return jdbcTemplate.update(SqlTemplates.INSERT_UPLOAD_TASK, id, filename, size, status, rows, createdAt);
    }

    public int updateUploadTask(String id, String status, int rows) {
        return jdbcTemplate.update(SqlTemplates.UPDATE_UPLOAD_TASK, status, rows, id);
    }

    public List<Map<String, Object>> findReportJobs(int limit) {
        return jdbcTemplate.queryForList(SqlTemplates.FIND_REPORT_JOBS, limit);
    }

    public List<Map<String, Object>> findReportJob(String id) {
        return jdbcTemplate.queryForList(SqlTemplates.FIND_REPORT_JOB, id);
    }

    public int upsertReportJob(String id, String template, String language, String format, int threshold, String status, int progress, String step, String downloadUrl, Instant createdAt) {
        return jdbcTemplate.update(SqlTemplates.UPSERT_REPORT_JOB, id, template, language, format, threshold, status, progress, step, downloadUrl, createdAt);
    }
}
