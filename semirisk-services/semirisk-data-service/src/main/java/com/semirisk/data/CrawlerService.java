package com.semirisk.data;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

@Service
public class CrawlerService {

    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(4)).build();
    private final CopyOnWriteArrayList<CrawlerRecord> dailyRecords = new CopyOnWriteArrayList<>();
    private final List<SourceSpec> sources;

    public CrawlerService(@Value("${semirisk.crawler.sources}") String sourceConfig) {
        this.sources = parseSources(sourceConfig);
    }

    @PostConstruct
    public void init() {
        refreshDailyRecords();
    }

    @Scheduled(cron = "0 0 0 * * *")
    public void refreshDailyRecords() {
        List<CrawlerRecord> records = new ArrayList<>();
        for (SourceSpec source : sources) {
            records.addAll(crawl(source));
        }
        if (records.isEmpty()) {
            records.add(failed(new SourceSpec("公开源", "about:blank", "供应链"), "未获得任何公开源条目，请检查网络或源配置"));
        }
        dailyRecords.clear();
        dailyRecords.addAll(records);
    }

    public List<CrawlerRecord> records() {
        return List.copyOf(dailyRecords);
    }

    private List<CrawlerRecord> crawl(SourceSpec source) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(source.url()))
                    .timeout(Duration.ofSeconds(6))
                    .header("User-Agent", "SemiRiskCrawler/1.0")
                    .header("Accept", "application/rss+xml, application/xml, text/xml, */*")
                    .GET()
                    .build();
            String xml = httpClient.send(request, HttpResponse.BodyHandlers.ofString()).body();
            List<FeedItem> items = parseFeed(xml);
            if (items.isEmpty()) {
                return List.of(failed(source, "公开源返回内容未解析到 RSS 条目"));
            }
            return items.stream()
                    .limit(4)
                    .map(item -> record(source, item))
                    .toList();
        } catch (Exception ex) {
            return List.of(failed(source, "公开源采集失败：" + ex.getClass().getSimpleName()));
        }
    }

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
            factory.setNamespaceAware(false);
            Document document = factory.newDocumentBuilder().parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
            NodeList nodes = document.getElementsByTagName("item");
            for (int i = 0; i < nodes.getLength(); i++) {
                Node node = nodes.item(i);
                if (node instanceof Element element) {
                    String title = childText(element, "title");
                    String link = childText(element, "link");
                    String published = childText(element, "pubDate");
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
            return Instant.now();
        }
    }

    private String childText(Element item, String tagName) {
        NodeList nodes = item.getElementsByTagName(tagName);
        if (nodes.getLength() == 0) {
            return "";
        }
        return nodes.item(0).getTextContent().replaceAll("\\s+", " ").trim();
    }

    private CrawlerRecord record(SourceSpec source, FeedItem item) {
        String title = item.title();
        String normalized = title.toLowerCase(Locale.ROOT);
        int score = 35;
        if (containsAny(normalized, "delay", "strike", "shortage", "disrupt", "congestion", "bankruptcy", "shutdown", "tariff")
                || title.contains("中断") || title.contains("拥堵") || title.contains("短缺") || title.contains("关税")) {
            score += 28;
        }
        if (containsAny(normalized, "price", "commodity", "freight", "export", "restriction", "regulation", "semiconductor", "chip")
                || title.contains("价格") || title.contains("出口") || title.contains("半导体")) {
            score += 18;
        }
        if (containsAny(normalized, "risk", "warning", "lawsuit", "recall", "sanction")) {
            score += 10;
        }
        String signal = score >= 75 ? "高危信号" : score >= 60 ? "中危信号" : "监控信号";
        String link = item.link().isBlank() ? source.url() : item.link();
        return new CrawlerRecord(stableId(link + title), source.name(), link, title, source.dimension(), signal, Math.min(score, 95), item.publishedAt(), "OK");
    }

    private CrawlerRecord failed(SourceSpec source, String message) {
        return new CrawlerRecord(stableId(source.url() + message), source.name(), source.url(), message, source.dimension(), "采集失败", 0, Instant.now(), "FAILED");
    }

    private boolean containsAny(String value, String... keywords) {
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

    private record SourceSpec(String name, String url, String dimension) {
    }

    private record FeedItem(String title, String link, Instant publishedAt) {
    }
}
