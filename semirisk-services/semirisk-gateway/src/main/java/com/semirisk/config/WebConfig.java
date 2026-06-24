package com.semirisk.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.semirisk.api.ApiResponse;
import com.semirisk.common.RolePermissionPolicy;
import com.semirisk.security.CsrfTokenService;
import com.semirisk.security.TokenAuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.io.IOException;
import java.util.Locale;

@org.springframework.context.annotation.Configuration
public class WebConfig implements WebMvcConfigurer {

    private static final String CSRF_HEADER = "X-CSRF-Token";
    private static final String[] SPA_ROUTES = {
            "/dashboard",
            "/login",
            "/register",
            "/upload",
            "/analysis",
            "/detail",
            "/report",
            "/alerts",
            "/gis",
            "/enterprise",
            "/knowledge",
            "/system"
    };

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
        for (String route : SPA_ROUTES) {
            registry.addViewController(route).setViewName("forward:/index.html");
        }
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOriginPatterns(
                        "http://localhost:*",
                        "http://127.0.0.1:*",
                        "http://123.57.239.56:*",
                        "http://semirisk.kaduoxli.com"
                )
                .allowedMethods("GET", "POST", "PUT", "DELETE", "HEAD", "OPTIONS")
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
                        if (uri.startsWith("/api/") && request.getParameter("access_token") != null) {
                            writeJson(response, HttpServletResponse.SC_BAD_REQUEST, ApiResponse.fail("access_token must not be sent in URL"));
                            return false;
                        }
                        if (uri.startsWith("/api/") && hasSuspiciousParameters(request)) {
                            writeJson(response, HttpServletResponse.SC_BAD_REQUEST, ApiResponse.fail("Invalid request parameters"));
                            return false;
                        }
                        if (uri.startsWith("/api/") && requiresCsrf(method) && !csrfValid(request)) {
                            writeJson(response, HttpServletResponse.SC_FORBIDDEN, ApiResponse.fail("CSRF Token invalid or missing"));
                            return false;
                        }
                        if (uri.startsWith("/api/auth/")) {
                            return true;
                        }
                        var principal = tokenAuthService.validate(request.getHeader("Authorization"));
                        if (uri.startsWith("/api/") && principal.isEmpty()) {
                            writeJson(response, HttpServletResponse.SC_UNAUTHORIZED, ApiResponse.fail("Please login first"));
                            return false;
                        }
                        TokenAuthService.AuthPrincipal value = principal.get();
                        String module = moduleFor(uri);
                        if (module != null && !RolePermissionPolicy.canAccess(value.role(), module)) {
                            writeJson(response, HttpServletResponse.SC_FORBIDDEN, ApiResponse.fail("Access denied for module: " + module));
                            return false;
                        }
                        request.setAttribute("authPrincipal", value);
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

    private boolean hasSuspiciousParameters(HttpServletRequest request) {
        for (var entry : request.getParameterMap().entrySet()) {
            if (isSuspicious(entry.getKey())) {
                return true;
            }
            for (String value : entry.getValue()) {
                if (isSuspicious(value)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean isSuspicious(String value) {
        if (value == null) {
            return false;
        }
        String lower = value.toLowerCase(Locale.ROOT);
        return lower.contains("$ne")
                || lower.contains("$gt")
                || lower.contains("$where")
                || lower.contains("{$")
                || lower.contains("<script")
                || lower.contains("javascript:");
    }

    private void writeJson(HttpServletResponse response, int status, ApiResponse<?> body) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(objectMapper.writeValueAsString(body));
    }

    private String moduleFor(String uri) {
        if (uri.startsWith("/api/data/")) return "upload";
        if (uri.startsWith("/api/risk/analysis")) return "analysis";
        if (uri.startsWith("/api/risk/events/")) return "detail";
        if (uri.startsWith("/api/reports/")) return "report";
        if (uri.startsWith("/api/alerts")) return "alerts";
        if (uri.startsWith("/api/gis/")) return "gis";
        if (uri.equals("/api/enterprises") || uri.startsWith("/api/enterprises/")) return "enterprise";
        if (uri.startsWith("/api/knowledge/")) return "knowledge";
        if (uri.startsWith("/api/system/")) return "system";
        if (uri.startsWith("/api/risk-score/")) return "dashboard";
        return null;
    }

}
