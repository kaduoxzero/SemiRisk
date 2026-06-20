package com.semirisk.service;

import com.semirisk.model.CrawlerSignal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 知识库搜索索引服务（重构版：使用 ElasticSearchBulkWriter 批量写入）。
 *
 * <p>变更：
 * <ul>
 *   <li>单条 PUT → Bulk API 批量写入，吞吐提升 10-50 倍</li>
 *   <li>定时/定量自动刷写，减少 ES 连接占用</li>
 *   <li>保留原有 search 接口</li>
 * </ul>
 * </p>
 */
@Service
public class KnowledgeSearchIndexService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeSearchIndexService.class);

    private final ElasticSearchBulkWriter bulkWriter;

    public KnowledgeSearchIndexService(ElasticSearchBulkWriter bulkWriter) {
        this.bulkWriter = bulkWriter;
    }

    /**
     * 同步爬虫信号到 ES（批量写入）。
     */
    public void sync(List<CrawlerSignal> signals) {
        if (signals == null || signals.isEmpty()) {
            return;
        }
        List<Map<String, Object>> documents = new ArrayList<>();
        for (CrawlerSignal signal : signals) {
            if (!"OK".equalsIgnoreCase(signal.status())) {
                continue;
            }
            documents.add(buildSignalDocument(signal));
        }
        if (documents.isEmpty()) {
            return;
        }
        // 提交到批量缓冲区，由 ElasticSearchBulkWriter 自动刷写
        bulkWriter.submitBatch(documents);
        log.debug("Submitted {} crawler signals to ES bulk writer, buffer size={}",
                documents.size(), bulkWriter.getBufferSize());
    }

    /**
     * 索引上传的 AI 评估文档（兼容旧接口）。
     */
    public void indexUploadedDoc(String docId, String title, String content, String dimension, int riskScore, String objectKey) {
        Map<String, Object> doc = Map.of(
                "id", docId,
                "title", title,
                "content", content,
                "source", "用户上传",
                "sourceUrl", objectKey,
                "dimension", dimension,
                "riskScore", riskScore,
                "fetchedAt", Instant.now().toString()
        );
        bulkWriter.submit(docId, doc);
    }

    /**
     * 搜索（委托给 bulkWriter 的搜索接口）。
     */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> search(String query, int size) {
        return bulkWriter.search(query, size);
    }

    private Map<String, Object> buildSignalDocument(CrawlerSignal signal) {
        return Map.of(
                "id", signal.id(),
                "title", signal.title(),
                "content", signal.title() + " " + signal.source() + " " + signal.dimension(),
                "source", signal.source(),
                "sourceUrl", signal.sourceUrl(),
                "dimension", signal.dimension(),
                "riskScore", signal.riskScore(),
                "fetchedAt", signal.fetchedAt().toString()
        );
    }
}
