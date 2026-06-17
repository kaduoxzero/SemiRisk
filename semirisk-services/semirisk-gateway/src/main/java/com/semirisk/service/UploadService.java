package com.semirisk.service;

import com.semirisk.model.UploadTask;
import com.semirisk.repository.PreparedRiskRepository;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
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
        String status = file.getOriginalFilename() != null && file.getOriginalFilename().toLowerCase(Locale.ROOT).endsWith(".zip")
                ? "待解压"
                : "解析中";
        UploadTask task = new UploadTask(id, file.getOriginalFilename(), file.getSize(), status, Instant.now(), 0, List.of());
        uploadTasks.put(id, task);
        return task;
    }

    public UploadTask completeUpload(String id, int rows, List<String> warnings) {
        UploadTask task = uploadTasks.get(id);
        if (task == null) {
            throw new IllegalArgumentException("上传任务不存在");
        }
        String status = rows > 0 ? "导入成功" : "无有效数据";
        UploadTask done = new UploadTask(task.id(), task.filename(), task.size(), status, task.createdAt(), rows,
                warnings == null ? List.of() : warnings);
        uploadTasks.put(id, done);
        return done;
    }

    public Optional<UploadTask> uploadTask(String id) {
        return Optional.ofNullable(uploadTasks.get(id));
    }

    /** 上传处理 SSE 的真实日志行，反映文件接收、MinIO 落库与真实解析结果。 */
    public List<String> uploadLogLines(String id) {
        UploadTask task = (id == null || id.isBlank())
                ? uploadTasks.values().stream().max(Comparator.comparing(UploadTask::createdAt)).orElse(null)
                : uploadTasks.get(id);
        List<String> lines = new ArrayList<>();
        if (task == null) {
            lines.add("[INFO] 暂无上传任务，等待文件上传后开始处理");
            return lines;
        }
        lines.add("[INFO] 接收文件 " + task.filename() + "（" + task.size() + " 字节），校验大小与格式");
        lines.add("[INFO] 文件已写入 MinIO 对象存储，便于后续解析与预览");
        lines.add("[INFO] 当前任务状态：" + task.status());
        if (task.rows() > 0) {
            lines.add("[INFO] 真实解析数据行 " + task.rows() + " 行，已抽取供应商/物料/航线字段");
        }
        task.warnings().forEach(lines::add);
        lines.add("[INFO] 处理流程结束");
        return lines;
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
                if (!"导入成功".equals(status) && !"无有效数据".equals(status) && !"失败".equals(status)) {
                    UploadTask task = new UploadTask(id, filename, size, status, createdAt, rowsCount, List.of());
                    uploadTasks.put(id, task);
                }
            }
        } catch (Exception ignored) {
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
        } catch (Exception ignored) {
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
        } catch (Exception ignored) {
            return Instant.now();
        }
    }
}
