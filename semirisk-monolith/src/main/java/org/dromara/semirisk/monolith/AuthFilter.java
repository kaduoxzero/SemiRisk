package org.dromara.semirisk.monolith;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

@Component
public class AuthFilter extends OncePerRequestFilter {
    private final AuthService authService;

    public AuthFilter(AuthService authService) {
        this.authService = authService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
        throws ServletException, IOException {
        String path = request.getRequestURI();
        if (!path.startsWith("/prod-api/risk/") && !path.startsWith("/prod-api/auth/me")) {
            filterChain.doFilter(request, response);
            return;
        }
        try {
            AuthUser user = authService.validate(bearerToken(request));
            if (isAdminOnly(path)) {
                authService.requireAdmin(user);
            }
            request.setAttribute("authUser", user);
            filterChain.doFilter(request, response);
        } catch (ResponseStatusException ex) {
            response.setStatus(ex.getStatusCode().value());
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"code\":" + ex.getStatusCode().value() + ",\"msg\":\"" + ex.getReason() + "\"}");
        }
    }

    private static String bearerToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            return null;
        }
        return header.substring("Bearer ".length()).trim();
    }

    private static boolean isAdminOnly(String path) {
        return path.startsWith("/prod-api/risk/crawler/")
            || path.startsWith("/prod-api/risk/source/");
    }
}
