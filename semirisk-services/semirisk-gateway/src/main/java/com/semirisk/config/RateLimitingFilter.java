package com.semirisk.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Sliding-window rate limiter backed by Redis Sorted Sets.
 * <p>
 * Applies only to {@code /api/auth/register} and
 * {@code /api/auth/send-verification-code}.  Uses {@code ZADD} +
 * {@code ZREMRANGEBYSCORE} + {@code ZCARD} to count requests
 * within a rolling time window.
 * </p>
 *
 * <table>
 *   <tr><th>Endpoint</th><th>Window</th><th>Max requests</th></tr>
 *   <tr><td>/api/auth/register</td><td>60 s</td><td>2</td></tr>
 *   <tr><td>/api/auth/send-verification-code</td><td>60 s</td><td>1</td></tr>
 * </table>
 */
public class RateLimitingFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitingFilter.class);

    private static final String X_FORWARDED_FOR = "X-Forwarded-For";

    /** Endpoints governed by this filter. */
    private static final Set<String> TARGET_PATHS = Set.of(
            "/api/auth/register",
            "/api/auth/send-verification-code"
    );

    /** Sliding-window size in seconds. */
    private static final int WINDOW_SECONDS = 60;

    /** Max requests per window per endpoint + IP. */
    private static final int REGISTER_MAX = 2;
    private static final int VERIFICATION_CODE_MAX = 1;

    private final StringRedisTemplate redisTemplate;

    public RateLimitingFilter(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !TARGET_PATHS.contains(path);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        String ip = getClientIp(request);
        String path = request.getRequestURI();

        // Pick the correct max based on the endpoint
        int maxRequests = path.equals("/api/auth/register") ? REGISTER_MAX : VERIFICATION_CODE_MAX;

        String key = "semirisk:ratelimit:" + path + ":" + ip;
        long now = System.currentTimeMillis();
        long windowStart = now - WINDOW_SECONDS * 1000L;

        try {
            // 1. Remove entries outside the sliding window
            redisTemplate.opsForZSet().removeRangeByScore(key, windowStart, now);

            // 2. Add current request with timestamp as score
            redisTemplate.opsForZSet().add(key, String.valueOf(now), now);

            // 3. Count entries in the window
            Long count = redisTemplate.opsForZSet().zCard(key);

            if (count != null && count > maxRequests) {
                // Rate limit exceeded
                response.setStatus(429);
                response.setContentType("application/json;charset=UTF-8");
                response.getWriter().write(
                        "{\"code\":\"RATE_LIMITED\",\"message\":\"请求过于频繁，请稍后再试\"}"
                );
                return;
            }

            // 4. Set TTL on the key so Redis cleans up abandoned entries
            redisTemplate.expire(key, WINDOW_SECONDS + 1, TimeUnit.SECONDS);

            filterChain.doFilter(request, response);

        } catch (Exception ex) {
            // Redis is down or error — fail open, skip rate limiting
            log.warn("Rate-limit check skipped due to Redis error: {}", ex.getMessage());
            filterChain.doFilter(request, response);
        }
    }

    /**
     * Extract the real client IP, checking X-Forwarded-For first.
     */
    private String getClientIp(HttpServletRequest request) {
        String forwarded = request.getHeader(X_FORWARDED_FOR);
        if (forwarded != null && !forwarded.isEmpty() && !"unknown".equalsIgnoreCase(forwarded)) {
            // X-Forwarded-For can contain multiple IPs; take the first (client) one
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
