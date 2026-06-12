package com.semirisk.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 中间件真实健康探测服务。
 *
 * <p>对 MySQL / Redis / Elasticsearch / MinIO / RabbitMQ / Nacos 发起真实连通性探测，
 * 返回真实可达状态与往返延迟，供系统监控页面与 {@code /api/system/datasources/{name}/reconnect} 使用。
 * 不再使用任何写死的健康状态或延迟。</p>
 */
@Service
public class HealthProbeService {

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();
    private final JdbcTemplate jdbcTemplate;
    private final String middlewareHost;
    private final String esUrl;
    private final int redisPort;
    private final int minioApiPort;
    private final int rabbitHttpPort;
    private final int nacosPort;

    public HealthProbeService(
            JdbcTemplate jdbcTemplate,
            @Value("${semirisk.middleware.host:}") String middlewareHost,
            @Value("${semirisk.elasticsearch.url:http://127.0.0.1:9200}") String esUrl,
            @Value("${spring.data.redis.port:6379}") int redisPort,
            @Value("${semirisk.minio.api-port:9000}") int minioApiPort,
            @Value("${semirisk.rabbitmq.http-port:15672}") int rabbitHttpPort,
            @Value("${semirisk.nacos.port:8848}") int nacosPort) {
        this.jdbcTemplate = jdbcTemplate;
        // 如果配置为空，尝试检测两个候选主机
        this.middlewareHost = resolveMiddlewareHost(middlewareHost);
        this.esUrl = esUrl;
        this.redisPort = redisPort;
        this.minioApiPort = minioApiPort;
        this.rabbitHttpPort = rabbitHttpPort;
        this.nacosPort = nacosPort;
    }

    private String resolveMiddlewareHost(String configuredHost) {
        if (configuredHost != null && !configuredHost.isBlank()) {
            return configuredHost;
        }
        if (tryConnect("127.0.0.1", 3306, 2000)) return "127.0.0.1";
        return "192.168.101.130";
    }

    private boolean tryConnect(String host, int port, int timeoutMs) {
        try (java.net.Socket s = new java.net.Socket()) {
            s.connect(new java.net.InetSocketAddress(host, port), timeoutMs);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** 探测全部中间件，返回真实健康清单。 */
    public List<Map<String, Object>> probeAll() {
        List<Map<String, Object>> sources = new ArrayList<>();
        sources.add(probe("MySQL", "数据库", middlewareHost + ":3306", this::probeMysql));
        sources.add(probe("Redis", "缓存/失败计数", middlewareHost + ":" + redisPort, () -> probeTcp(redisPort)));
        sources.add(probe("Elasticsearch", "知识库检索", hostPort(esUrl), this::probeEs));
        sources.add(probe("MinIO", "对象存储", middlewareHost + ":" + minioApiPort, this::probeMinio));
        sources.add(probe("RabbitMQ", "消息队列", middlewareHost + ":" + rabbitHttpPort, () -> probeHttp("http://" + middlewareHost + ":" + rabbitHttpPort)));
        sources.add(probe("Nacos", "配置/注册中心", middlewareHost + ":" + nacosPort, () -> probeHttp("http://" + middlewareHost + ":" + nacosPort + "/nacos")));
        return sources;
    }

    /** 探测单个数据源（按名称匹配），供 reconnect 使用。 */
    public Map<String, Object> probeOne(String name) {
        String key = name == null ? "" : name.trim().toLowerCase();
        return probeAll().stream()
                .filter(item -> String.valueOf(item.get("name")).toLowerCase().contains(key) || key.isBlank())
                .findFirst()
                .orElseGet(() -> Map.of("name", name, "status", "未知数据源", "reachable", false, "host", middlewareHost));
    }

    /** 对 AI 模型 endpoint 发起真实连通性探测，返回真实可达状态与延迟。 */
    public Map<String, Object> probeModelEndpoint(String model, String endpoint) {
        String base = endpoint == null || endpoint.isBlank() ? "https://api.deepseek.com/v1" : endpoint.trim();
        String probeUrl = base.replaceAll("/+$", "");
        long start = System.nanoTime();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("model", model);
        result.put("endpoint", base);
        try {
            String detail = probeHttp(probeUrl);
            long latencyMs = Math.max(1, (System.nanoTime() - start) / 1_000_000);
            result.put("reachable", true);
            result.put("latencyMs", latencyMs);
            result.put("status", "可达");
            result.put("detail", detail);
        } catch (Exception ex) {
            result.put("reachable", false);
            result.put("latencyMs", 0);
            result.put("status", "不可达");
            result.put("detail", ex.getClass().getSimpleName());
        }
        return result;
    }

    private Map<String, Object> probe(String name, String role, String host, Probe probe) {
        long start = System.nanoTime();
        boolean reachable;
        String detail;
        try {
            detail = probe.run();
            reachable = detail != null;
        } catch (Exception ex) {
            reachable = false;
            // 提供更多信息，便于诊断
            detail = ex.getClass().getSimpleName() + ": " + ex.getMessage();
        }
        long latencyMs = Math.max(1, (System.nanoTime() - start) / 1_000_000);
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("name", name);
        map.put("role", role);
        map.put("host", host);
        map.put("reachable", reachable);
        map.put("status", reachable ? "健康" : "不可达");
        map.put("latencyMs", reachable ? latencyMs : 0);
        map.put("detail", detail == null ? "连接失败 (host=" + host + ")" : detail);
        return map;
    }

    private String probeMysql() {
        Number value = jdbcTemplate.queryForObject("SELECT 1", Number.class);
        return "SELECT 1 -> " + (value != null ? value : "?");
    }

    private String probeEs() throws Exception {
        return probeHttp(esUrl);
    }

    private String probeMinio() throws Exception {
        // MinIO 提供 /minio/health/live 健康端点。
        return probeHttp("http://" + middlewareHost + ":" + minioApiPort + "/minio/health/live");
    }

    private String probeHttp(String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(3))
                .GET()
                .build();
        HttpResponse<Void> response = httpClient.send(request, HttpResponse.BodyHandlers.discarding());
        int code = response.statusCode();
        // 任意 HTTP 响应（含 401/403/404）都说明端口在线、服务可达。
        return "HTTP " + code;
    }

    private String probeTcp(int port) throws Exception {
        try (Socket socket = new Socket()) {
            socket.connect(new java.net.InetSocketAddress(middlewareHost, port), 3000);
            return "TCP connect ok";
        }
    }

    private String hostPort(String url) {
        try {
            URI uri = URI.create(url);
            int port = uri.getPort() == -1 ? 9200 : uri.getPort();
            return uri.getHost() + ":" + port;
        } catch (Exception ignored) {
            return url;
        }
    }

    @FunctionalInterface
    private interface Probe {
        String run() throws Exception;
    }
}
