package com.semirisk.api;

import com.alibaba.csp.sentinel.slots.block.BlockException;
import com.semirisk.api.ApiResponse;
import org.springframework.http.ResponseEntity;

import java.util.Map;

/** 统一的 Sentinel 限流 / 降级处理器。 */
public final class SentinelBlockHandler {

    private SentinelBlockHandler() {}

    public static ResponseEntity<ApiResponse<Map<String, Object>>> loginBlocked(Object request, BlockException ex) {
        return ResponseEntity.status(429).body(ApiResponse.fail("登录请求过于频繁，请稍后再试（限流保护）"));
    }

    public static ApiResponse<Map<String, Object>> dashboardBlocked(BlockException ex) {
        return ApiResponse.fail("Dashboard 接口达到限流阈值，请稍候");
    }

    public static ApiResponse<Map<String, Object>> dashboardFallback(Throwable ex) {
        return ApiResponse.ok("Dashboard 降级（依赖异常）", Map.of(
                "kpis", java.util.List.of(),
                "dailyRisk", Map.of("score", 0, "level", "降级", "summary", "下游服务异常，已降级"),
                "fallback", true,
                "reason", ex.getClass().getSimpleName()
        ));
    }

    public static ApiResponse<Map<String, Object>> reportBlocked(BlockException ex) {
        return ApiResponse.fail("报告生成接口被限流，请避开高峰时段");
    }

    public static ApiResponse<Map<String, Object>> knowledgeBlocked(BlockException ex) {
        return ApiResponse.fail("AI 知识库问答接口已达限流阈值（保护下游 LLM）");
    }

    public static ApiResponse<Map<String, Object>> knowledgeFallback(Throwable ex) {
        return ApiResponse.ok("知识库降级", Map.of(
                "answer", "AI 服务暂时不可用，已切换到本地检索摘要",
                "aiCalled", false,
                "modelStatus", "降级：" + ex.getClass().getSimpleName(),
                "citations", java.util.List.of()
        ));
    }
}
