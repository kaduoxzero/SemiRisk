package com.semirisk.data;

import com.semirisk.api.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/crawler")
public class DataController {

    private final CrawlerService crawlerService;

    public DataController(CrawlerService crawlerService) {
        this.crawlerService = crawlerService;
    }

    @GetMapping("/records/today")
    public ApiResponse<List<CrawlerRecord>> today() {
        return ApiResponse.ok(crawlerService.records());
    }

    @PostMapping("/refresh")
    public ApiResponse<List<CrawlerRecord>> refresh() {
        crawlerService.refreshDailyRecords();
        return ApiResponse.ok("爬虫刷新完成", crawlerService.records());
    }
}

