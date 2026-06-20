package com.semirisk.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Set;

/**
 * Idempotency filter — prevents duplicate submissions for POST/PUT requests.
 * Reads the Idempotency-Key header; if present, atomically SETs it in Redis
 * with NX + 5-minute TTL. Duplicate keys return HTTP 409 Conflict.
 */
public class IdempotencyFilter extends OncePerRequestFilter {

    private static final String HEADER_IDEMPOTENCY_KEY = "Idempotency-Key";
    private static final int TTL_SECONDS = 300;

    private static final Set<String> EXCLUDED_PATH_PREFIXES = Set.of(
            "/api/auth/login",
            "/api/auth/register",
            "/api/dashboard/",
            "/api/knowledge/preview/"
    );

    private final StringRedisTemplate redisTemplate;

    public IdempotencyFilter(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String method = request.getMethod();
        return !"POST".equalsIgnoreCase(method) && !"PUT".equalsIgnoreCase(method);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        // Skip excluded endpoints
        String path = request.getRequestURI();
        for (String prefix : EXCLUDED_PATH_PREFIXES) {
            if (path.startsWith(prefix)) {
                filterChain.doFilter(request, response);
                return;
            }
        }

        String idempotencyKey = request.getHeader(HEADER_IDEMPOTENCY_KEY);
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            filterChain.doFilter(request, response);
            return;
        }

        try {
            Boolean isNew = redisTemplate.opsForValue()
                    .setIfAbsent(idempotencyKey, "1", TTL_SECONDS, java.util.concurrent.TimeUnit.SECONDS);

            if (Boolean.FALSE.equals(isNew)) {
                // Key already exists — duplicate request
                response.setStatus(HttpServletResponse.SC_CONFLICT);
                response.setContentType("application/json;charset=UTF-8");
                response.getWriter().write("{\"code\":\"CONFLICT\",\"message\":\"重复请求，请勿重复提交\"}");
                return;
            }

            // New key — strip header and forward
            HttpServletRequestWrapper wrappedRequest = new HttpServletRequestWrapper(request) {
                @Override
                public String getHeader(String name) {
                    return HEADER_IDEMPOTENCY_KEY.equalsIgnoreCase(name) ? null : super.getHeader(name);
                }

                @Override
                public java.util.Enumeration<String> getHeaders(String name) {
                    return HEADER_IDEMPOTENCY_KEY.equalsIgnoreCase(name)
                            ? java.util.Collections.emptyEnumeration()
                            : super.getHeaders(name);
                }

                @Override
                public java.util.Enumeration<String> getHeaderNames() {
                    return super.getHeaderNames();
                }
            };

            filterChain.doFilter(wrappedRequest, response);

        } catch (Exception ex) {
            // Redis is down or error — fall through, skip idempotency check
            String msg = ex.getMessage() != null ? ex.getMessage() : "Unknown error";
            logger.warn("Idempotency check skipped due to Redis error: " + msg);
            filterChain.doFilter(request, response);
        }
    }
}
