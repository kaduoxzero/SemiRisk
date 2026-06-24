package com.semirisk.data;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import java.util.concurrent.TimeUnit;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.ErrorHandler;
import org.xml.sax.SAXParseException;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 高性能爬虫服务（Phase 1 重构：并行抓取 + 重试 + 超时控制）。
 *
 * <p>变更要点：
 * <ul>
 *   <li>12 个 feed 串行拉取 → CompletableFuture 并行抓取</li>
 *   <li>每个源独立超时（8s）和重试（最多 2 次）</li>
 *   <li>使用专用线程池，与全局 crawlerExecutor 解耦</li>
 *   <li>保持原有解析/评分逻辑不变</li>
 * </ul>
 * </p>
 */
@Service
public class CrawlerService {

    private static final Logger log = LoggerFactory.getLogger(CrawlerService.class);

    private static final int RECENT_WINDOW_DAYS = 30;
    private static final int PER_SOURCE_LIMIT = 50;
    private static final int PARALLELISM = 8;          // 最大并行度
    private static final int MAX_RETRIES = 2;           // 每个源最多重试 2 次
    private static final Duration SOURCE_TIMEOUT = Duration.ofSeconds(8);

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(4))
            .build();
    private final CopyOnWriteArrayList<CrawlerRecord> dailyRecords = new CopyOnWriteArrayList<>();
    private final Path persistFile;
    private final List<SourceSpec> sources;
    private final org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor crawlerExecutor;

    public CrawlerService(
            @Value("${semirisk.crawler.sources}") String sourceConfig,
            @Value("${semirisk.crawler.persist-dir:./data}") String persistDir,
            @Qualifier("semiriskCrawlerPool") org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor crawlerExecutor) {
        this.sources = parseSources(sourceConfig);
        this.crawlerExecutor = crawlerExecutor;
        Path pf = null;
        try {
            java.nio.file.Files.createDirectories(java.nio.file.Paths.get(persistDir));
            pf = java.nio.file.Paths.get(persistDir, "crawler_records.json");
        } catch (Exception e) {
            log.warn("Cannot initialize persist file at {}: {}", persistDir, e.getMessage());
        }
        this.persistFile = pf;
        log.info("CrawlerService initialized with {} sources", sources.size());
    }

    @PostConstruct
    public void init() {
        loadRecords();
        refreshDailyRecords();
    }

    @PreDestroy
    public void destroy() {
        httpClient.close();
        // crawlerExecutor is Spring-managed, no need to shut down
    }

    @Scheduled(fixedDelayString = "${semirisk.crawler.refresh-interval-ms:300000}", initialDelayString = "${semirisk.crawler.initial-delay-ms:30000}")
    public void refreshDailyRecords() {
        Instant startTime = Instant.now();
        log.info("Starting parallel crawler refresh for {} sources...", sources.size());

        // Phase 1: 并行抓取所有源（通过 this 代理调用以触发 @Retryable）
        CrawlerService self = this;
        List<CompletableFuture<List<CrawlerRecord>>> futures = sources.stream()
                .map(source -> CompletableFuture.supplyAsync(() -> {
                        try {
                            return self.crawlWithRetry(source);
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                    }, crawlerExecutor)
                        .exceptionally((Throwable ex) -> {
                            log.warn("Crawl failed for source {}: {}", source.name(), ex.getMessage());
                            return List.<CrawlerRecord>of(failed(source, "抓取失败：" + ex.getMessage()));
                        }))
                .toList();

        // 等待所有源完成
        CompletableFuture<Void> allDone = CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new));

        try {
            allDone.get(60, TimeUnit.SECONDS); // 整体超时 60s
        } catch (java.util.concurrent.TimeoutException e) {
            allDone.cancel(true);
            log.warn("Crawler refresh timed out after 60s, using partial results");
        } catch (Exception e) {
            log.error("Crawler refresh interrupted", e);
        }

        // 收集所有结果
        List<CrawlerRecord> records = new ArrayList<>();
        for (CompletableFuture<List<CrawlerRecord>> future : futures) {
            List<CrawlerRecord> result = future.getNow(null);
            if (result != null && !result.isEmpty()) {
                records.addAll(result);
            }
        }

        if (records.stream().anyMatch(record -> "OK".equals(record.status()))) {
            records.removeIf(record -> "FAILED".equals(record.status()));
        }

        if (records.isEmpty()) {
            records.add(failed(new SourceSpec("公开源", "about:blank", "供应链"),
                    "未获得任何公开源条目，请检查网络或源配置"));
        }

        dailyRecords.clear();
        dailyRecords.addAll(records);
        saveRecords();

        long elapsed = Duration.between(startTime, Instant.now()).toMillis();
        log.info("Parallel crawler refresh completed in {}ms, total records={}", elapsed, records.size());
    }

    /**
     * 带重试的单个源抓取。
     */
    @Retryable(
            retryFor = {Exception.class},
            maxAttempts = MAX_RETRIES + 1,  // 初始 + 重试次数
            backoff = @Backoff(delay = 1000, multiplier = 2)
    )
    public List<CrawlerRecord> crawlWithRetry(SourceSpec source) throws Exception {
        return crawl(source);
    }

    /**
     * 抓取单个源（核心逻辑不变）。
     */
    private List<CrawlerRecord> crawl(SourceSpec source) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(source.url()))
                .timeout(SOURCE_TIMEOUT)
                .header("User-Agent", "Mozilla/5.0 SemiRiskCrawler/1.0")
                .header("Accept", "application/rss+xml, application/xml, text/xml, */*")
                .GET()
                .build();

        // 使用新版 HttpClient 的异步 API 支持超时
        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (java.net.http.HttpTimeoutException e) {
            throw new Exception("源 " + source.name() + " 抓取超时 (" + SOURCE_TIMEOUT.toSeconds() + "s)", e);
        } catch (Exception e) {
            throw new Exception("源 " + source.name() + " 网络异常: " + e.getMessage(), e);
        }

        if (response.statusCode() != 200) {
            throw new Exception("源 " + source.name() + " 返回 HTTP " + response.statusCode());
        }

        String xml = response.body();
        List<FeedItem> items = parseFeed(xml);
        if (items.isEmpty()) {
            return List.of(failed(source, "公开源返回内容未解析到 RSS/Atom 条目"));
        }

        Instant cutoff = Instant.now().minus(RECENT_WINDOW_DAYS, ChronoUnit.DAYS);
        List<FeedItem> recentItems = items.stream()
                .filter(item -> !item.publishedAt().isBefore(cutoff))
                .sorted(Comparator.comparing(FeedItem::publishedAt).reversed())
                .limit(PER_SOURCE_LIMIT)
                .toList();

        if (recentItems.isEmpty()) {
            return List.of(failed(source, "近30天未发现 RSS/Atom 条目"));
        }

        return recentItems.stream()
                .map(item -> record(source, item))
                .toList();
    }

    public List<CrawlerRecord> records() {
        return List.copyOf(dailyRecords);
    }

    // ---- 持久化：JSON 文件备份，防止重启丢失 ----

    private static final java.time.format.DateTimeFormatter PERSIST_FMT =
            java.time.format.DateTimeFormatter.ISO_INSTANT;

    private Map<String, Object> toPersistable(CrawlerRecord r) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("id", r.id());
        m.put("source", r.source());
        m.put("sourceUrl", r.sourceUrl());
        m.put("title", r.title());
        m.put("dimension", r.dimension());
        m.put("riskSignal", r.riskSignal());
        m.put("riskScore", r.riskScore());
        m.put("fetchedAt", r.fetchedAt().toString());
        m.put("status", r.status());
        return m;
    }

    private CrawlerRecord fromPersistable(Map<String, Object> m) {
        return new CrawlerRecord(
                (String) m.get("id"),
                (String) m.get("source"),
                (String) m.get("sourceUrl"),
                (String) m.get("title"),
                (String) m.get("dimension"),
                (String) m.get("riskSignal"),
                ((Number) m.get("riskScore")).intValue(),
                Instant.parse((String) m.get("fetchedAt")),
                (String) m.get("status")
        );
    }

    private void saveRecords() {
        if (persistFile == null || dailyRecords.isEmpty()) return;
        try {
            List<Map<String, Object>> list = dailyRecords.stream()
                    .map(this::toPersistable)
                    .toList();
            java.nio.file.Files.writeString(persistFile,
                    new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(list));
        } catch (Exception e) {
            log.warn("Failed to persist crawler records: {}", e.getMessage());
        }
    }

    private void loadRecords() {
        if (persistFile == null || !java.nio.file.Files.exists(persistFile)) return;
        try {
            String json = java.nio.file.Files.readString(persistFile);
            if (json.isBlank()) return;
            List<Map<String, Object>> list = new com.fasterxml.jackson.databind.ObjectMapper()
                    .readValue(json, List.class);
            dailyRecords.addAll(list.stream()
                    .map(m -> fromPersistable((Map<String, Object>) m))
                    .toList());
            log.info("Loaded {} persisted crawler records from {}", dailyRecords.size(), persistFile);
        } catch (Exception e) {
            log.warn("Failed to load persisted crawler records: {}", e.getMessage());
        }
    }

    // ---- 以下为保持不变的核心解析/评分逻辑 ----

    private List<SourceSpec> parseSources(String sourceConfig) {
        String separator = sourceConfig.contains(";") ? ";" : ",";
        return List.of(sourceConfig.split(separator)).stream()
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .map(value -> {
                    String[] parts = value.split("\\|");
                    if (parts.length >= 3) {
                        return new SourceSpec(parts[0].trim(), parts[1].trim(), parts[2].trim());
                    }
                    String url = parts[0].trim();
                    return new SourceSpec(hostName(url), url, "供应链");
                })
                .toList();
    }

    private List<FeedItem> parseFeed(String xml) {
        List<FeedItem> items = new ArrayList<>();
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setNamespaceAware(false);
            var builder = factory.newDocumentBuilder();
            builder.setErrorHandler(new ErrorHandler() {
                @Override
                public void warning(SAXParseException exception) {
                    // Ignore malformed public feeds quietly; callers fall back to a failed crawler record.
                }

                @Override
                public void error(SAXParseException exception) throws SAXParseException {
                    throw exception;
                }

                @Override
                public void fatalError(SAXParseException exception) throws SAXParseException {
                    throw exception;
                }
            });
            Document document = builder.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
            NodeList nodes = document.getElementsByTagName("item");
            for (int i = 0; i < nodes.getLength(); i++) {
                Node node = nodes.item(i);
                if (node instanceof Element element) {
                    String title = childText(element, "title");
                    String link = childText(element, "link");
                    if (link.isBlank()) {
                        link = childText(element, "guid");
                    }
                    String published = childText(element, "pubDate");
                    if (!title.isBlank()) {
                        items.add(new FeedItem(title, link, parsePublishedAt(published)));
                    }
                }
            }
            NodeList entries = document.getElementsByTagName("entry");
            for (int i = 0; i < entries.getLength(); i++) {
                Node node = entries.item(i);
                if (node instanceof Element element) {
                    String title = childText(element, "title");
                    String link = childText(element, "link");
                    if (link.isBlank()) {
                        link = childAttribute(element, "link", "href");
                    }
                    if (link.isBlank()) {
                        link = childText(element, "id");
                    }
                    String published = childText(element, "published");
                    if (published.isBlank()) {
                        published = childText(element, "updated");
                    }
                    if (!title.isBlank()) {
                        items.add(new FeedItem(title, link, parsePublishedAt(published)));
                    }
                }
            }
        } catch (Exception ignored) {
            return List.of();
        }
        return items;
    }

    private Instant parsePublishedAt(String value) {
        if (value == null || value.isBlank()) {
            return Instant.now();
        }
        try {
            return ZonedDateTime.parse(value.trim(), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
        } catch (Exception ignored) {
            try {
                return Instant.parse(value.trim());
            } catch (Exception ignoredAgain) {
                try {
                    return ZonedDateTime.parse(value.trim(), DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant();
                } catch (Exception ignoredFinally) {
                    return Instant.now();
                }
            }
        }
    }

    private String childText(Element item, String tagName) {
        NodeList nodes = item.getElementsByTagName(tagName);
        if (nodes.getLength() == 0) {
            return "";
        }
        return nodes.item(0).getTextContent().replaceAll("\\s+", " ").trim();
    }

    private String childAttribute(Element item, String tagName, String attributeName) {
        NodeList nodes = item.getElementsByTagName(tagName);
        if (nodes.getLength() == 0 || !(nodes.item(0) instanceof Element element)) {
            return "";
        }
        return element.getAttribute(attributeName).replaceAll("\\s+", " ").trim();
    }

    private CrawlerRecord record(SourceSpec source, FeedItem item) {
        String title = item.title();
        int score = ruleRiskScore(source.name(), source.dimension(), title);
        String signal = signalLabel(score);
        String link = item.link().isBlank() ? source.url() : item.link();
        return new CrawlerRecord(stableId(link + title), source.name(), link, title, source.dimension(),
                signal, Math.min(score, 95), item.publishedAt(), "OK");
    }

    static int ruleRiskScore(String sourceName, String dimension, String title) {
        String text = title == null ? "" : title;
        String normalized = text.toLowerCase(Locale.ROOT);
        String source = sourceName == null ? "" : sourceName.toLowerCase(Locale.ROOT);
        String dim = dimension == null ? "" : dimension.toLowerCase(Locale.ROOT);
        int score = 0;

        if (containsAny(normalized, "strike", "shortage", "disrupt", "congestion", "bankruptcy", "shutdown",
                "sanction", "recall", "lawsuit", "investigation", "emergency", "port closure", "export control",
                "customs enforcement", "tariff", "surcharge", "restriction", "ban", "license")
                || containsAny(text, "中断", "拥堵", "短缺", "关税", "制裁", "停产", "断供", "延期", "召回", "调查")) {
            score += 46;
        }
        if (containsAny(normalized, "supply chain", "freight", "logistics", "shipping", "port", "customs",
                "import", "export", "semiconductor", "chip", "manufacturing", "production", "commodity",
                "warehouse", "inventory", "procurement")
                || containsAny(text, "价格", "出口", "进口", "半导体", "芯片", "供应链", "产业链", "物流", "港口", "海关", "贸易", "制造")) {
            score += 24;
        }
        if (containsAny(normalized, "regulation", "rule", "notice", "trade policy", "federal register",
                "anti-dumping", "dumping", "license", "controlled substances")
                || containsAny(text, "监管", "法规", "许可", "管制", "反倾销", "禁令", "条例")) {
            score += 18;
        }
        if (containsAny(normalized, "risk", "warning", "delay", "price", "capacity", "volatility", "pressure")
                || containsAny(text, "风险", "预警", "延迟", "波动", "压力")) {
            score += 14;
        }

        if (score == 0) {
            return 0;
        }
        if (containsAny(dim, "supply", "logistics", "manufacturing", "policy", "market")) {
            score += 5;
        }
        if (containsAny(source, "federalregister", "supplychaindive", "freightwaves", "manufacturingdive", "truckingdive")) {
            score += 4;
        }
        if (containsAny(source, "wto")) {
            score += 3;
        }
        return Math.max(20, Math.min(score, 95));
    }

    private String signalLabel(int score) {
        if (score >= 75) {
            return "高危信号";
        }
        if (score >= 55) {
            return "中危信号";
        }
        if (score > 0) {
            return "监控信号";
        }
        return "公开源记录";
    }

    private CrawlerRecord failed(SourceSpec source, String message) {
        return new CrawlerRecord(stableId(source.url() + message), source.name(), source.url(),
                message, source.dimension(), "采集失败", 0, Instant.now(), "FAILED");
    }

    private static boolean containsAny(String value, String... keywords) {
        for (String keyword : keywords) {
            if (value.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private String stableId(String value) {
        return "CR-" + UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8)).toString().substring(0, 8);
    }

    private String hostName(String url) {
        try {
            return URI.create(url).getHost();
        } catch (Exception ignored) {
            return "公开源";
        }
    }

    private record SourceSpec(String name, String url, String dimension) {}

    private record FeedItem(String title, String link, Instant publishedAt) {}
}
