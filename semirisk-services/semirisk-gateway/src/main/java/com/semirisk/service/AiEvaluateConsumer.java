package com.semirisk.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * AI 评估消息队列消费者（Phase 2）。
 * 替代原有的本地线程池异步处理，防止应用重启导致任务丢失。
 */
@Service
public class AiEvaluateConsumer {

    private static final Logger log = LoggerFactory.getLogger(AiEvaluateConsumer.class);

    private final UploadAiEvaluateService evaluateService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public AiEvaluateConsumer(UploadAiEvaluateService evaluateService) {
        this.evaluateService = evaluateService;
    }

    /**
     * 监听 AI 评估队列。
     * 消息格式：{"taskId": "...", "objectKey": "...", "filename": "..."}
     */
    @RabbitListener(queues = com.semirisk.config.RabbitMqConfig.AI_EVAL_QUEUE)
    public void handleAiEvaluate(String message) {
        try {
            Map<String, String> payload = objectMapper.readValue(message, Map.class);
            String taskId = payload.get("taskId");
            String objectKey = payload.get("objectKey");
            String filename = payload.get("filename");

            log.info("Received AI evaluate message: taskId={}, file={}", taskId, filename);
            evaluateService.evaluateAsync(taskId, objectKey, filename);
        } catch (Exception e) {
            log.error("Failed to process AI evaluate message: {}", message, e);
        }
    }
}
