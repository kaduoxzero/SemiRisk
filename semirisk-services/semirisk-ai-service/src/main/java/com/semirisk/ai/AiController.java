package com.semirisk.ai;

import com.semirisk.api.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/ai")
public class AiController {

    private final AiConfigService aiConfigService;
    private final AiReportService aiReportService;

    public AiController(AiConfigService aiConfigService, AiReportService aiReportService) {
        this.aiConfigService = aiConfigService;
        this.aiReportService = aiReportService;
    }

    @PostMapping("/models/config")
    public ApiResponse<AiConfigService.AiModelConfig> saveConfig(@Valid @RequestBody AiModelRequest request) {
        return ApiResponse.ok("AI 模型配置已保存", aiConfigService.save(request.model(), request.endpoint(), request.apiKey()));
    }

    @GetMapping("/models/config")
    public ApiResponse<Map<String, AiConfigService.AiModelConfig>> configs() {
        return ApiResponse.ok(aiConfigService.all());
    }

    @GetMapping("/reports/latest")
    public ApiResponse<Map<String, Object>> latestReport() {
        return ApiResponse.ok(aiReportService.latestReport());
    }

    @PostMapping("/reports/refresh")
    public ApiResponse<Map<String, Object>> refreshReport() {
        aiReportService.generateReport();
        return ApiResponse.ok("AI report refreshed", aiReportService.latestReport());
    }

    public record AiModelRequest(@NotBlank String model, @NotBlank String endpoint, @NotBlank String apiKey) {
    }
}

