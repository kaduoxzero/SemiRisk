package com.semirisk.service;

import com.semirisk.model.UploadTask;
import com.semirisk.repository.PreparedRiskRepository;
import com.semirisk.util.SafeLogger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 处理文件上传任务的生命周期：创建、完成、查询和恢复。
 */
@Service
public class UploadService {

    private static final Logger log = LoggerFactory.getLogger(UploadService.class);

    private final Map<String, UploadTask> uploadTasks = new ConcurrentHashMap<>();
    private final PreparedRiskRepository repository;

    public UploadService(PreparedRiskRepository repository) {
        this.repository = repository;
    }

    // -----------------------------------------------------------------
    // 包级私有映射 getter（重构后供 SemiRiskStore 访问上传状态使用）。
    // -----------------------------------------------------------------

    Map<String, UploadTask> getUploadTasks() {
        return uploadTasks;
    }

    // -----------------------------------------------------------------
    // 上传任务操作 —— 精确实现直接从 SemiRiskStore 复制。
    // -----------------------------------------------------------------
    // SemiRiskStore.
    // -----------------------------------------------------------------

    public UploadTask createUpload(MultipartFile file) throws IOException {
        if (file.getSize() > 50L * 1024L * 1024L) {
            throw new IllegalArgumentException("单个文件不能超过 50MB");
        }
        String id = "UP-" + System.currentTimeMillis();
        String status = "上传中";
        UploadTask task = new UploadTask(id, file.getOriginalFilename(), file.getSize(), status, Instant.now(), 0, List.of());
        uploadTasks.put(id, task);
        return task;
    }

    public UploadTask completeUpload(String id, int rows, List<String> warnings) {
        UploadTask task = uploadTasks.get(id);
        if (task == null) {
            throw new IllegalArgumentException("上传任务不存在");
        }
        String status = rows > 0 ? "评估中" : "AI评估失败";
        UploadTask done = new UploadTask(task.id(), task.filename(), task.size(), status, task.createdAt(), rows,
                warnings == null ? List.of() : warnings);
        uploadTasks.put(id, done);
        return done;
    }

    public Optional<UploadTask> uploadTask(String id) {
        return Optional.ofNullable(uploadTasks.get(id));
    }

    public List<UploadTask> uploadTasks() {
        return uploadTasks.values().stream().sorted(Comparator.comparing(UploadTask::createdAt).reversed()).toList();
    }

    /** 从 MySQL 恢复上传任务到内存。 */
    public void recoverUploadTasks() {
        try {
            List<Map<String, Object>> rows = repository.findUploadTasks(200);
            for (Map<String, Object> row : rows) {
                String id = stringValue(row.get("id"));
                String filename = stringValue(row.get("filename"));
                long size = asInt(row.get("size"));
                String status = stringValue(row.get("status"));
                int rowsCount = asInt(row.get("rows"));
                Instant createdAt = toInstant(row.get("createdAt"));
                // 只恢复非完成状态的任务
                if (!"已入库".equals(status) && !"AI评估失败".equals(status)) {
                    UploadTask task = new UploadTask(id, filename, size, status, createdAt, rowsCount, List.of());
                    uploadTasks.put(id, task);
                }
            }
        } catch (Exception ex) {
            SafeLogger.error(log, "Failed to recover upload tasks from MySQL", ex);
        }
    }

    // -----------------------------------------------------------------
    // 辅助方法（从 SemiRiskStore 提取，recoverUploadTasks 需要）
    // -----------------------------------------------------------------
    // -----------------------------------------------------------------

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private int asInt(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (Exception ex) {
            SafeLogger.debug(log, "Failed to parse int from '" + value + "'", ex);
            return 0;
        }
    }

    private Instant toInstant(Object value) {
        if (value == null) {
            return Instant.now();
        }
        if (value instanceof Instant instant) {
            return instant;
        }
        if (value instanceof java.sql.Timestamp timestamp) {
            return timestamp.toInstant();
        }
        if (value instanceof java.time.LocalDateTime localDateTime) {
            return localDateTime.atZone(java.time.ZoneId.systemDefault()).toInstant();
        }
        try {
            return Instant.parse(String.valueOf(value));
        } catch (Exception ex) {
            SafeLogger.debug(log, "Failed to parse Instant from '" + value + "'", ex);
            return Instant.now();
        }
    }
}
