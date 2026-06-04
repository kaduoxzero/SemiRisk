package com.semirisk.repository;

import com.semirisk.common.SqlTemplates;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

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
}
