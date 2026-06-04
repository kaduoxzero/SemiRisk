package com.semirisk.alert;

import com.semirisk.api.ApiResponse;
import com.semirisk.common.SqlTemplates;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/alerts")
public class AlertController {

    private final JdbcTemplate jdbcTemplate;

    public AlertController(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @GetMapping
    public ApiResponse<List<Map<String, Object>>> alerts(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String level,
            @RequestParam(required = false) String status) {
        String kw = keyword == null || keyword.isBlank() ? null : keyword;
        return ApiResponse.ok(jdbcTemplate.queryForList(SqlTemplates.FIND_ALERTS, kw, kw, kw, blank(level), blank(level), blank(status), blank(status), 100));
    }

    @PutMapping("/{id}/ignore")
    public ApiResponse<Map<String, Object>> ignore(@PathVariable String id) {
        jdbcTemplate.update(SqlTemplates.UPDATE_ALERT_STATUS, "已忽略", id);
        return ApiResponse.ok("告警已忽略", Map.of("id", id, "status", "已忽略"));
    }

    private String blank(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
