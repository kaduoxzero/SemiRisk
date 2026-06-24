package com.semirisk.api;

import com.semirisk.repository.PreparedRiskRepository;
import com.semirisk.security.InputSanitizer;
import com.semirisk.service.MinioStorageService;
import com.semirisk.service.SemiRiskStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@RestController
@RequestMapping("/api")
public class DataController {

    private static final Logger log = LoggerFactory.getLogger(DataController.class);
    private static final long MAX_UPLOAD_BYTES = 10L * 1024L * 1024L;
    private static final Set<String> ALLOWED_EXTENSIONS = Set.of("csv", "tsv", "xlsx", "xls");

    private final SemiRiskStore store;
    private final MinioStorageService minioStorageService;
    private final PreparedRiskRepository repository;
    private final InputSanitizer inputSanitizer;

    public DataController(SemiRiskStore store, MinioStorageService minioStorageService,
                          PreparedRiskRepository repository, InputSanitizer inputSanitizer) {
        this.store = store;
        this.minioStorageService = minioStorageService;
        this.repository = repository;
        this.inputSanitizer = inputSanitizer;
    }

    @GetMapping(value = "/data/templates/{type}", produces = "text/csv;charset=UTF-8")
    public ResponseEntity<byte[]> template(@PathVariable String type) {
        String cleanType = inputSanitizer.plain(type, 48).replaceAll("[^A-Za-z0-9._-]+", "_");
        String csv = "supplier,material,stage,lead_time_days,risk_level\n"
                + "Anhua Logistics,Wafer,Warehouse Logistics,7,Medium\n"
                + "South China Wafer,Silicon,Production,14,Low\n";
        byte[] csvBytes = csv.getBytes(StandardCharsets.UTF_8);
        byte[] bom = new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
        byte[] response = new byte[bom.length + csvBytes.length];
        System.arraycopy(bom, 0, response, 0, bom.length);
        System.arraycopy(csvBytes, 0, response, bom.length, csvBytes.length);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, "text/csv;charset=UTF-8")
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"semirisk-" + cleanType + "-template.csv\"")
                .header("Content-Transfer-Encoding", "binary")
                .body(response);
    }

    @PostMapping(value = "/data/uploads", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<?> upload(@RequestParam("file") MultipartFile file) throws IOException {
        validateUpload(file);
        var task = store.createUpload(file);
        String objectKey = uploadObjectKey(task.id(), task.filename());
        boolean stored = false;
        try {
            minioStorageService.putObject(objectKey, file.getBytes(), safeContentType(file.getContentType()));
            stored = true;
        } catch (Exception ex) {
            log.debug("MinIO unavailable for upload {}: {}", task.filename(), ex.getMessage());
        }
        return ApiResponse.ok(stored ? "文件已上传，AI 自动分析中" : "文件已接收，AI 分析稍后进行（对象存储暂不可达）", task);
    }

    @GetMapping("/data/uploads")
    public ApiResponse<List<?>> uploads() {
        return ApiResponse.ok(repository.findUploadTasks(100));
    }

    private void validateUpload(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("上传文件不能为空");
        }
        if (file.getSize() > MAX_UPLOAD_BYTES) {
            throw new IllegalArgumentException("单个文件不能超过 10MB");
        }
        String filename = file.getOriginalFilename() == null ? "" : file.getOriginalFilename();
        String extension = extensionOf(filename);
        if (!ALLOWED_EXTENSIONS.contains(extension)) {
            throw new IllegalArgumentException("仅支持 CSV、TSV、XLSX、XLS 文件");
        }
    }

    private String extensionOf(String filename) {
        int dot = filename.lastIndexOf('.');
        if (dot < 0 || dot == filename.length() - 1) {
            return "";
        }
        return filename.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private String uploadObjectKey(String id, String filename) {
        String safe = filename == null ? "file" : filename.replaceAll("[^A-Za-z0-9._\\-]+", "_");
        return "uploads/" + id + "/" + safe;
    }

    private String safeContentType(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            return MediaType.APPLICATION_OCTET_STREAM_VALUE;
        }
        String lower = contentType.toLowerCase(Locale.ROOT);
        if (lower.contains("html") || lower.contains("xml") || lower.contains("javascript")) {
            return MediaType.APPLICATION_OCTET_STREAM_VALUE;
        }
        return contentType;
    }
}
