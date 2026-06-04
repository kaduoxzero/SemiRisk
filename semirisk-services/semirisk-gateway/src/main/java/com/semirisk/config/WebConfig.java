package com.semirisk.config;

import com.semirisk.api.ApiResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.semirisk.common.RolePermissionPolicy;
import com.semirisk.security.CsrfTokenService;
import com.semirisk.security.TokenAuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.ConstraintViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@org.springframework.context.annotation.Configuration
public class WebConfig implements WebMvcConfigurer {

    private static final String CSRF_HEADER = "X-CSRF-Token";

    private final ObjectMapper objectMapper;
    private final CsrfTokenService csrfTokenService;
    private final TokenAuthService tokenAuthService;

    public WebConfig(ObjectMapper objectMapper, CsrfTokenService csrfTokenService, TokenAuthService tokenAuthService) {
        this.objectMapper = objectMapper;
        this.csrfTokenService = csrfTokenService;
        this.tokenAuthService = tokenAuthService;
    }

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        registry.addRedirectViewController("/", "/index.html");
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOriginPatterns("http://localhost:*", "http://127.0.0.1:*")
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(true);
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new HandlerInterceptor() {
                    @Override
                    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
                        String uri = request.getRequestURI();
                        String method = request.getMethod();
                        if ("OPTIONS".equalsIgnoreCase(method)) {
                            return true;
                        }
                        if (uri.startsWith("/api/") && requiresCsrf(method) && !csrfValid(request)) {
                            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                            response.setContentType("application/json;charset=UTF-8");
                            response.getWriter().write(objectMapper.writeValueAsString(ApiResponse.fail("CSRF Token 无效或缺失")));
                            return false;
                        }
                        if (uri.startsWith("/api/auth/")
                                || uri.equals("/api/dashboard/overview")
                                || uri.equals("/api/risk-score/today")
                                || ("GET".equalsIgnoreCase(method) && uri.startsWith("/api/reports/") && uri.endsWith("/download"))) {
                            return true;
                        }
                        var principal = tokenAuthService.validate(request.getHeader("Authorization"));
                        if (uri.startsWith("/api/") && principal.isEmpty()) {
                            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                            response.setContentType("application/json;charset=UTF-8");
                            response.getWriter().write(objectMapper.writeValueAsString(ApiResponse.fail("请先登录")));
                            return false;
                        }
                        if (principal.isPresent()) {
                            TokenAuthService.AuthPrincipal value = principal.get();
                            String module = moduleFor(uri);
                            if (module != null && !RolePermissionPolicy.canAccess(value.role(), module)) {
                                response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                                response.setContentType("application/json;charset=UTF-8");
                                response.getWriter().write(objectMapper.writeValueAsString(ApiResponse.fail("无权访问模块：" + module)));
                                return false;
                            }
                            request.setAttribute("authPrincipal", value);
                        }
                        return true;
                    }
                })
                .addPathPatterns("/api/**");
    }

    private boolean requiresCsrf(String method) {
        return !"GET".equalsIgnoreCase(method)
                && !"HEAD".equalsIgnoreCase(method)
                && !"OPTIONS".equalsIgnoreCase(method);
    }

    private boolean csrfValid(HttpServletRequest request) {
        return csrfTokenService.validate(request.getHeader(CSRF_HEADER));
    }

    private String moduleFor(String uri) {
        if (uri.startsWith("/api/data/")) return "upload";
        if (uri.startsWith("/api/risk/analysis")) return "analysis";
        if (uri.startsWith("/api/risk/events/")) return "detail";
        if (uri.startsWith("/api/reports/")) return "report";
        if (uri.startsWith("/api/alerts")) return "alerts";
        if (uri.startsWith("/api/gis/")) return "gis";
        if (uri.startsWith("/api/enterprises/")) return "enterprise";
        if (uri.startsWith("/api/knowledge/")) return "knowledge";
        if (uri.startsWith("/api/system/")) return "system";
        if (uri.startsWith("/api/risk-score/")) return "dashboard";
        return null;
    }

    @RestControllerAdvice
    public static class ApiExceptionHandler {

        @ExceptionHandler({IllegalArgumentException.class, ConstraintViolationException.class, MethodArgumentNotValidException.class})
        public ResponseEntity<ApiResponse<Object>> badRequest(Exception ex) {
            return ResponseEntity.badRequest().body(ApiResponse.fail(ex.getMessage()));
        }

        @ExceptionHandler(Exception.class)
        public ResponseEntity<ApiResponse<Object>> serverError(Exception ex) {
            return ResponseEntity.internalServerError().body(ApiResponse.fail(ex.getMessage()));
        }
    }
}
