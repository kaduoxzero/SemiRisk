package com.semirisk.data;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class CrawlerService {

    private static final Pattern TITLE_PATTERN = Pattern.compile("<title>(.*?)</title>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(4)).build();
    private final CopyOnWriteArrayList<CrawlerRecord> dailyRecords = new CopyOnWriteArrayList<>();
    private final List<String> sources;

    public CrawlerService(@Value("${semirisk.crawler.sources}") String sourceConfig) {
        this.sources = List.of(sourceConfig.split(","));
    }

    @PostConstruct
    public void init() {
        refreshDailyRecords();
    }

    @Scheduled(cron = "0 0 0 * * *")
    public void refreshDailyRecords() {
        List<CrawlerRecord> records = new ArrayList<>();
        for (String source : sources) {
            records.add(crawl(source.trim()));
        }
        if (records.isEmpty()) {
            records.add(fallback("internal://fallback", "半导体物流与原材料价格出现复合波动"));
        }
        dailyRecords.clear();
        dailyRecords.addAll(records);
    }

    public List<CrawlerRecord> records() {
        return List.copyOf(dailyRecords);
    }

    private CrawlerRecord crawl(String source) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(source))
                    .timeout(Duration.ofSeconds(6))
                    .header("User-Agent", "SemiRiskCrawler/1.0")
                    .GET()
                    .build();
            String html = httpClient.send(request, HttpResponse.BodyHandlers.ofString()).body();
            String title = extractTitle(html);
            return record(source, title);
        } catch (Exception ex) {
            return fallback(source, "外部情报源暂不可达，使用本地风险基线生成记录");
        }
    }

    private CrawlerRecord fallback(String source, String title) {
        return record(source, title);
    }

    private CrawlerRecord record(String source, String title) {
        String normalized = title.toLowerCase(Locale.ROOT);
        int score = 42;
        if (normalized.contains("delay") || normalized.contains("strike") || title.contains("中断") || title.contains("拥堵")) {
            score += 28;
        }
        if (normalized.contains("price") || normalized.contains("commodity") || title.contains("价格")) {
            score += 18;
        }
        String signal = score >= 75 ? "高危信号" : score >= 60 ? "中危信号" : "监控信号";
        return new CrawlerRecord("CR-" + UUID.randomUUID().toString().substring(0, 8), source, title, signal, Math.min(score, 95), Instant.now());
    }

    private String extractTitle(String html) {
        Matcher matcher = TITLE_PATTERN.matcher(html);
        if (!matcher.find()) {
            return "未识别标题的供应链情报页面";
        }
        return matcher.group(1).replaceAll("\\s+", " ").trim();
    }
}

