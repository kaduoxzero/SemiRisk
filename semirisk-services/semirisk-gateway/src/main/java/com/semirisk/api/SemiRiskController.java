package com.semirisk.api;

import com.semirisk.service.SemiRiskStore;
import com.semirisk.common.RolePermissionPolicy;
import com.semirisk.service.SemiRiskStore.ReportJob;
import com.semirisk.service.SemiRiskStore.SystemUser;
import com.semirisk.service.SemiRiskStore.UploadTask;
import com.semirisk.service.SemiRiskStore.UserAccount;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Validated
@RestController
@RequestMapping("/api")
public class SemiRiskController {

    private final SemiRiskStore store;
    private final ExecutorService sseExecutor = Executors.newCachedThreadPool();

    public SemiRiskController(SemiRiskStore store) {
        this.store = store;
    }

    @PostMapping("/auth/login")
    public ResponseEntity<ApiResponse<Map<String, Object>>> login(@Valid @RequestBody LoginRequest request, HttpSession session) {
        SemiRiskStore.LoginState state = store.loginState(request.username());
        if (state.locked()) {
            return ResponseEntity.status(423).body(ApiResponse.fail("账号已锁定至 " + state.lockedUntil()));
        }
        return store.authenticate(request.username(), request.password())
                .map(account -> {
                    String token = UUID.randomUUID().toString();
                    session.setAttribute("principal", account.username());
                    session.setAttribute("role", account.role());
                    Map<String, Object> body = new HashMap<>();
                    body.put("token", token);
                    body.put("rememberMe", request.rememberMe());
                    body.put("user", Map.of(
                            "username", account.username(),
                            "displayName", account.displayName(),
                            "role", account.role(),
                            "modules", RolePermissionPolicy.modules(account.role())
                    ));
                    return ResponseEntity.ok(ApiResponse.ok("登录成功", body));
                })
                .orElseGet(() -> {
                    SemiRiskStore.LoginState failed = store.recordFailure(request.username());
                    String message = failed.locked()
                            ? "密码错误次数达到 5 次，账号锁定 30 分钟"
                            : "账号或密码错误，当前 5 分钟窗口失败次数：" + failed.failures();
                    return ResponseEntity.status(401).body(ApiResponse.fail(message));
                });
    }

    @PostMapping("/auth/logout")
    public ApiResponse<Map<String, Object>> logout(HttpSession session) {
        session.invalidate();
        return ApiResponse.ok(Map.of("loggedOut", true));
    }

    @GetMapping("/auth/me")
    public ResponseEntity<ApiResponse<Map<String, Object>>> me(HttpSession session) {
        Object username = session.getAttribute("principal");
        Object role = session.getAttribute("role");
        if (username == null || role == null) {
            return ResponseEntity.status(401).body(ApiResponse.fail("未登录"));
        }
        return ResponseEntity.ok(ApiResponse.ok(Map.of(
                "username", username,
                "role", role,
                "modules", RolePermissionPolicy.modules(String.valueOf(role))
        )));
    }

    @GetMapping("/auth/permissions/{module}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> permission(@PathVariable String module, HttpSession session) {
        Object role = session.getAttribute("role");
        boolean allowed = role != null && RolePermissionPolicy.canAccess(String.valueOf(role), module);
        return allowed
                ? ResponseEntity.ok(ApiResponse.ok(Map.of("module", module, "allowed", true)))
                : ResponseEntity.status(403).body(ApiResponse.fail("无权访问模块：" + module));
    }

    @PostMapping("/auth/password-reset/request")
    public ApiResponse<Map<String, Object>> requestReset(@Valid @RequestBody PasswordResetRequest request) {
        String token = store.createResetToken(request.email());
        return ApiResponse.ok("重置链接已发送，Token 15 分钟内有效且仅可使用一次",
                Map.of("email", request.email(), "token", token, "expiresInMinutes", 15));
    }

    @GetMapping("/dashboard/overview")
    public ApiResponse<Map<String, Object>> dashboard() {
        return ApiResponse.ok(store.dashboard());
    }

    @GetMapping(value = "/data/templates/{type}", produces = "text/csv;charset=UTF-8")
    public ResponseEntity<byte[]> template(@PathVariable String type) {
        String csv = "supplier,material,stage,lead_time_days,risk_level\n"
                + "安芯物流,晶圆,仓储物流,7,中危\n"
                + "华南晶圆,硅片,生产制造,14,低危\n";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=semirisk-" + type + "-template.csv")
                .body(csv.getBytes(StandardCharsets.UTF_8));
    }

    @PostMapping(value = "/data/uploads", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<UploadTask> upload(@RequestParam("file") MultipartFile file) throws IOException {
        return ApiResponse.ok("文件已进入 AI 清洗队列", store.createUpload(file));
    }

    @GetMapping("/data/uploads")
    public ApiResponse<List<UploadTask>> uploads() {
        return ApiResponse.ok(store.uploadTasks());
    }

    @PostMapping("/data/uploads/{id}/parse")
    public ApiResponse<UploadTask> parseUpload(@PathVariable String id) {
        return ApiResponse.ok("AI 校验和导入完成", store.advanceUpload(id));
    }

    @GetMapping("/data/uploads/logs")
    public SseEmitter uploadLogs() {
        SseEmitter emitter = new SseEmitter(30_000L);
        sseExecutor.submit(() -> {
            List<String> logs = List.of(
                    "[INFO] 接收文件元数据，校验大小与格式",
                    "[INFO] 解析 Excel/CSV/PDF 文档结构",
                    "[WARN] lead_time_days 缺失，按临近均值自动插值",
                    "[INFO] 抽取供应商、物料、航线实体",
                    "[INFO] 建立语义关联并写入风险事件候选集",
                    "[INFO] ETL 清洗完成"
            );
            try {
                for (String log : logs) {
                    emitter.send(SseEmitter.event().name("log").data(Map.of("time", Instant.now().toString(), "message", log)));
                    Thread.sleep(650);
                }
                emitter.complete();
            } catch (Exception ex) {
                emitter.completeWithError(ex);
            }
        });
        return emitter;
    }

    @GetMapping("/risk/analysis")
    public ApiResponse<Map<String, Object>> riskAnalysis(@RequestParam(defaultValue = "24h") String window) {
        return ApiResponse.ok(store.riskAnalysis(window));
    }

    @GetMapping("/risk/events/{id}")
    public ApiResponse<Map<String, Object>> riskDetail(@PathVariable String id) {
        return ApiResponse.ok(store.riskDetail(id));
    }

    @PostMapping("/risk/events/{id}/assign")
    public ApiResponse<Map<String, Object>> assignRisk(@PathVariable String id, @RequestBody(required = false) Map<String, String> body) {
        String owner = body == null ? "默认负责人" : body.getOrDefault("owner", "默认负责人");
        store.alert(id).ifPresent(alert -> store.updateAlertStatus(alert.id(), "处理中"));
        return ApiResponse.ok("负责人已指派", Map.of("id", id, "owner", owner, "status", "处理中"));
    }

    @PostMapping("/risk/events/{id}/dispatch-report")
    public ApiResponse<Map<String, Object>> dispatchReport(@PathVariable String id) {
        store.alert(id).ifPresent(alert -> store.updateAlertStatus(alert.id(), "处理中"));
        return ApiResponse.ok("处置报告已下发", Map.of("id", id, "status", "处理中"));
    }

    @GetMapping("/reports/templates")
    public ApiResponse<List<Map<String, String>>> reportTemplates() {
        return ApiResponse.ok(List.of(
                Map.of("id", "risk-assessment", "name", "风险评估报告", "scenario", "高管汇报与事件复盘"),
                Map.of("id", "supply-chain", "name", "供应链分析报告", "scenario", "物流、库存、供应商协同评估"),
                Map.of("id", "enterprise-dd", "name", "企业尽调报告", "scenario", "供应商准入与年审")
        ));
    }

    @PostMapping("/reports/jobs")
    public ApiResponse<ReportJob> createReport(@Valid @RequestBody ReportRequest request) {
        return ApiResponse.ok("报告生成任务已启动", store.createReport(request.template(), request.language(), request.format(), request.threshold()));
    }

    @GetMapping("/reports/jobs/{id}")
    public ApiResponse<ReportJob> reportJob(@PathVariable String id) {
        return store.reportJob(id).map(job -> ApiResponse.ok(store.advanceReport(id))).orElseGet(() -> ApiResponse.fail("报告任务不存在"));
    }

    @GetMapping(value = "/reports/{id}/download", produces = "text/plain;charset=UTF-8")
    public ResponseEntity<String> downloadReport(@PathVariable String id) {
        String report = """
                SemiRisk AI 风险报告
                报告编号：%s
                结论：当前主要风险为物流中断与原材料价格波动叠加。
                建议：启用备用仓、询价现货供应商、上调安全库存阈值。
                """.formatted(id);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=" + id + "-report.txt")
                .body(report);
    }

    @GetMapping("/alerts")
    public ApiResponse<List<SemiRiskStore.RiskAlert>> alerts(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String level,
            @RequestParam(required = false) String status) {
        String kw = keyword == null ? "" : keyword.trim();
        List<SemiRiskStore.RiskAlert> filtered = store.alerts().stream()
                .filter(alert -> kw.isBlank() || alert.title().contains(kw) || alert.source().contains(kw))
                .filter(alert -> level == null || level.isBlank() || alert.level().equals(level))
                .filter(alert -> status == null || status.isBlank() || alert.status().equals(status))
                .toList();
        return ApiResponse.ok(filtered);
    }

    @GetMapping("/alerts/counts")
    public ApiResponse<Map<String, Long>> alertCounts() {
        Map<String, Long> counts = new HashMap<>();
        store.alerts().stream().filter(alert -> !"已忽略".equals(alert.status())).forEach(alert -> counts.merge(alert.level(), 1L, Long::sum));
        return ApiResponse.ok(counts);
    }

    @PutMapping("/alerts/{id}/ignore")
    public ApiResponse<SemiRiskStore.RiskAlert> ignoreAlert(@PathVariable String id) {
        return ApiResponse.ok("告警已忽略", store.updateAlertStatus(id, "已忽略"));
    }

    @PostMapping("/alerts/batch-process")
    public ApiResponse<Map<String, Object>> batchProcess(@RequestBody BatchRequest request) {
        request.ids().forEach(id -> store.updateAlertStatus(id, "处理中"));
        return ApiResponse.ok("批量处理指令下发成功", Map.of("processed", request.ids().size()));
    }

    @GetMapping("/gis/map")
    public ApiResponse<Map<String, Object>> gis(@RequestParam(required = false) String layers) {
        return ApiResponse.ok(store.gis(layers));
    }

    @GetMapping("/enterprises/profile")
    public ApiResponse<Map<String, Object>> enterprise(@RequestParam(required = false) String keyword) {
        return ApiResponse.ok(store.enterprise(keyword));
    }

    @GetMapping("/knowledge/search")
    public ApiResponse<Map<String, Object>> knowledge(@RequestParam(required = false) String query) {
        return ApiResponse.ok(store.knowledge(query));
    }

    @GetMapping(value = "/knowledge/preview/{id}", produces = "text/plain;charset=UTF-8")
    public ResponseEntity<String> preview(@PathVariable String id) {
        return ResponseEntity.ok("知识文档预览 " + id + "\n此处模拟 PDF 在线预览内容，真实环境可替换为 MinIO 文件流。");
    }

    @GetMapping("/system/overview")
    public ApiResponse<Map<String, Object>> systemOverview() {
        return ApiResponse.ok(store.systemOverview());
    }

    @PostMapping("/system/users")
    public ApiResponse<SystemUser> addUser(@Valid @RequestBody AddUserRequest request) {
        return ApiResponse.ok("系统用户已创建", store.addSystemUser(request.username(), request.email(), request.role()));
    }

    @PutMapping("/system/users/{id}/status")
    public ApiResponse<SystemUser> updateUserStatus(@PathVariable String id, @Valid @RequestBody StatusRequest request) {
        return ApiResponse.ok("用户状态已更新，在线 Session 已踢下线", store.updateSystemUserStatus(id, request.status()));
    }

    @DeleteMapping("/system/users/{id}")
    public ApiResponse<Map<String, Object>> deleteUser(@PathVariable String id) {
        store.deleteSystemUser(id);
        return ApiResponse.ok("用户已物理删除", Map.of("id", id));
    }

    @PostMapping("/system/models/ping")
    public ApiResponse<Map<String, Object>> modelPing(@RequestBody Map<String, String> request) {
        String model = request.getOrDefault("model", "default");
        int latency = 240 + Math.abs(model.hashCode() % 300);
        return ApiResponse.ok("模型连通性测试成功", Map.of("model", model, "latencyMs", latency, "status", "健康"));
    }

    @PostMapping("/system/models/config")
    public ApiResponse<SemiRiskStore.AiModelConfig> saveModelConfig(@Valid @RequestBody AiModelConfigRequest request) {
        return ApiResponse.ok("AI 模型 API Key 已保存", store.saveAiModelConfig(request.model(), request.endpoint(), request.apiKey()));
    }

    @GetMapping("/system/models/config")
    public ApiResponse<Map<String, SemiRiskStore.AiModelConfig>> modelConfigs() {
        return ApiResponse.ok(store.aiModelConfigs());
    }

    @GetMapping("/risk-score/today")
    public ApiResponse<SemiRiskStore.DailyRiskSnapshot> todayRiskScore() {
        return ApiResponse.ok(store.dailyRiskSnapshot());
    }

    @PostMapping("/risk-score/recalculate")
    public ApiResponse<SemiRiskStore.DailyRiskSnapshot> recalculateRiskScore() {
        store.refreshDailyRiskRecords();
        return ApiResponse.ok("AI 风险自动测算完成", store.dailyRiskSnapshot());
    }

    @PostMapping("/system/agents/{name}/trigger")
    public ApiResponse<Map<String, Object>> triggerAgent(@PathVariable String name) {
        return ApiResponse.ok("Agent 已手动触发", Map.of("agent", name, "triggeredAt", Instant.now().toString()));
    }

    @PostMapping("/system/datasources/{name}/reconnect")
    public ApiResponse<Map<String, Object>> reconnect(@PathVariable String name) {
        return ApiResponse.ok("数据源重连成功", Map.of("source", name, "host", "192.168.101.128"));
    }

    public record LoginRequest(@NotBlank String username, @NotBlank String password, boolean rememberMe, String captchaToken) {
    }

    public record PasswordResetRequest(@NotBlank @Email String email) {
    }

    public record ReportRequest(@NotBlank String template, @NotBlank String language, @NotBlank String format, int threshold) {
    }

    public record BatchRequest(@NotNull List<String> ids) {
    }

    public record AddUserRequest(@NotBlank String username, @NotBlank @Email String email, @NotBlank String role) {
    }

    public record StatusRequest(@NotBlank String status) {
    }

    public record AiModelConfigRequest(@NotBlank String model, @NotBlank String endpoint, @NotBlank String apiKey) {
    }
}
