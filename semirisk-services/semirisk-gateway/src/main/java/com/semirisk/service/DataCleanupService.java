package com.semirisk.service;

import com.semirisk.repository.PreparedRiskRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * 数据清理与归档服务。
 *
 * <p>负责定期归档高频写入的爬虫信号数据，防止主表数据量过大导致查询变慢。</p>
 */
@Service
public class DataCleanupService {

    private static final Logger log = LoggerFactory.getLogger(DataCleanupService.class);
    private static final int RETENTION_DAYS = 90;

    private final PreparedRiskRepository repository;
    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;
    private volatile boolean running = false;

    public DataCleanupService(PreparedRiskRepository repository,
                              JdbcTemplate jdbcTemplate,
                              TransactionTemplate transactionTemplate) {
        this.repository = repository;
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = transactionTemplate;
    }

    /**
     * 每周日凌晨 2:00 执行爬虫信号归档。
     *
     * <p>流程：
     * <ol>
     *   <li>创建归档表（DDL，自动提交）</li>
     *   <li>在事务内迁移旧数据到归档表</li>
     *   <li>在事务内从主表删除已迁移的数据</li>
     * </ol>
     * </p>
     */
    @Scheduled(cron = "0 0 2 ? * SUN")
    public void archiveOldCrawlerSignals() {
        if (running) {
            log.info("DataCleanupService: archival already running, skipping");
            return;
        }
        running = true;
        try {
            log.info("DataCleanupService: starting crawler_signal archival");

            // Step 1: 创建归档表（DDL，隐式提交，必须在事务外）
            repository.createArchiveTable();

            // Step 2: 计算 cutoff
            Instant cutoff = Instant.now().minus(RETENTION_DAYS, ChronoUnit.DAYS);

            // Step 3: 在事务内执行数据迁移和清理
            Integer[] result = transactionTemplate.execute(status -> {
                try {
                    // 迁移旧数据到归档表
                    int archived = repository.archiveCrawlerSignals(cutoff);
                    log.info("DataCleanupService: archived {} crawler_signal records older than {}", archived, cutoff);

                    // 从主表删除已归档的记录
                    int deleted = repository.deleteArchivedCrawlerSignals(cutoff);
                    log.info("DataCleanupService: deleted {} archived records from main table", deleted);

                    return new Integer[]{archived, deleted};
                } catch (Exception ex) {
                    status.setRollbackOnly();
                    log.error("DataCleanupService: archival transaction failed", ex);
                    throw ex;
                }
            });

            if (result != null) {
                log.info("DataCleanupService: archival completed archived={} deleted={}", result[0], result[1]);
            }
        } catch (Exception ex) {
            log.error("DataCleanupService: archival failed", ex);
        } finally {
            running = false;
        }
    }
}
