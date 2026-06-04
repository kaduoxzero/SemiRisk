package com.semirisk.report;

import com.semirisk.api.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

@RestController
@RequestMapping("/api/reports")
public class ReportController {

    private final Map<String, ReportJob> jobs = new ConcurrentHashMap<>();

    @PostMapping("/jobs")
    public ApiResponse<ReportJob> create(@Valid @RequestBody ReportRequest request) {
        String id = "RP-" + System.currentTimeMillis();
        ReportJob job = new ReportJob(id, request.template(), request.language(), request.format(), request.threshold(), "排队中", 0, "任务已进入 AI 编译队列", null, Instant.now());
        jobs.put(id, job);
        return ApiResponse.ok("报告生成任务已启动", job);
    }

    @GetMapping("/jobs/{id}")
    public ApiResponse<ReportJob> get(@PathVariable String id) {
        ReportJob job = jobs.get(id);
        if (job == null) return ApiResponse.fail("报告任务不存在");
        int progress = Math.min(100, job.progress() + ThreadLocalRandom.current().nextInt(18, 34));
        ReportJob updated = new ReportJob(job.id(), job.template(), job.language(), job.format(), job.threshold(), progress >= 100 ? "已完成" : "生成中", progress, progress >= 100 ? "报告文件已生成" : "调用 AI 生成风险摘要", progress >= 100 ? "/api/reports/" + id + "/download" : null, job.createdAt());
        jobs.put(id, updated);
        return ApiResponse.ok(updated);
    }

    public record ReportRequest(@NotBlank String template, @NotBlank String language, @NotBlank String format, int threshold) {
    }

    public record ReportJob(String id, String template, String language, String format, int threshold, String status, int progress, String step, String downloadUrl, Instant createdAt) {
    }
}
