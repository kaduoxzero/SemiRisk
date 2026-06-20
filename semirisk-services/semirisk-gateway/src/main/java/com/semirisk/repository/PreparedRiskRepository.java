package com.semirisk.repository;

import com.semirisk.common.SqlTemplates;
import com.semirisk.service.MinioStorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.BatchUpdateException;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.HashSet;
import java.util.stream.Collectors;

@Repository
public class PreparedRiskRepository {

    private final JdbcTemplate jdbcTemplate;
    private final MinioStorageService minioStorageService;
    private static final Logger log = LoggerFactory.getLogger(PreparedRiskRepository.class);

    public PreparedRiskRepository(JdbcTemplate jdbcTemplate, MinioStorageService minioStorageService) {
        this.jdbcTemplate = jdbcTemplate;
        this.minioStorageService = minioStorageService;
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

    public int insertSystemUser(String id, String username, String displayName, String email, String passwordHash, String role, String status) {
        return jdbcTemplate.update(SqlTemplates.INSERT_SYSTEM_USER, id, username, displayName, email, passwordHash, role, status);
    }

    public int upsertSystemLoginUser(String id, String username, String displayName, String email, String passwordHash, String role, String status) {
        return jdbcTemplate.update(SqlTemplates.UPSERT_SYSTEM_LOGIN_USER, id, username, displayName, email, passwordHash, role, status);
    }

    public List<Map<String, Object>> findAuthUserByUsername(String username) {
        return jdbcTemplate.queryForList(SqlTemplates.FIND_AUTH_USER_BY_USERNAME, username);
    }

    public int countLoginUsers() {
        Number count = jdbcTemplate.queryForObject(SqlTemplates.COUNT_LOGIN_USERS, Number.class);
        return count == null ? 0 : count.intValue();
    }

    public boolean emailExists(String email) {
        Number count = jdbcTemplate.queryForObject(SqlTemplates.COUNT_SYSTEM_USER_BY_EMAIL, Number.class, email);
        return count != null && count.intValue() > 0;
    }

    public int updateSystemUserLastLogin(String id) {
        return jdbcTemplate.update(SqlTemplates.UPDATE_SYSTEM_USER_LAST_LOGIN, id);
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

    public List<Map<String, Object>> findAuditLogs(int limit) {
        return jdbcTemplate.queryForList(SqlTemplates.FIND_AUDIT_LOGS, limit);
    }

    // --- Bearer Token 持久化 ---
    public int insertAuthToken(String token, String username, String displayName, String role, Instant issuedAt, Instant expiresAt) {
        return jdbcTemplate.update(SqlTemplates.INSERT_AUTH_TOKEN, token, username, displayName, role, issuedAt, expiresAt);
    }

    public List<Map<String, Object>> findAuthToken(String token) {
        return jdbcTemplate.queryForList(SqlTemplates.FIND_AUTH_TOKEN, token);
    }

    public int renewAuthToken(String token, Instant expiresAt) {
        return jdbcTemplate.update(SqlTemplates.RENEW_AUTH_TOKEN, expiresAt, token);
    }

    public int deleteAuthToken(String token) {
        return jdbcTemplate.update(SqlTemplates.DELETE_AUTH_TOKEN, token);
    }

    public int deleteExpiredAuthTokens(Instant now) {
        return jdbcTemplate.update(SqlTemplates.DELETE_EXPIRED_AUTH_TOKENS, now);
    }

    public Set<String> findActiveUsernames() {
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(SqlTemplates.FIND_ACTIVE_USERNAMES);
            return rows.stream()
                    .map(r -> String.valueOf(r.get("username")))
                    .collect(Collectors.toCollection(HashSet::new));
        } catch (Exception ex) {
            return Set.of();
        }
    }

    // --- 实时爬取信号持久化 ---
    public int upsertCrawlerSignal(String id, String source, String sourceUrl, String title, String dimension, String category, String riskSignal, int riskScore, String status, Instant fetchedAt) {
        return jdbcTemplate.update(SqlTemplates.UPSERT_CRAWLER_SIGNAL, id, source, sourceUrl, title, dimension, category, riskSignal, riskScore, status, fetchedAt);
    }

    public List<Map<String, Object>> findRecentCrawlerSignals(Instant since, int limit) {
        return jdbcTemplate.queryForList(SqlTemplates.FIND_RECENT_CRAWLER_SIGNALS, since, limit);
    }

    public int deleteOldCrawlerSignals(Instant before) {
        return jdbcTemplate.update(SqlTemplates.DELETE_OLD_CRAWLER_SIGNALS, before);
    }

    /** 统计数据库中所有 OK 状态的公开源信号总数。 */
    public int countAllOkCrawlerSignals() {
        Number count = jdbcTemplate.queryForObject(SqlTemplates.COUNT_ALL_OK_CRAWLER_SIGNALS, Number.class);
        return count == null ? 0 : count.intValue();
    }

    /** 统计今日 OK 状态的公开源信号数。 */
    public int countTodayOkCrawlerSignals() {
        Number count = jdbcTemplate.queryForObject(SqlTemplates.COUNT_TODAY_OK_CRAWLER_SIGNALS, Number.class);
        return count == null ? 0 : count.intValue();
    }

    // --- 风险快照持久化 ---
    public int insertRiskSnapshot(int score, String level, String summary, int signalCount, Instant calculatedAt) {
        return jdbcTemplate.update(SqlTemplates.INSERT_RISK_SNAPSHOT, score, level, summary, signalCount, calculatedAt);
    }

    public List<Map<String, Object>> findLatestRiskSnapshot() {
        return jdbcTemplate.queryForList(SqlTemplates.FIND_LATEST_RISK_SNAPSHOT);
    }

    // --- 知识库文档持久化 ---
    public int upsertKnowledgeDoc(String id, String category, String title, String content, String source, String sourceUrl, String dimension, int riskScore, String objectKey, Instant fetchedAt) {
        return jdbcTemplate.update(SqlTemplates.UPSERT_KNOWLEDGE_DOC, id, category, title, content, source, sourceUrl, dimension, riskScore, objectKey, fetchedAt);
    }

    public List<Map<String, Object>> findKnowledgeDocsByCategory(String category, int limit) {
        return jdbcTemplate.queryForList(SqlTemplates.FIND_KNOWLEDGE_DOCS_BY_CATEGORY, category, limit);
    }

    public List<Map<String, Object>> findKnowledgeDocById(String id) {
        return jdbcTemplate.queryForList(SqlTemplates.FIND_KNOWLEDGE_DOC_BY_ID, id);
    }

    public int countKnowledgeDocsByCategory(String category) {
        Number count = jdbcTemplate.queryForObject(SqlTemplates.COUNT_KNOWLEDGE_DOCS_BY_CATEGORY, Number.class, category);
        return count == null ? 0 : count.intValue();
    }

    public List<Map<String, Object>> searchKnowledgeDocs(String keyword, int limit) {
        return jdbcTemplate.queryForList(SqlTemplates.SEARCH_KNOWLEDGE_DOCS, keyword, keyword, keyword, limit);
    }

    // --- 企业画像持久化 ---
    public int upsertEnterpriseRecord(String id, String name, String creditCode, String industry, String location, int riskScore, String creditLevel, String sourceMode, String registryStatus, String eventsJson, String signalsJson, Instant updatedAt) {
        return jdbcTemplate.update(SqlTemplates.UPSERT_ENTERPRISE_RECORD, id, name, creditCode, industry, location, riskScore, creditLevel, sourceMode, registryStatus, eventsJson, signalsJson, updatedAt);
    }

    public List<Map<String, Object>> findEnterpriseRecords(int limit) {
        return jdbcTemplate.queryForList(SqlTemplates.FIND_ENTERPRISE_RECORDS, limit);
    }

    public List<Map<String, Object>> findEnterpriseRecordByKeyword(String keyword) {
        return jdbcTemplate.queryForList(SqlTemplates.FIND_ENTERPRISE_RECORD_BY_KEYWORD, keyword, keyword, keyword);
    }

    // --- AI 报告持久化 ---
    public int upsertAiReport(String reportDate, String title, String model, boolean configured, String modelStatus, String summary, String recommendation, String bodyJson, Instant generatedAt) {
        return jdbcTemplate.update(SqlTemplates.UPSERT_AI_REPORT, reportDate, title, model, configured, modelStatus, summary, recommendation, bodyJson, generatedAt);
    }

    public List<Map<String, Object>> findLatestAiReport() {
        return jdbcTemplate.queryForList(SqlTemplates.FIND_LATEST_AI_REPORT);
    }

    // --- 公开源告警持久化 ---
    public int upsertPublicAlert(String id, Instant alertTime, String level, String title, String source, String status, String target) {
        return jdbcTemplate.update(SqlTemplates.UPSERT_PUBLIC_ALERT, id, alertTime, level, title, source, status, target);
    }

    public String findAlertStatus(String id) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(SqlTemplates.FIND_ALERT_STATUS, id);
        return rows.isEmpty() ? null : String.valueOf(rows.get(0).get("status"));
    }

    // ── Password Reset Tokens ──────────────────────────────────────────

    public int insertResetToken(String token, String email, java.time.Instant expiresAt) {
        return jdbcTemplate.update(SqlTemplates.INSERT_RESET_TOKEN, token, email, java.sql.Timestamp.from(expiresAt));
    }

    public Optional<Map<String, Object>> findActiveResetToken(String token) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(SqlTemplates.FIND_ACTIVE_RESET_TOKEN, token);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public int markResetTokenConsumed(String token) {
        return jdbcTemplate.update(SqlTemplates.MARK_RESET_TOKEN_CONSUMED, token);
    }

    public Optional<Map<String, Object>> findActiveResetTokenByEmail(String email) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(SqlTemplates.FIND_ACTIVE_RESET_TOKEN_BY_EMAIL, email);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public List<Map<String, Object>> findAuthUserByEmail(String email) {
        return jdbcTemplate.queryForList(SqlTemplates.FIND_AUTH_USER_BY_EMAIL, email);
    }

    public int updateSystemUserPassword(String userId, String passwordHash) {
        return jdbcTemplate.update(SqlTemplates.UPDATE_SYSTEM_USER_PASSWORD, passwordHash, userId);
    }

    // =====================================================================
    // Phase 1: Batch DB Upsert Upgrade — 批量写入方法
    // =====================================================================

    @SuppressWarnings("SqlNoDataSourceInspection")
    public int[] batchUpsertCrawlerSignals(List<Object[]> signals) {
        if (signals == null || signals.isEmpty()) {
            return new int[0];
        }
        try {
            jdbcTemplate.batchUpdate(SqlTemplates.BATCH_UPSERT_CRAWLER_SIGNALS, signals);
            return new int[signals.size()];
        } catch (org.springframework.dao.DataAccessException ex) {
            log.warn("Batch upsert crawler_signals failed: {} total", signals.size(), ex);
            return new int[0];
        }
    }

    /**
     * 批量 upsert knowledge_doc。
     */
    @SuppressWarnings("SqlNoDataSourceInspection")
    public int[] batchUpsertKnowledgeDocs(List<Object[]> docs) {
        if (docs == null || docs.isEmpty()) {
            return new int[0];
        }
        try {
            jdbcTemplate.batchUpdate(SqlTemplates.BATCH_UPSERT_KNOWLEDGE_DOCS, docs);
            return new int[docs.size()];
        } catch (org.springframework.dao.DataAccessException ex) {
            log.warn("Batch upsert knowledge_docs failed: {} total", docs.size(), ex);
            return new int[0];
        }
    }

    /**
     * 批量 upsert risk_alert（公开源告警）。
     */
    @SuppressWarnings("SqlNoDataSourceInspection")
    public int[] batchUpsertPublicAlerts(List<Object[]> alerts) {
        if (alerts == null || alerts.isEmpty()) {
            return new int[0];
        }
        try {
            jdbcTemplate.batchUpdate(SqlTemplates.BATCH_UPSERT_PUBLIC_ALERTS, alerts);
            return new int[alerts.size()];
        } catch (org.springframework.dao.DataAccessException ex) {
            log.warn("Batch upsert risk_alerts failed: {} total", alerts.size(), ex);
            return new int[0];
        }
    }

    /**
     * 批量 upsert enterprise_record。
     */
    @SuppressWarnings("SqlNoDataSourceInspection")
    public int[] batchUpsertEnterpriseRecords(List<Object[]> records) {
        if (records == null || records.isEmpty()) {
            return new int[0];
        }
        try {
            jdbcTemplate.batchUpdate(SqlTemplates.BATCH_UPSERT_ENTERPRISE_RECORDS, records);
            return new int[records.size()];
        } catch (org.springframework.dao.DataAccessException ex) {
            log.warn("Batch upsert enterprise_records failed: {} total", records.size(), ex);
            return new int[0];
        }
    }

    // =====================================================================
    // Knowledge Doc Soft Delete with MinIO Cleanup
    // =====================================================================

    /**
     * 软删除 knowledge_doc，并联动清理 MinIO 物理文件。
     *
     * <p>步骤：① 查询 object_key → ② 执行软删除 UPDATE → ③ 调用 MinIO removeObject（最佳努力）。
     * MinIO 清理失败不影响数据库软删除结果。</p>
     */
    public int softDeleteKnowledgeDoc(String id) {
        // Step 1: 获取 object_key
        String objectKey = null;
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "SELECT object_key FROM knowledge_doc WHERE id = ? AND is_deleted = 0 LIMIT 1", id);
            if (!rows.isEmpty()) {
                objectKey = String.valueOf(rows.get(0).get("object_key"));
                if (objectKey == null || "null".equals(objectKey) || objectKey.isBlank()) {
                    objectKey = null;
                }
            }
        } catch (Exception ex) {
            log.warn("Failed to lookup object_key for knowledge_doc {}: {}", id, ex.getMessage());
        }

        // Step 2: 软删除
        int affected = jdbcTemplate.update(SqlTemplates.SOFT_DELETE_KNOWLEDGE_DOC, id);

        // Step 3: 清理 MinIO 文件（最佳努力，不阻塞）
        if (objectKey != null && minioStorageService != null) {
            try {
                minioStorageService.removeObject(objectKey);
            } catch (Exception ex) {
                log.warn("MinIO file cleanup failed for doc {}: {}", id, ex.getMessage());
            }
        }

        return affected;
    }

    /** 更新 knowledge_doc 状态。 */
    public int updateKnowledgeDocStatus(String id, String status) {
        return jdbcTemplate.update(SqlTemplates.UPDATE_KNOWLEDGE_DOC_STATUS, status, id);
    }

    // =====================================================================
    // Crawler Signal Archive
    // =====================================================================

    /** 创建归档表（DDL，自动提交）。 */
    public int createArchiveTable() {
        return jdbcTemplate.update(SqlTemplates.CREATE_ARCHIVE_TABLE);
    }

    /** 将指定时间点之前的爬虫信号迁移到归档表。 */
    public int archiveCrawlerSignals(Instant before) {
        return jdbcTemplate.update(SqlTemplates.ARCHIVE_CRAWLER_SIGNALS, before);
    }

    /** 从主表中删除已归档的爬虫信号。 */
    public int deleteArchivedCrawlerSignals(Instant before) {
        return jdbcTemplate.update(SqlTemplates.DELETE_ARCHIVED_CRAWLER_SIGNALS, before);
    }
}
