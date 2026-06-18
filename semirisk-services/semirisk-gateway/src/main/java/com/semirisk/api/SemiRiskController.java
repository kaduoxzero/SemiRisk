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
import com.semirisk.security.PasswordResetTokenService;
import com.semirisk.security.RedisLoginGuardService;
import com.semirisk.security.TokenAuthService;
import com.semirisk.security.VerificationCodeService;
import com.semirisk.service.KnowledgeSearchIndexService;
import com.semirisk.service.MinioStorageService;
import com.semirisk.service.PublicCrawlerClient;
import com.semirisk.service.HealthProbeService;
import com.semirisk.service.UploadAiEvaluateService;
import com.semirisk.service.EmailService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import jakarta.servlet.http.HttpServletRequest;
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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.HashSet;
import java.util.UUID;

@Validated
@RestController
@RequestMapping("/api")
public class SemiRiskController {

    private static final Logger log = LoggerFactory.getLogger(SemiRiskController.class);

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
    private final UploadAiEvaluateService uploadAiEvaluateService;
    private final HealthProbeService healthProbeService;
    private final EmailService emailService;
    private final VerificationCodeService verificationCodeService;
    private final PasswordResetTokenService passwordResetTokenService;
    private volatile Instant lastCrawlerSync = Instant.EPOCH;

    public SemiRiskController(SemiRiskStore store, PublicCrawlerClient publicCrawlerClient, KnowledgeSearchIndexService knowledgeSearchIndexService, PreparedRiskRepository preparedRiskRepository, RedisLoginGuardService redisLoginGuardService, PasswordHashService passwordHashService, InputSanitizer inputSanitizer, TokenAuthService tokenAuthService, CsrfTokenService csrfTokenService, MinioStorageService minioStorageService, UploadAiEvaluateService uploadAiEvaluateService, HealthProbeService healthProbeService, EmailService emailService, VerificationCodeService verificationCodeService, PasswordResetTokenService passwordResetTokenService) {
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
        this.uploadAiEvaluateService = uploadAiEvaluateService;
        this.healthProbeService = healthProbeService;
        this.emailService = emailService;
        this.verificationCodeService = verificationCodeService;
        this.passwordResetTokenService = passwordResetTokenService;
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
        LoginState state = redisLoginGuardService.loginState(username);
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
                    LoginState failed = redisLoginGuardService.recordFailure(username);
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
        // 校验邮箱验证码
        if (!verificationCodeService.verify(email, request.verificationCode())) {
            return ResponseEntity.badRequest().body(ApiResponse.fail("验证码错误或已过期"));
        }
        // 用户名唯一性
        if (!preparedRiskRepository.findAuthUserByUsername(username).isEmpty()) {
            throw new IllegalArgumentException("账号已存在");
        }
        // 邮箱唯一性
        if (preparedRiskRepository.emailExists(email)) {
            throw new IllegalArgumentException("邮箱已被注册");
        }
        String role = preparedRiskRepository.countLoginUsers() == 0 ? SemiriskConstants.ROLE_ADMIN : SemiriskConstants.ROLE_OPERATOR;
        String id = "U-" + UUID.randomUUID().toString().substring(0, 8);
        preparedRiskRepository.insertSystemUser(id, username, displayName, email, passwordHashService.hash(password), role, "启用");
        preparedRiskRepository.insertAuditLog("INFO", "public registration persisted username=" + username + " role=" + role);
        UserAccount account = new UserAccount(username, "", displayName, role, true);
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

    @PostMapping("/auth/send-verification-code")
    public ApiResponse<Void> sendVerificationCode(@Valid @RequestBody SendVerificationCodeRequest request) {
        String email = inputSanitizer.qqEmail(request.email());
        String code = verificationCodeService.generate(email);
        try {
            emailService.sendVerificationCode(email, code);
        } catch (Exception ex) {
            log.error("Failed to send verification email to {}", email, ex);
        }
        return ApiResponse.ok(null);
    }

    private record SendVerificationCodeRequest(@NotBlank @Email String email) {
    }

    private Optional<UserAccount> authenticate(String username, String password) {
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
        return Optional.empty();
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
                .map(principal -> {
                    Map<String, Object> data = new HashMap<>();
                    data.put("username", principal.username());
                    data.put("displayName", principal.displayName());
                    data.put("role", principal.role());
                    data.put("modules", RolePermissionPolicy.modules(principal.role()));
                    data.put("expiresAt", principal.expiresAt().toString());
                    // 补充 lastLoginAt
                    try {
                        List<Map<String, Object>> rows = preparedRiskRepository.findAuthUserByUsername(principal.username());
                        if (!rows.isEmpty()) {
                            Object lastLogin = rows.get(0).get("lastLoginAt");
                            if (lastLogin != null && !String.valueOf(lastLogin).isEmpty()) {
                                data.put("lastLoginAt", String.valueOf(lastLogin));
                            }
                        }
                    } catch (Exception ignored) {
                        // DB may be temporarily unavailable
                    }
                    return ResponseEntity.ok(ApiResponse.ok(data));
                })
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
        // 检查邮箱是否在系统中注册
        List<Map<String, Object>> rows = preparedRiskRepository.findAuthUserByEmail(email);
        if (rows.isEmpty()) {
            return ApiResponse.ok("重置验证码已发送至您的邮箱", null);
        }
        String token = passwordResetTokenService.createToken(email);
        try {
            emailService.sendPasswordResetCode(email, token);
        } catch (Exception ex) {
            log.error("Failed to send password reset email to {}", email, ex);
        }
        return ApiResponse.ok("重置验证码已发送至您的邮箱",
                Map.of("email", email, "expiresInMinutes", 15));
    }

    @PostMapping("/auth/password-reset/confirm")
    public ApiResponse<Map<String, Object>> confirmReset(@Valid @RequestBody PasswordResetConfirmRequest request) {
        String email = inputSanitizer.qqEmail(request.email());
        String newPassword = inputSanitizer.password(request.newPassword());
        if (newPassword.length() < 8) {
            throw new IllegalArgumentException("密码至少 8 位");
        }
        // 校验重置验证码
        if (!passwordResetTokenService.validateAndConsume(request.resetCode()).filter(e -> e.equalsIgnoreCase(email)).isPresent()) {
            throw new IllegalArgumentException("验证码错误或已过期");
        }
        // 查找用户并更新密码
        List<Map<String, Object>> rows = preparedRiskRepository.findAuthUserByEmail(email);
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("该邮箱未注册");
        }
        String userId = rowString(rows.get(0), "id");
        preparedRiskRepository.updateSystemUserPassword(userId, passwordHashService.hash(newPassword));
        preparedRiskRepository.insertAuditLog("INFO", "password reset for email=" + email);
        return ApiResponse.ok("密码重置成功，请使用新密码登录", null);
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
        // Excel/WPS 正确打开 UTF-8 CSV 文件需要 UTF-8 BOM (EF BB BF)
        byte[] csvBytes = csv.getBytes(StandardCharsets.UTF_8);
        byte[] bom = new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
        byte[] response = new byte[bom.length + csvBytes.length];
        System.arraycopy(bom, 0, response, 0, bom.length);
        System.arraycopy(csvBytes, 0, response, bom.length, csvBytes.length);

        // 根据用户角色确定上传处理流程
        String flow = determineUploadFlow(request);

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, "text/csv;charset=UTF-8")
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"semirisk-" + type + "-template.csv\"")
                .header("Content-Transfer-Encoding", "binary")
                .header("X-Upload-Processing-Flow", flow)
                .body(response);
    }

    private String determineUploadFlow(HttpServletRequest request) {
        return "上传→AI自动分析→自动入库";
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
            // MinIO 暂不可达时仍接收任务
        }
        try {
            preparedRiskRepository.insertUploadTask(task.id(), task.filename(), task.size(), task.status(), task.rows(), task.createdAt());
            preparedRiskRepository.insertAuditLog("INFO", "upload accepted " + task.filename() + (stored ? " stored=minio:" + objectKey : " stored=none"));
        } catch (Exception ex) {
            log.error("Failed to persist upload task to MySQL, task={}", task.id(), ex);
        }
        // 异步触发 AI 评估
        if (stored) {
            uploadAiEvaluateService.evaluateAsync(task.id(), objectKey, task.filename());
        }
        return ApiResponse.ok(stored ? "文件已上传，AI 自动分析中" : "文件已接收，AI 分析将稍后进行（对象存储暂不可达）", task);
    }

    @GetMapping("/data/uploads")
    public ApiResponse<List<?>> uploads() {
        List<Map<String, Object>> rows = preparedRiskRepository.findUploadTasks(100);
        return ApiResponse.ok((List<?>) rows);
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
        } catch (Exception ex) {
            log.warn("Failed to lookup upload filename from MySQL, id={}", id, ex);
            return null;
        }
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
            // 未知的风险 ID 仍为当前详情页返回确定性响应。
        }
        // store.updateAlertStatus 已持久化到数据库，跳过重复的数据库更新
        return ApiResponse.ok("负责人已指派", Map.of("id", id, "owner", owner, "status", "处理中"));
    }

    @PostMapping("/risk/events/{id}/dispatch-report")
    public ApiResponse<Map<String, Object>> dispatchReport(@PathVariable String id) {
        try {
            store.updateAlertStatus(id, "处理中");
        } catch (IllegalArgumentException ignored) {
            // 未知的风险 ID 仍为当前详情页返回确定性响应。
        }
        // store.updateAlertStatus 已持久化到数据库，跳过重复的数据库更新
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
        } catch (Exception ex) {
            log.error("Failed to persist report job to MySQL, job={}", job.id(), ex);
        }
        return ApiResponse.ok("报告生成任务已启动", job);
    }

    @GetMapping("/reports/jobs/{id}")
    public ApiResponse<?> reportJob(@PathVariable String id) {
        if (store.reportJob(id).isPresent()) {
            ReportJob advanced = store.advanceReport(id);
            try {
                preparedRiskRepository.upsertReportJob(advanced.id(), advanced.template(), advanced.language(), advanced.format(), advanced.threshold(), advanced.status(), advanced.progress(), advanced.step(), advanced.downloadUrl(), advanced.createdAt());
            } catch (Exception ex) {
                log.error("Failed to persist report job update to MySQL, job={}", advanced.id(), ex);
            }
            return ApiResponse.ok(advanced);
        }
        Map<String, Object> row = preparedRiskRepository.findReportJob(id).stream().findFirst().orElse(Map.of());
        if (!row.isEmpty()) {
            return ApiResponse.ok(row);
        }
        return ApiResponse.fail("报告任务不存在");
    }

    @GetMapping("/reports/{id}/download")
    public ResponseEntity<byte[]> downloadReport(@PathVariable String id) {
        syncPublicCrawlerRecords();
        ReportJob job = store.reportJob(id).orElse(null);
        Map<String, Object> persisted = Map.of();
        if (job == null) {
            persisted = preparedRiskRepository.findReportJob(id).stream().findFirst().orElse(Map.of());
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
        List<Map<String, Object>> rows = preparedRiskRepository.findAlerts(kw.isBlank() ? null : kw, blankToNull(level), blankToNull(status), 100);
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
            // 忽略 -- 下游服务可能暂时不可用
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
        // store.updateAlertStatus 已通过 persistAlertStatus 持久化到数据库，跳过重复操作
        return ApiResponse.ok("告警已忽略", alert);
    }

    @PostMapping("/alerts/batch-process")
    public ApiResponse<Map<String, Object>> batchProcess(@RequestBody BatchRequest request) {
        request.ids().forEach(id -> store.updateAlertStatus(id, "处理中"));
        // store.updateAlertStatus 已持久化到数据库，跳过重复操作
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
                // 查询当前活跃用户名：合并 auth_token 表 + 内存中未过期 token
                Set<String> activeUsernames = new HashSet<>(preparedRiskRepository.findActiveUsernames());
                activeUsernames.addAll(tokenAuthService.getActiveUsernames());
                // 统一转换为中文展示值，前端直接渲染
                for (Map<String, Object> user : users) {
                    String role = String.valueOf(user.get("role"));
                    switch (role) {
                        case "ADMIN" -> user.put("role", "管理员");
                        case "ANALYST" -> user.put("role", "分析师");
                        case "OPERATOR" -> user.put("role", "运营人员");
                    }
                    String status = String.valueOf(user.get("status"));
                    switch (status) {
                        case "启用" -> user.put("status", "启用");
                        case "禁用" -> user.put("status", "禁用");
                        default -> user.put("status", status);
                    }
                    // 登录状态：auth_token 表中有未过期 token → 在线；有 lastLoginAt → 上次登录；否则未登录
                    String username = String.valueOf(user.get("username"));
                    if (activeUsernames.contains(username)) {
                        user.put("loginStatus", "在线");
                    } else {
                        Object lastLogin = user.get("lastLoginAt");
                        if (lastLogin != null && !lastLogin.toString().isEmpty()) {
                            String formatted = formatRelativeTime(lastLogin);
                            user.put("loginStatus", "上次登录");
                            user.put("lastLoginAtFormatted", formatted);
                        } else {
                            user.put("loginStatus", "未登录");
                        }
                    }
                    // 权限模块
                    user.put("modules", RolePermissionPolicy.modules(role));
                }
                overview.put("users", users);
            }
        } catch (Exception ex) {
            log.error("Failed to fetch system users from MySQL", ex);
        }
        return ApiResponse.ok(overview);
    }

    private String formatRelativeTime(Object lastLoginObj) {
        try {
            LocalDateTime lastLogin;
            if (lastLoginObj instanceof LocalDateTime ldt) {
                lastLogin = ldt;
            } else if (lastLoginObj instanceof java.sql.Timestamp ts) {
                lastLogin = ts.toLocalDateTime();
            } else {
                // Try parsing as string
                lastLogin = LocalDateTime.parse(String.valueOf(lastLoginObj).replace(' ', 'T'));
            }
            LocalDateTime now = LocalDateTime.now(ZoneId.systemDefault());
            long minutes = java.time.Duration.between(lastLogin, now).toMinutes();
            if (minutes < 1) return "刚刚";
            if (minutes < 60) return minutes + " 分钟前";
            long hours = minutes / 60;
            if (hours < 24) return hours + " 小时前";
            long days = hours / 24;
            return days + " 天前";
        } catch (Exception ex) {
            return String.valueOf(lastLoginObj);
        }
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
        } catch (Exception ex) {
            log.error("Failed to persist system user to MySQL, user={}", user.username(), ex);
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
        } catch (Exception ex) {
            log.error("Failed to persist login user to MySQL, username={}", username, ex);
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
        } catch (Exception ex) {
            log.error("Failed to update system user status in MySQL, id={}", id, ex);
        }
        return ApiResponse.ok("用户状态已更新，后续请求需重新获取 Token", user);
    }

    @DeleteMapping("/system/users/{id}")
    public ApiResponse<Map<String, Object>> deleteUser(@PathVariable String id) {
        store.deleteSystemUser(id);
        try {
            preparedRiskRepository.deleteSystemUser(id);
            preparedRiskRepository.insertAuditLog("ERROR", "system user deleted " + id);
        } catch (Exception ex) {
            log.error("Failed to delete system user from MySQL, id={}", id, ex);
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
        } catch (Exception ex) {
            log.error("Failed to persist AI model config to MySQL, model={}", config.model(), ex);
        }
        return ApiResponse.ok("AI 模型 API Key 已保存", config);
    }

    @GetMapping("/system/models/config")
    public ApiResponse<?> modelConfigs() {
        List<Map<String, Object>> rows = preparedRiskRepository.findAiModelConfigs();
        Map<String, Map<String, Object>> configs = new HashMap<>();
        rows.forEach(row -> configs.put(String.valueOf(row.get("model")), row));
        return ApiResponse.ok(configs);
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
        } catch (Exception ex) {
            log.error("Failed to insert audit log for agent trigger, agent={}", name, ex);
        }
        return ApiResponse.ok("Agent 已手动触发", Map.of("agent", name, "result", result, "triggeredAt", Instant.now().toString()));
    }

    @PostMapping("/system/datasources/{name}/reconnect")
    public ApiResponse<Map<String, Object>> reconnect(@PathVariable String name) {
        Map<String, Object> probe = healthProbeService.probeOne(name);
        boolean reachable = Boolean.TRUE.equals(probe.get("reachable"));
        try {
            preparedRiskRepository.insertAuditLog(reachable ? "INFO" : "WARN", "datasource reconnect " + name + " reachable=" + reachable);
        } catch (Exception ex) {
            log.error("Failed to insert audit log for datasource reconnect, name={}", name, ex);
        }
        return ApiResponse.ok(reachable ? "数据源连通正常" : "数据源不可达，请检查中间件状态", probe);
    }

    public record LoginRequest(@NotBlank String username, @NotBlank String password, boolean rememberMe, String captchaToken) {
    }

    public record RegisterRequest(@NotBlank String username, @NotBlank @Email String email, @NotBlank String password, @NotBlank String displayName, @NotBlank String verificationCode) {
    }

    public record PasswordResetRequest(@NotBlank @Email String email) {
    }

    public record PasswordResetConfirmRequest(@NotBlank @Email String email, @NotBlank String resetCode, @NotBlank String newPassword) {
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
