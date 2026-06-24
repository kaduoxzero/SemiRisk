package com.semirisk.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import com.semirisk.config.ThreadPoolConfig;

import java.net.Socket;
import java.net.URI;
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

    private static final Logger log = LoggerFactory.getLogger(HealthProbeService.class);

    private final JdbcTemplate jdbcTemplate;
    private final String middlewareHost;
    private final String esUrl;
    private final String redisNodes;
    private final int minioApiPort;
    private final int rabbitHttpPort;
    private final int nacosPort;
    private final int zipkinPort;

    public HealthProbeService(
            JdbcTemplate jdbcTemplate,
            @Value("${semirisk.middleware.host:127.0.0.1}") String middlewareHost,
            @Value("${semirisk.elasticsearch.url:http://127.0.0.1:9200}") String esUrl,
            @Value("${semirisk.redis.cluster.nodes:127.0.0.1:6379}") String redisNodes,
            @Value("${semirisk.minio.api-port:9000}") int minioApiPort,
            @Value("${semirisk.rabbitmq.http-port:15672}") int rabbitHttpPort,
            @Value("${semirisk.nacos.port:8848}") int nacosPort,
            @Value("${semirisk.zipkin.port:9411}") int zipkinPort) {
        this.jdbcTemplate = jdbcTemplate;
        // 如果配置为空，尝试检测两个候选主机
        this.middlewareHost = resolveMiddlewareHost(middlewareHost);
        this.esUrl = esUrl;
        this.redisNodes = redisNodes;
        this.minioApiPort = minioApiPort;
        this.rabbitHttpPort = rabbitHttpPort;
        this.nacosPort = nacosPort;
        this.zipkinPort = zipkinPort;
    }

    private String resolveMiddlewareHost(String configuredHost) {
        if (configuredHost != null && !configuredHost.isBlank()) {
            return configuredHost;
        }
        // No fallback: SEMIRISK_MIDDLEWARE_HOST must be set explicitly
        throw new IllegalStateException("SEMIRISK_MIDDLEWARE_HOST is not set. Please configure it via environment variable.");
    }

    /** 探测全部中间件，返回真实健康清单。 */
    public List<Map<String, Object>> probeAll() {
        List<Map<String, Object>> sources = new ArrayList<>();
        sources.add(probe("MySQL", "数据库", middlewareHost + ":3306", this::probeMysql));
        sources.add(probe("Redis", "缓存/失败计数/限流", redisNodes, () -> probeRedisCluster()));
        sources.add(probe("Elasticsearch", "知识库检索", hostPort(esUrl), this::probeEs));
        sources.add(probe("MinIO", "对象存储", middlewareHost + ":" + minioApiPort, this::probeMinio));
        sources.add(probe("RabbitMQ", "消息队列", middlewareHost + ":" + rabbitHttpPort, () -> probeHttp("http://" + middlewareHost + ":" + rabbitHttpPort)));
        sources.add(probe("Nacos", "配置/注册中心", middlewareHost + ":" + nacosPort, () -> probeHttp("http://" + middlewareHost + ":" + nacosPort + "/nacos")));
        sources.add(probe("Zipkin", "Distributed tracing", middlewareHost + ":" + zipkinPort, () -> probeHttp("http://" + middlewareHost + ":" + zipkinPort + "/health")));
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
        String probeUrl = base.replaceAll("/+$", "") + "/chat/completions";
        long start = System.nanoTime();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("model", model);
        result.put("endpoint", base);
        try {
            // 先用 HTTP GET 探测基础连通性（端口是否在线）
            String detail = probeHttp(base);
            long latencyMs = Math.max(1, (System.nanoTime() - start) / 1_000_000);
            result.put("reachable", true);
            result.put("latencyMs", latencyMs);
            result.put("status", "可达");
            result.put("detail", "HTTP 连通测试通过 (" + detail + ")，API Key 需在模型配置中设置后验证");
        } catch (Exception ex) {
            result.put("reachable", false);
            result.put("latencyMs", 0);
            result.put("status", "不可达");
            result.put("detail", ex.getClass().getSimpleName() + ": " + ex.getMessage());
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
        HttpResponse<Void> response = ThreadPoolConfig.sharedHttpClient().send(request, HttpResponse.BodyHandlers.discarding());
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

    /** 探测 Redis 集群所有节点的健康状态。 */
    private String probeRedisCluster() throws Exception {
        String[] nodes = redisNodes.split(",");
        List<String> results = new ArrayList<>();
        int ok = 0;
        for (String node : nodes) {
            String[] parts = node.trim().split(":");
            String host = parts[0];
            int port = parts.length > 1 ? Integer.parseInt(parts[1]) : 6379;
            try (Socket s = new Socket()) {
                s.connect(new java.net.InetSocketAddress(host, port), 2000);
                results.add(host + ":" + port + "=ok");
                ok++;
            } catch (Exception e) {
                results.add(host + ":" + port + "=fail");
            }
        }
        return ok + "/" + nodes.length + " reachable (" + String.join("; ", results) + ")";
    }

    private String hostPort(String url) {
        try {
            URI uri = URI.create(url);
            int port = uri.getPort() == -1 ? 9200 : uri.getPort();
            return uri.getHost() + ":" + port;
        } catch (Exception ex) {
            log.debug("Failed to parse host:port from '{}': {}", url, ex.getMessage());
            return url;
        }
    }

    @FunctionalInterface
    private interface Probe {
        String run() throws Exception;
    }
}
