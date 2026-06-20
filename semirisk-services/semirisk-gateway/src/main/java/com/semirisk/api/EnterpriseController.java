package com.semirisk.api;

import com.semirisk.service.SemiRiskStore;
import com.semirisk.service.MinioStorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * 企业画像 / 知识库问答 API。
 */
@RestController
@RequestMapping("/api")
public class EnterpriseController {

    private static final Logger log = LoggerFactory.getLogger(EnterpriseController.class);

    private final SemiRiskStore store;
    private final MinioStorageService minioStorageService;

    public EnterpriseController(SemiRiskStore store, MinioStorageService minioStorageService) {
        this.store = store;
        this.minioStorageService = minioStorageService;
    }

    @GetMapping("/enterprises")
    public ApiResponse<List<Map<String, Object>>> enterprises() {
        return ApiResponse.ok(store.enterpriseCatalog());
    }

    @GetMapping("/enterprises/profile")
    public ApiResponse<Map<String, Object>> enterprise(@RequestParam(required = false) String keyword) {
        return ApiResponse.ok(store.enterprise(keyword));
    }

    @PostMapping("/knowledge/ask")
    public ApiResponse<Map<String, Object>> askKnowledge(@Valid @RequestBody KnowledgeAskRequest request) {
        return ApiResponse.ok("AI 知识库智能体回答完成", store.askKnowledgeAgent(request.question()));
    }

    @GetMapping("/knowledge/preview/{id}")
    public ResponseEntity<byte[]> preview(@PathVariable String id) {
        try {
            List<Map<String, Object>> docs = store.getPreparedRiskRepository().findKnowledgeDocById(id);
            if (!docs.isEmpty()) {
                Map<String, Object> doc = docs.get(0);
                String objectKey = String.valueOf(doc.getOrDefault("objectKey", ""));
                if (objectKey != null && !objectKey.isBlank() && !"null".equals(objectKey)) {
                    byte[] body = minioStorageService.getObject(objectKey);
                    return ResponseEntity.ok()
                            .contentType(org.springframework.http.MediaType.parseMediaType(minioStorageService.contentType(objectKey)))
                            .body(body);
                }
                String text = "标题：" + doc.get("title") + "\n分类：" + doc.get("category") + "\n来源：" + doc.get("source")
                        + "\n维度：" + doc.get("dimension") + "\n原文链接：" + doc.get("sourceUrl") + "\n\n" + doc.get("content");
                return ResponseEntity.ok()
                        .contentType(org.springframework.http.MediaType.parseMediaType("text/plain;charset=UTF-8"))
                        .body(text.getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception ex) {
            log.debug("Knowledge doc preview failed for id {}: {}", id, ex.getMessage());
        }
        String fallback = "知识文档 " + id + " 暂无可预览的对象。";
        return ResponseEntity.ok()
                .contentType(org.springframework.http.MediaType.parseMediaType("text/plain;charset=UTF-8"))
                .body(fallback.getBytes(StandardCharsets.UTF_8));
    }

    public record KnowledgeAskRequest(@NotBlank String question) {}
}
