package com.semirisk.api;

import com.alibaba.csp.sentinel.annotation.SentinelResource;
import com.semirisk.model.LoginState;
import com.semirisk.model.CrawlerSignal;
import com.semirisk.model.DailyRiskSnapshot;
import com.semirisk.model.AiModelConfig;
import com.semirisk.model.ReportJob;
import com.semirisk.model.RiskAlert;
import com.semirisk.model.SystemUser;
import com.semirisk.model.UploadTask;
import com.semirisk.model.UserAccount;
import com.semirisk.service.SemiRiskStore;
import com.semirisk.common.ReportFileFactory;
import com.semirisk.common.ReportFileFactory.ReportFile;
import com.semirisk.common.RolePermissionPolicy;
import com.semirisk.common.SemiriskConstants;
import com.semirisk.repository.PreparedRiskRepository;
import com.semirisk.security.CsrfTokenService;
import com.semirisk.security.InputSanitizer;
import com.semirisk.security.PasswordHashService;
import com.semirisk.security.RedisLoginGuardService;
import com.semirisk.security.TokenAuthService;
import com.semirisk.service.KnowledgeSearchIndexService;
import com.semirisk.service.MinioStorageService;
import com.semirisk.service.PublicCrawlerClient;
import com.semirisk.service.HealthProbeService;
import com.semirisk.service.UploadParseService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.dao.DuplicateKeyException;
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
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Validated
@RestController
@RequestMapping("/api")
public class SemiRiskController {

    private final SemiRiskStore store;
    private final PublicCrawlerClient publicCrawlerClient;
    private final KnowledgeSearchIndexService knowledgeSearchIndexService;
    private final PreparedRiskRepository preparedRiskRepository;
    private final RedisLoginGuardService redisLoginGuardService;
    private final PasswordHashService passwordHashService;
    private final InputSanitizer inputSanitizer;
    private final TokenAuthService tokenAuthService;
    private final CsrfTokenService csrfTokenService;
    private final MinioStorageService minioStorageService;
    private final UploadParseService uploadParseService;
    private final HealthProbeService healthProbeService;
    private final ExecutorService sseExecutor = Executors.newCachedThreadPool();
    private volatile Instant lastCrawlerSync = Instant.EPOCH;

    public SemiRiskController(SemiRiskStore store, PublicCrawlerClient publicCrawlerClient, KnowledgeSearchIndexService knowledgeSearchIndexService, PreparedRiskRepository preparedRiskRepository, RedisLoginGuardService redisLoginGuardService, PasswordHashService passwordHashService, InputSanitizer inputSanitizer, TokenAuthService tokenAuthService, CsrfTokenService csrfTokenService, MinioStorageService minioStorageService, UploadParseService uploadParseService, HealthProbeService healthProbeService) {
        this.store = store;
        this.publicCrawlerClient = publicCrawlerClient;
        this.knowledgeSearchIndexService = knowledgeSearchIndexService;
        this.preparedRiskRepository = preparedRiskRepository;
        this.redisLoginGuardService = redisLoginGuardService;
        this.passwordHashService = passwordHashService;
        this.inputSanitizer = inputSanitizer;
        this.tokenAuthService = tokenAuthService;
        this.csrfTokenService = csrfTokenService;
        this.minioStorageService = minioStorageService;
        this.uploadParseService = uploadParseService;
        this.healthProbeService = healthProbeService;
    }

    @GetMapping("/auth/csrf")
    public ApiResponse<Map<String, Object>> csrf() {
        return ApiResponse.ok(Map.of("token", csrfTokenService.issue()));
    }

    @PostMapping("/auth/login")
    @SentinelResource(value = "auth.login",
            blockHandlerClass = SentinelBlockHandler.class, blockHandler = "loginBlocked")
    public ResponseEntity<ApiResponse<Map<String, Object>>> login(@Valid @RequestBody LoginRequest request) {
        String username = inputSanitizer.username(request.username());
        String password = inputSanitizer.loginPassword(request.password());
        LoginState state = redisLoginGuardService.loginState(username).orElseGet(() -> store.loginState(username));
        if (state.locked()) {
            return ResponseEntity.status(423).body(ApiResponse.fail("账号已锁定至 " + state.lockedUntil()));
        }
        return authenticate(username, password)
                .map(account -> {
                    redisLoginGuardService.clear(username);
                    TokenAuthService.IssuedToken issuedToken = tokenAuthService.issue(account);
                    Map<String, Object> body = new HashMap<>();
                    body.put("token", issuedToken.token());
                    body.put("expiresAt", issuedToken.expiresAt().toString());
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
                    LoginState failed = redisLoginGuardService.recordFailure(username).orElseGet(() -> store.recordFailure(username));
                    String message = failed.locked()
                            ? "密码错误次数达到 5 次，账号锁定 30 分钟"
                            : "账号或密码错误，当前 5 分钟窗口失败次数：" + failed.failures();
                    return ResponseEntity.status(401).body(ApiResponse.fail(message));
                });
    }

    @PostMapping("/auth/register")
    public ResponseEntity<ApiResponse<Map<String, Object>>> register(@Valid @RequestBody RegisterRequest request) {
        String username = inputSanitizer.username(request.username());
        String email = inputSanitizer.qqEmail(request.email());
        String displayName = inputSanitizer.displayName(request.displayName());
        String password = inputSanitizer.password(request.password());
        UserAccount account = registerPersisted(username, password, displayName, email);
        TokenAuthService.IssuedToken issuedToken = tokenAuthService.issue(account);
        Map<String, Object> body = new HashMap<>();
        body.put("token", issuedToken.token());
        body.put("expiresAt", issuedToken.expiresAt().toString());
        body.put("user", Map.of(
                "username", account.username(),
                "displayName", account.displayName(),
                "role", account.role(),
                "modules", RolePermissionPolicy.modules(account.role())
        ));
        return ResponseEntity.ok(ApiResponse.ok("注册成功", body));
    }

    private Optional<UserAccount> authenticate(String username, String password) {
        try {
            List<Map<String, Object>> rows = preparedRiskRepository.findAuthUserByUsername(username);
            if (!rows.isEmpty()) {
                Map<String, Object> row = rows.get(0);
                String status = rowString(row, "status");
                String hash = rowString(row, "passwordHash");
                if (!"启用".equals(status) || !passwordHashService.verify(password, hash)) {
                    return Optional.empty();
                }
                String id = rowString(row, "id");
                String role = normalizeRole(rowString(row, "role"));
                String displayName = rowString(row, "displayName").isBlank() ? username : rowString(row, "displayName");
                preparedRiskRepository.updateSystemUserLastLogin(id);
                preparedRiskRepository.insertAuditLog("INFO", "auth login success " + username);
                return Optional.of(new UserAccount(username, "", displayName, role, true));
            }
        } catch (Exception ignored) {
            // Local fallback keeps registration/login usable before VM MySQL is connected.
        }
        return store.authenticate(username, password);
    }

    private UserAccount registerPersisted(String username, String password, String displayName, String email) {
        try {
            if (!preparedRiskRepository.findAuthUserByUsername(username).isEmpty()) {
                throw new IllegalArgumentException("账号已存在");
            }
            if (preparedRiskRepository.emailExists(email)) {
                throw new IllegalArgumentException("邮箱已被注册");
            }
            String role = preparedRiskRepository.countLoginUsers() == 0 ? SemiriskConstants.ROLE_ADMIN : SemiriskConstants.ROLE_OPERATOR;
            String id = "U-" + UUID.randomUUID().toString().substring(0, 8);
            preparedRiskRepository.insertSystemUser(id, username, displayName, email, passwordHashService.hash(password), role, "启用");
            preparedRiskRepository.insertAuditLog("INFO", "public registration persisted username=" + username + " role=" + role);
            return new UserAccount(username, "", displayName, role, true);
        } catch (IllegalArgumentException ex) {
            throw ex;
        } catch (DuplicateKeyException ex) {
            throw new IllegalArgumentException("账号或邮箱已存在");
        } catch (Exception ignored) {
            return store.register(username, password, displayName, email);
        }
    }

    private String rowString(Map<String, Object> row, String key) {
        Object value = row.get(key);
        return value == null ? "" : String.valueOf(value);
    }

    private String normalizeRole(String role) {
        try {
            return inputSanitizer.role(role);
        } catch (IllegalArgumentException ignored) {
            return SemiriskConstants.ROLE_OPERATOR;
        }
    }

    private String sanitizeEndpoint(String endpoint) {
        String clean = inputSanitizer.plain(endpoint, 512);
        if (!clean.startsWith("https://") && !clean.startsWith("http://")) {
            throw new IllegalArgumentException("Endpoint 必须以 http:// 或 https:// 开头");
        }
        return clean;
    }

    @PostMapping("/auth/logout")
    public ApiResponse<Map<String, Object>> logout(HttpServletRequest request) {
        tokenAuthService.revoke(request.getHeader("Authorization"));
        return ApiResponse.ok(Map.of("loggedOut", true));
    }

    @GetMapping("/auth/me")
    public ResponseEntity<ApiResponse<Map<String, Object>>> me(HttpServletRequest request) {
        return tokenAuthService.validate(request.getHeader("Authorization"))
                .map(principal -> ResponseEntity.ok(ApiResponse.ok(Map.of(
                        "username", principal.username(),
                        "displayName", principal.displayName(),
                        "role", principal.role(),
                        "modules", RolePermissionPolicy.modules(principal.role()),
                        "expiresAt", principal.expiresAt().toString()
                ))))
                .orElseGet(() -> ResponseEntity.status(401).body(ApiResponse.fail("未登录或 Token 已过期")));
    }

    @GetMapping("/auth/permissions/{module}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> permission(@PathVariable String module, HttpServletRequest request) {
        Optional<TokenAuthService.AuthPrincipal> principal = tokenAuthService.validate(request.getHeader("Authorization"));
        boolean allowed = principal.isPresent() && RolePermissionPolicy.canAccess(principal.get().role(), module);
        return allowed
                ? ResponseEntity.ok(ApiResponse.ok(Map.of("module", module, "allowed", true)))
                : ResponseEntity.status(403).body(ApiResponse.fail("无权访问模块：" + module));
    }

    @PostMapping("/auth/password-reset/request")
    public ApiResponse<Map<String, Object>> requestReset(@Valid @RequestBody PasswordResetRequest request) {
        String email = inputSanitizer.qqEmail(request.email());
        String token = store.createResetToken(email);
        return ApiResponse.ok("重置链接已发送，Token 15 分钟内有效且仅可使用一次",
                Map.of("email", email, "token", token, "expiresInMinutes", 15));
    }

    @GetMapping("/dashboard/overview")
    @SentinelResource(value = "dashboard.overview",
            blockHandlerClass = SentinelBlockHandler.class, blockHandler = "dashboardBlocked",
            fallbackClass = SentinelBlockHandler.class, fallback = "dashboardFallback")
    public ApiResponse<Map<String, Object>> dashboard() {
        syncPublicCrawlerRecords();
        return ApiResponse.ok(store.dashboard());
    }

    @GetMapping(value = "/data/templates/{type}", produces = "text/csv;charset=UTF-8")
    public ResponseEntity<byte[]> template(@PathVariable String type, HttpServletRequest request) {
        String csv = "supplier,material,stage,lead_time_days,risk_level\n"
                + "安芯物流,晶圆,仓储物流,7,中危\n"
                + "华南晶圆,硅片,生产制造,14,低危\n";
        // UTF-8 BOM (EF BB BF) is required for Excel/WPS to correctly open UTF-8 CSV files
        byte[] csvBytes = csv.getBytes(StandardCharsets.UTF_8);
        byte[] bom = new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
        byte[] response = new byte[bom.length + csvBytes.length];
        System.arraycopy(bom, 0, response, 0, bom.length);
        System.arraycopy(csvBytes, 0, response, bom.length, csvBytes.length);

        // Determine upload processing flow based on user role
        String flow = determineUploadFlow(request);

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, "text/csv;charset=UTF-8")
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"semirisk-" + type + "-template.csv\"")
                .header("Content-Transfer-Encoding", "binary")
                .header("X-Upload-Processing-Flow", flow)
                .body(response);
    }

    private String determineUploadFlow(HttpServletRequest request) {
        Optional<TokenAuthService.AuthPrincipal> principal = tokenAuthService.validate(
                request == null ? null : request.getHeader("Authorization"));
        if (principal.isEmpty()) return "上传→清洗→人工复核→导入";
        String role = principal.get().role();
        if ("ADMIN".equals(role) || "ANALYST".equals(role)) {
            return "上传→AI自动清洗→自动导入→日志追踪";
        }
        return "上传→清洗→人工复核→导入";
    }

    @PostMapping(value = "/data/uploads", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<UploadTask> upload(@RequestParam("file") MultipartFile file) throws IOException {
        UploadTask task = store.createUpload(file);
        String objectKey = uploadObjectKey(task.id(), task.filename());
        boolean stored = false;
        try {
            minioStorageService.putObject(objectKey, file.getBytes(), file.getContentType());
            stored = true;
        } catch (Exception ignored) {
            // MinIO 暂不可达时仍接收任务，前端显示明确状态。
        }
        try {
            preparedRiskRepository.insertUploadTask(task.id(), task.filename(), task.size(), task.status(), task.rows(), task.createdAt());
            preparedRiskRepository.insertAuditLog("INFO", "upload accepted " + task.filename() + (stored ? " stored=minio:" + objectKey : " stored=none"));
        } catch (Exception ignored) {
            // Local fallback keeps the project runnable before VM middleware is connected.
        }
        return ApiResponse.ok(stored ? "文件已上传至 MinIO 对象存储并进入解析队列" : "文件已进入解析队列（对象存储暂不可达）", task);
    }

    @GetMapping("/data/uploads")
    public ApiResponse<List<?>> uploads() {
        try {
            List<Map<String, Object>> rows = preparedRiskRepository.findUploadTasks(100);
            if (!rows.isEmpty()) {
                return ApiResponse.ok((List<?>) rows);
            }
        } catch (Exception ignored) {
            // Local fallback keeps the project runnable before VM middleware is connected.
        }
        return ApiResponse.ok((List<?>) store.uploadTasks());
    }

    @PostMapping("/data/uploads/{id}/parse")
    public ApiResponse<UploadTask> parseUpload(@PathVariable String id) {
        String filename = store.uploadTask(id).map(UploadTask::filename).orElseGet(() -> lookupUploadFilename(id));
        if (filename == null || filename.isBlank()) {
            return ApiResponse.fail("上传任务不存在或对象已过期");
        }
        String objectKey = uploadObjectKey(id, filename);
        UploadParseService.ParseResult result;
        try {
            byte[] content = minioStorageService.getObject(objectKey);
            result = uploadParseService.parse(filename, content);
        } catch (Exception ex) {
            return ApiResponse.fail("无法从对象存储读取文件进行解析：" + ex.getClass().getSimpleName());
        }
        UploadTask task;
        try {
            task = store.completeUpload(id, result.rows(), result.warnings());
        } catch (IllegalArgumentException ex) {
            task = new UploadTask(id, filename, 0, result.rows() > 0 ? "导入成功" : "无有效数据", Instant.now(), result.rows(), result.warnings());
        }
        try {
            preparedRiskRepository.updateUploadTask(task.id(), task.status(), task.rows());
            preparedRiskRepository.insertAuditLog("INFO", "upload parsed " + task.id() + " rows=" + task.rows());
        } catch (Exception ignored) {
            // Local fallback keeps the project runnable before VM middleware is connected.
        }
        return ApiResponse.ok("AI 解析与导入完成，真实解析 " + task.rows() + " 行", task);
    }

    private String uploadObjectKey(String id, String filename) {
        String safe = filename == null ? "file" : filename.replaceAll("[^A-Za-z0-9._\\-]+", "_");
        return "uploads/" + id + "/" + safe;
    }

    private String lookupUploadFilename(String id) {
        try {
            return preparedRiskRepository.findUploadTasks(200).stream()
                    .filter(row -> id.equals(String.valueOf(row.get("id"))))
                    .map(row -> String.valueOf(row.get("filename")))
                    .findFirst()
                    .orElse(null);
        } catch (Exception ignored) {
            return null;
        }
    }

    @GetMapping("/data/uploads/logs")
    public SseEmitter uploadLogs() {
        SseEmitter emitter = new SseEmitter(30_000L);
        sseExecutor.submit(() -> {
            try {
                for (String log : store.uploadLogLines(null)) {
                    emitter.send(SseEmitter.event().name("log").data(Map.of("time", Instant.now().toString(), "message", log)));
                    Thread.sleep(360);
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
        syncPublicCrawlerRecords();
        return ApiResponse.ok(store.riskAnalysis(window));
    }

    @GetMapping("/risk/events/{id}")
    public ApiResponse<Map<String, Object>> riskDetail(@PathVariable String id) {
        syncPublicCrawlerRecords();
        return ApiResponse.ok(store.riskDetail(id));
    }

    @PostMapping("/risk/events/{id}/assign")
    public ApiResponse<Map<String, Object>> assignRisk(@PathVariable String id, @RequestBody(required = false) Map<String, String> body) {
        String owner = body == null ? "默认负责人" : body.getOrDefault("owner", "默认负责人");
        try {
            store.updateAlertStatus(id, "处理中");
        } catch (IllegalArgumentException ignored) {
            // Unknown risk ids still return a deterministic response for the current detail page.
        }
        // store.updateAlertStatus already persists to DB, skip duplicate DB update
        return ApiResponse.ok("负责人已指派", Map.of("id", id, "owner", owner, "status", "处理中"));
    }

    @PostMapping("/risk/events/{id}/dispatch-report")
    public ApiResponse<Map<String, Object>> dispatchReport(@PathVariable String id) {
        try {
            store.updateAlertStatus(id, "处理中");
        } catch (IllegalArgumentException ignored) {
            // Unknown risk ids still return a deterministic response for the current detail page.
        }
        // store.updateAlertStatus already persists to DB, skip duplicate DB update
        return ApiResponse.ok("处置报告已下发", Map.of("id", id, "status", "处理中"));
    }

    @GetMapping("/reports/templates")
    public ApiResponse<List<Map<String, String>>> reportTemplates() {
        return ApiResponse.ok(List.of(
                Map.of("id", "risk-assessment", "name", "风险评估报告", "scenario", "AI 研判风险评分、影响范围和闭环处置", "format", "PDF"),
                Map.of("id", "supply-chain", "name", "供应链分析报告", "scenario", "AI 分析物流路径、供应商韧性和替代方案", "format", "PDF"),
                Map.of("id", "enterprise-dd", "name", "企业尽调报告", "scenario", "AI 汇总企业主体、公开源事件和合作建议", "format", "PDF")
        ));
    }

    @PostMapping("/reports/jobs")
    @SentinelResource(value = "reports.create",
            blockHandlerClass = SentinelBlockHandler.class, blockHandler = "reportBlocked")
    public ApiResponse<ReportJob> createReport(@Valid @RequestBody ReportRequest request) {
        String fmt = request.format() == null || request.format().isBlank() ? "PDF" : request.format().toUpperCase();
        ReportJob job = store.createReport(request.template(), request.language(), fmt, request.threshold());
        try {
            preparedRiskRepository.upsertReportJob(job.id(), job.template(), job.language(), job.format(), job.threshold(), job.status(), job.progress(), job.step(), job.downloadUrl(), job.createdAt());
            preparedRiskRepository.insertAuditLog("INFO", "report job created " + job.id());
        } catch (Exception ignored) {
            // Local fallback keeps the project runnable before VM middleware is connected.
        }
        return ApiResponse.ok("报告生成任务已启动", job);
    }

    @GetMapping("/reports/jobs/{id}")
    public ApiResponse<?> reportJob(@PathVariable String id) {
        if (store.reportJob(id).isPresent()) {
            ReportJob advanced = store.advanceReport(id);
            try {
                preparedRiskRepository.upsertReportJob(advanced.id(), advanced.template(), advanced.language(), advanced.format(), advanced.threshold(), advanced.status(), advanced.progress(), advanced.step(), advanced.downloadUrl(), advanced.createdAt());
            } catch (Exception ignored) {
                // Local fallback keeps the project runnable before VM middleware is connected.
            }
            return ApiResponse.ok(advanced);
        }
        try {
            Map<String, Object> row = preparedRiskRepository.findReportJob(id).stream().findFirst().orElse(Map.of());
            if (!row.isEmpty()) {
                return ApiResponse.ok(row);
            }
        } catch (Exception ignored) {
            // Local fallback keeps the project runnable before VM middleware is connected.
        }
        return ApiResponse.fail("报告任务不存在");
    }

    @GetMapping("/reports/{id}/download")
    public ResponseEntity<byte[]> downloadReport(@PathVariable String id) {
        syncPublicCrawlerRecords();
        ReportJob job = store.reportJob(id).orElse(null);
        Map<String, Object> persisted = Map.of();
        if (job == null) {
            try {
                persisted = preparedRiskRepository.findReportJob(id).stream().findFirst().orElse(Map.of());
            } catch (Exception ignored) {
                // Local fallback keeps the project runnable before VM middleware is connected.
            }
        }
        String template = job == null ? String.valueOf(persisted.getOrDefault("template", "risk-assessment")) : job.template();
        String language = job == null ? String.valueOf(persisted.getOrDefault("language", "中文")) : job.language();
        String format = job == null ? String.valueOf(persisted.getOrDefault("format", "PDF")) : job.format();
        if (format == null || format.isBlank()) format = "PDF";
        List<String> findings = store.aiReportLines(id, template, language);
        ReportFile report = ReportFileFactory.build(id, template, language, format, findings);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(report.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + report.filename() + "\"")
                .body(report.body());
    }

    @GetMapping("/alerts")
    public ApiResponse<List<?>> alerts(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String level,
            @RequestParam(required = false) String status) {
        syncPublicCrawlerRecords();
        String kw = keyword == null ? "" : keyword.trim();
        List<RiskAlert> publicAlerts = filterAlerts(store.publicSignalAlerts(), kw, level, status);
        if (!publicAlerts.isEmpty()) {
            return ApiResponse.ok((List<?>) publicAlerts);
        }
        if (!store.dailyRiskSnapshot().signals().isEmpty() || "待采集".equals(store.dailyRiskSnapshot().level())) {
            return ApiResponse.ok(List.of());
        }
        try {
            List<Map<String, Object>> rows = preparedRiskRepository.findAlerts(kw.isBlank() ? null : kw, blankToNull(level), blankToNull(status), 100);
            if (!rows.isEmpty()) {
                return ApiResponse.ok((List<?>) rows);
            }
        } catch (Exception ignored) {
            // Local fallback keeps the project runnable before VM middleware is connected.
        }
        List<RiskAlert> filtered = filterAlerts(store.alerts(), kw, level, status);
        return ApiResponse.ok((List<?>) filtered);
    }

    private List<RiskAlert> filterAlerts(List<RiskAlert> alerts, String keyword, String level, String status) {
        return alerts.stream()
                .filter(alert -> keyword == null || keyword.isBlank() || alert.title().contains(keyword) || alert.source().contains(keyword))
                .filter(alert -> level == null || level.isBlank() || alert.level().equals(level))
                .filter(alert -> status == null || status.isBlank() ? !"已忽略".equals(alert.status()) : alert.status().equals(status))
                .toList();
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private void syncPublicCrawlerRecords() {
        Instant now = Instant.now();
        // 实时刷新模式：冷却 30 秒（之前 120 秒），确保 Dashboard 接口能反映最新爬取数据
        if (lastCrawlerSync.isAfter(now.minusSeconds(crawlerSyncCooldownSeconds))) {
            return;
        }
        lastCrawlerSync = now;
        List<CrawlerSignal> records = publicCrawlerClient.today();
        store.refreshDailyRiskRecords(records);
        knowledgeSearchIndexService.sync(records);
    }

    @org.springframework.beans.factory.annotation.Value("${semirisk.crawler-sync.cooldown-seconds:60}")
    private int crawlerSyncCooldownSeconds;

    /** 定时任务：每分钟主动拉取 data-service 最新信号，写入网关风险快照。 */
    @org.springframework.scheduling.annotation.Scheduled(fixedDelayString = "${semirisk.crawler-sync.tick-ms:60000}", initialDelayString = "${semirisk.crawler-sync.initial-delay-ms:15000}")
    public void scheduledCrawlerSync() {
        try {
            syncPublicCrawlerRecords();
        } catch (Exception ex) {
            // ignore - downstream may be temporarily unavailable
        }
    }

    @GetMapping("/alerts/counts")
    public ApiResponse<Map<String, Long>> alertCounts() {
        syncPublicCrawlerRecords();
        Map<String, Long> counts = new HashMap<>();
        store.publicSignalAlerts().stream()
                .filter(alert -> !"已忽略".equals(alert.status()))
                .forEach(alert -> counts.merge(alert.level(), 1L, Long::sum));
        return ApiResponse.ok(counts);
    }

    @PutMapping("/alerts/{id}/ignore")
    public ApiResponse<RiskAlert> ignoreAlert(@PathVariable String id) {
        RiskAlert alert = store.updateAlertStatus(id, "已忽略");
        // store.updateAlertStatus already persist to DB via persistAlertStatus, skip duplicate
        return ApiResponse.ok("告警已忽略", alert);
    }

    @PostMapping("/alerts/batch-process")
    public ApiResponse<Map<String, Object>> batchProcess(@RequestBody BatchRequest request) {
        request.ids().forEach(id -> store.updateAlertStatus(id, "处理中"));
        // store.updateAlertStatus already persists to DB, skip duplicate
        return ApiResponse.ok("批量处理指令下发成功", Map.of("processed", request.ids().size()));
    }

    @GetMapping("/gis/map")
    public ApiResponse<Map<String, Object>> gis(@RequestParam(required = false) String layers) {
        syncPublicCrawlerRecords();
        return ApiResponse.ok(store.gis(layers));
    }

    @GetMapping("/enterprises")
    public ApiResponse<List<Map<String, Object>>> enterprises() {
        return ApiResponse.ok(store.enterpriseCatalog());
    }

    @GetMapping("/enterprises/profile")
    public ApiResponse<Map<String, Object>> enterprise(@RequestParam(required = false) String keyword) {
        syncPublicCrawlerRecords();
        return ApiResponse.ok(store.enterprise(keyword));
    }

    @GetMapping("/knowledge/search")
    public ApiResponse<Map<String, Object>> knowledge(@RequestParam(required = false) String query) {
        syncPublicCrawlerRecords();
        List<Map<String, Object>> indexedResults = knowledgeSearchIndexService.search(query, 30);
        return ApiResponse.ok(store.knowledge(query, indexedResults));
    }

    @PostMapping("/knowledge/ask")
    @SentinelResource(value = "knowledge.ask",
            blockHandlerClass = SentinelBlockHandler.class, blockHandler = "knowledgeBlocked",
            fallbackClass = SentinelBlockHandler.class, fallback = "knowledgeFallback")
    public ApiResponse<Map<String, Object>> askKnowledge(@Valid @RequestBody KnowledgeAskRequest request) {
        syncPublicCrawlerRecords();
        List<Map<String, Object>> indexedResults = knowledgeSearchIndexService.search(request.question(), 8);
        return ApiResponse.ok("AI 知识库智能体回答完成", store.askKnowledgeAgent(request.question(), indexedResults));
    }

    @GetMapping("/knowledge/preview/{id}")
    public ResponseEntity<byte[]> preview(@PathVariable String id) {
        // 优先从 MinIO 取真实文档对象；其次返回知识库文档真实正文；都没有时给出明确说明。
        try {
            List<Map<String, Object>> docs = preparedRiskRepository.findKnowledgeDocById(id);
            if (!docs.isEmpty()) {
                Map<String, Object> doc = docs.get(0);
                String objectKey = String.valueOf(doc.getOrDefault("objectKey", ""));
                if (objectKey != null && !objectKey.isBlank() && !"null".equals(objectKey) && minioStorageService.objectExists(objectKey)) {
                    byte[] body = minioStorageService.getObject(objectKey);
                    return ResponseEntity.ok()
                            .contentType(MediaType.parseMediaType(minioStorageService.contentType(objectKey)))
                            .body(body);
                }
                String text = "标题：" + doc.get("title") + "\n分类：" + doc.get("category") + "\n来源：" + doc.get("source")
                        + "\n维度：" + doc.get("dimension") + "\n原文链接：" + doc.get("sourceUrl") + "\n\n" + doc.get("content");
                return ResponseEntity.ok()
                        .contentType(MediaType.parseMediaType("text/plain;charset=UTF-8"))
                        .body(text.getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception ignored) {
            // 落到下面的统一说明。
        }
        String fallback = "知识文档 " + id + " 暂无可预览的对象。公开源文章请通过原文链接查看，内部/政策文档需先上传至 MinIO 对象存储。";
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("text/plain;charset=UTF-8"))
                .body(fallback.getBytes(StandardCharsets.UTF_8));
    }

    @GetMapping("/ai/reports/latest")
    public ApiResponse<Map<String, Object>> latestAiReport() {
        syncPublicCrawlerRecords();
        return ApiResponse.ok(store.latestAiReport());
    }

    @PostMapping("/ai/reports/refresh")
    public ApiResponse<Map<String, Object>> refreshAiReport() {
        syncPublicCrawlerRecords();
        return ApiResponse.ok("AI 本日风险报告已生成", store.generateDailyAiReport());
    }

    @GetMapping("/system/overview")
    public ApiResponse<Map<String, Object>> systemOverview() {
        Map<String, Object> overview = new HashMap<>(store.systemOverview());
        try {
            List<Map<String, Object>> users = preparedRiskRepository.findSystemUsers();
            if (!users.isEmpty()) {
                overview.put("users", users);
            }
        } catch (Exception ignored) {
            // Local fallback keeps the project runnable before VM middleware is connected.
        }
        return ApiResponse.ok(overview);
    }

    @PostMapping("/system/users")
    public ApiResponse<SystemUser> addUser(@Valid @RequestBody AddUserRequest request) {
        String username = inputSanitizer.username(request.username());
        String email = inputSanitizer.qqEmail(request.email());
        String role = inputSanitizer.role(request.role());
        SystemUser user = store.addSystemUser(username, email, role, "禁用");
        try {
            preparedRiskRepository.insertSystemUser(user.id(), user.username(), user.username(), user.email(), null, user.role(), user.status());
            preparedRiskRepository.insertAuditLog("INFO", "system user created " + user.username());
        } catch (Exception ignored) {
            // Local fallback keeps the project runnable before VM middleware is connected.
        }
        return ApiResponse.ok("系统用户已创建", user);
    }

    @PostMapping("/system/users/login")
    public ApiResponse<Map<String, Object>> upsertLoginUser(@Valid @RequestBody LoginUserRequest request) {
        String username = inputSanitizer.username(request.username());
        String email = inputSanitizer.qqEmail(request.email());
        String displayName = inputSanitizer.displayName(request.displayName());
        String password = inputSanitizer.password(request.password());
        String role = inputSanitizer.role(request.role());
        UserAccount account = store.upsertLoginUser(username, password, displayName, email, role);
        try {
            String id = "U-" + UUID.randomUUID().toString().substring(0, 8);
            preparedRiskRepository.upsertSystemLoginUser(id, username, displayName, email, passwordHashService.hash(password), role, "启用");
            preparedRiskRepository.insertAuditLog("INFO", "login user upserted " + username + " role=" + role);
        } catch (Exception ignored) {
            // Local fallback keeps admin provisioning usable before VM MySQL is connected.
        }
        return ApiResponse.ok("登录用户已创建/更新", Map.of(
                "username", account.username(),
                "displayName", account.displayName(),
                "role", account.role(),
                "modules", RolePermissionPolicy.modules(account.role())
        ));
    }

    @PutMapping("/system/users/{id}/status")
    public ApiResponse<SystemUser> updateUserStatus(@PathVariable String id, @Valid @RequestBody StatusRequest request) {
        String cleanStatus = inputSanitizer.status(request.status());
        SystemUser user = store.updateSystemUserStatus(id, cleanStatus);
        try {
            preparedRiskRepository.updateSystemUserStatus(id, cleanStatus);
            preparedRiskRepository.insertAuditLog("WARN", "system user status changed " + id + " -> " + cleanStatus);
        } catch (Exception ignored) {
            // Local fallback keeps the project runnable before VM middleware is connected.
        }
        return ApiResponse.ok("用户状态已更新，后续请求需重新获取 Token", user);
    }

    @DeleteMapping("/system/users/{id}")
    public ApiResponse<Map<String, Object>> deleteUser(@PathVariable String id) {
        store.deleteSystemUser(id);
        try {
            preparedRiskRepository.deleteSystemUser(id);
            preparedRiskRepository.insertAuditLog("ERROR", "system user deleted " + id);
        } catch (Exception ignored) {
            // Local fallback keeps the project runnable before VM middleware is connected.
        }
        return ApiResponse.ok("用户已物理删除", Map.of("id", id));
    }

    @PostMapping("/system/models/ping")
    public ApiResponse<Map<String, Object>> modelPing(@RequestBody Map<String, String> request) {
        String model = inputSanitizer.plain(request.getOrDefault("model", "default"), 128);
        String endpoint = request.get("endpoint");
        Map<String, Object> result = healthProbeService.probeModelEndpoint(model, endpoint);
        boolean reachable = Boolean.TRUE.equals(result.get("reachable"));
        return ApiResponse.ok(reachable ? "模型 endpoint 连通性测试成功" : "模型 endpoint 暂不可达", result);
    }

    @PostMapping("/system/models/config")
    public ApiResponse<AiModelConfig> saveModelConfig(@Valid @RequestBody AiModelConfigRequest request) {
        String model = inputSanitizer.plain(request.model(), 128);
        String endpoint = sanitizeEndpoint(request.endpoint());
        String apiKey = inputSanitizer.plain(request.apiKey(), 256);
        AiModelConfig config = store.saveAiModelConfig(model, endpoint, apiKey);
        try {
            preparedRiskRepository.upsertAiModelConfig(config.model(), config.endpoint(), config.maskedApiKey(), config.configured(), config.updatedAt());
            preparedRiskRepository.insertAuditLog("INFO", "AI model config saved " + config.model());
        } catch (Exception ignored) {
            // Local fallback keeps the project runnable before VM middleware is connected.
        }
        return ApiResponse.ok("AI 模型 API Key 已保存", config);
    }

    @GetMapping("/system/models/config")
    public ApiResponse<?> modelConfigs() {
        try {
            List<Map<String, Object>> rows = preparedRiskRepository.findAiModelConfigs();
            if (!rows.isEmpty()) {
                Map<String, Map<String, Object>> configs = new HashMap<>();
                rows.forEach(row -> configs.put(String.valueOf(row.get("model")), row));
                return ApiResponse.ok(configs);
            }
        } catch (Exception ignored) {
            // Local fallback keeps the project runnable before VM middleware is connected.
        }
        return ApiResponse.ok(store.aiModelConfigs());
    }

    @GetMapping("/risk-score/today")
    public ApiResponse<DailyRiskSnapshot> todayRiskScore() {
        syncPublicCrawlerRecords();
        return ApiResponse.ok(store.dailyRiskSnapshot());
    }

    @PostMapping("/risk-score/recalculate")
    public ApiResponse<DailyRiskSnapshot> recalculateRiskScore() {
        syncPublicCrawlerRecords();
        return ApiResponse.ok("AI 风险自动测算完成", store.dailyRiskSnapshot());
    }

    @PostMapping("/system/agents/{name}/trigger")
    public ApiResponse<Map<String, Object>> triggerAgent(@PathVariable String name) {
        String lower = name == null ? "" : name.toLowerCase();
        String result;
        if (lower.contains("报告") || lower.contains("report")) {
            Map<String, Object> report = store.generateDailyAiReport();
            result = "已触发 AI 报告生成，aiCalled=" + report.getOrDefault("aiCalled", false);
        } else {
            List<CrawlerSignal> records = publicCrawlerClient.today();
            store.refreshDailyRiskRecords(records);
            knowledgeSearchIndexService.sync(records);
            result = "已触发公开源爬虫与风险测算，纳入 " + records.size() + " 条真实信号";
        }
        try {
            preparedRiskRepository.insertAuditLog("INFO", "agent triggered " + name + " -> " + result);
        } catch (Exception ignored) {
            // Local fallback keeps the project runnable before VM middleware is connected.
        }
        return ApiResponse.ok("Agent 已手动触发", Map.of("agent", name, "result", result, "triggeredAt", Instant.now().toString()));
    }

    @PostMapping("/system/datasources/{name}/reconnect")
    public ApiResponse<Map<String, Object>> reconnect(@PathVariable String name) {
        Map<String, Object> probe = healthProbeService.probeOne(name);
        boolean reachable = Boolean.TRUE.equals(probe.get("reachable"));
        try {
            preparedRiskRepository.insertAuditLog(reachable ? "INFO" : "WARN", "datasource reconnect " + name + " reachable=" + reachable);
        } catch (Exception ignored) {
            // Local fallback keeps the project runnable before VM middleware is connected.
        }
        return ApiResponse.ok(reachable ? "数据源连通正常" : "数据源不可达，请检查中间件状态", probe);
    }

    public record LoginRequest(@NotBlank String username, @NotBlank String password, boolean rememberMe, String captchaToken) {
    }

    public record RegisterRequest(@NotBlank String username, @NotBlank @Email String email, @NotBlank String password, @NotBlank String displayName) {
    }

    public record PasswordResetRequest(@NotBlank @Email String email) {
    }

    public record ReportRequest(@NotBlank String template, @NotBlank String language, @NotBlank String format, int threshold) {
    }

    public record BatchRequest(@NotNull List<String> ids) {
    }

    public record AddUserRequest(@NotBlank String username, @NotBlank @Email String email, @NotBlank String role) {
    }

    public record LoginUserRequest(@NotBlank String username, @NotBlank @Email String email, @NotBlank String displayName, @NotBlank String password, @NotBlank String role) {
    }

    public record StatusRequest(@NotBlank String status) {
    }

    public record AiModelConfigRequest(@NotBlank String model, @NotBlank String endpoint, @NotBlank String apiKey) {
    }

    public record KnowledgeAskRequest(@NotBlank String question) {
    }
}
