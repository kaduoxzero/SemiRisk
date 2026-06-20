package com.semirisk.api;

import com.semirisk.service.SemiRiskStore;
import com.semirisk.service.MinioStorageService;
import com.semirisk.repository.PreparedRiskRepository;
import com.semirisk.security.InputSanitizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 数据上传 / 模板下载 API。
 */
@RestController
@RequestMapping("/api")
public class DataController {

    private static final Logger log = LoggerFactory.getLogger(DataController.class);

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
        String csv = "supplier,material,stage,lead_time_days,risk_level\n"
                + "安芯物流,晶圆,仓储物流,7,中危\n"
                + "华南晶圆,硅片,生产制造,14,低危\n";
        byte[] csvBytes = csv.getBytes(StandardCharsets.UTF_8);
        byte[] bom = new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
        byte[] response = new byte[bom.length + csvBytes.length];
        System.arraycopy(bom, 0, response, 0, bom.length);
        System.arraycopy(csvBytes, 0, response, bom.length, csvBytes.length);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, "text/csv;charset=UTF-8")
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"semirisk-" + type + "-template.csv\"")
                .header("Content-Transfer-Encoding", "binary")
                .body(response);
    }

    @PostMapping(value = "/data/uploads", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<?> upload(@RequestParam("file") MultipartFile file) throws IOException {
        try {
            var task = store.createUpload(file);
            String objectKey = uploadObjectKey(task.id(), task.filename());
            boolean stored = false;
            try {
                minioStorageService.putObject(objectKey, file.getBytes(), file.getContentType());
                stored = true;
            } catch (Exception ex) {
                log.debug("MinIO unavailable for upload {}: {}", task.filename(), ex.getMessage());
            }
            return ApiResponse.ok(stored ? "文件已上传，AI 自动分析中" : "文件已接收，AI 分析将稍后进行（对象存储暂不可达）", task);
        } catch (Exception ex) {
            return ApiResponse.fail("上传失败: " + ex.getMessage());
        }
    }

    @GetMapping("/data/uploads")
    public ApiResponse<List<?>> uploads() {
        return ApiResponse.ok(repository.findUploadTasks(100));
    }

    private String uploadObjectKey(String id, String filename) {
        String safe = filename == null ? "file" : filename.replaceAll("[^A-Za-z0-9._\\-]+", "_");
        return "uploads/" + id + "/" + safe;
    }
}
