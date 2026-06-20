package com.semirisk.config;

import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;

/**
 * RabbitMQ 消息队列配置（Phase 2）。
 *
 * <p>队列设计：
 * <ul>
 *   <li>ai.evaluate.queue — 上传文件 AI 评估队列</li>
 *   <li>report.generate.queue — 报告生成队列</li>
 *   <li>crawler.sync.queue — 爬虫数据同步队列（gateway ↔ data-service）</li>
 * </ul>
 * </p>
 */
@Configuration
@ConditionalOnProperty(name = "spring.rabbitmq.host", havingValue = "*", matchIfMissing = false)
public class RabbitMqConfig {

    public static final String AI_EVAL_QUEUE = "ai.evaluate.queue";
    public static final String AI_EVAL_EXCHANGE = "ai.evaluate.exchange";
    public static final String AI_EVAL_ROUTING_KEY = "ai.evaluate";

    public static final String REPORT_QUEUE = "report.generate.queue";
    public static final String REPORT_EXCHANGE = "report.exchange";
    public static final String REPORT_ROUTING_KEY = "report.generate";

    public static final String CRAWLER_SYNC_QUEUE = "crawler.sync.queue";
    public static final String CRAWLER_SYNC_EXCHANGE = "crawler.sync.exchange";
    public static final String CRAWLER_SYNC_ROUTING_KEY = "crawler.sync";

    @Bean
    public Jackson2JsonMessageConverter messageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    // ===== AI 评估队列 =====

    @Bean
    public Queue aiEvaluateQueue() {
        return QueueBuilder.durable(AI_EVAL_QUEUE)
                .withArgument("x-dead-letter-exchange", "")
                .withArgument("x-dead-letter-routing-key", "ai.evaluate.dlq")
                .build();
    }

    @Bean
    public DirectExchange aiEvaluateExchange() {
        return new DirectExchange(AI_EVAL_EXCHANGE);
    }

    @Bean
    public Binding aiEvaluateBinding() {
        return BindingBuilder.bind(aiEvaluateQueue())
                .to(aiEvaluateExchange())
                .with(AI_EVAL_ROUTING_KEY);
    }

    // ===== 报告生成队列 =====

    @Bean
    public Queue reportGenerateQueue() {
        return QueueBuilder.durable(REPORT_QUEUE)
                .withArgument("x-dead-letter-exchange", "")
                .withArgument("x-dead-letter-routing-key", "report.generate.dlq")
                .build();
    }

    @Bean
    public DirectExchange reportExchange() {
        return new DirectExchange(REPORT_EXCHANGE);
    }

    @Bean
    public Binding reportBinding() {
        return BindingBuilder.bind(reportGenerateQueue())
                .to(reportExchange())
                .with(REPORT_ROUTING_KEY);
    }

    // ===== 爬虫同步队列 =====

    @Bean
    public Queue crawlerSyncQueue() {
        return QueueBuilder.durable(CRAWLER_SYNC_QUEUE).build();
    }

    @Bean
    public DirectExchange crawlerSyncExchange() {
        return new DirectExchange(CRAWLER_SYNC_EXCHANGE);
    }

    @Bean
    public Binding crawlerSyncBinding() {
        return BindingBuilder.bind(crawlerSyncQueue())
                .to(crawlerSyncExchange())
                .with(CRAWLER_SYNC_ROUTING_KEY);
    }

    // ===== RabbitTemplate =====

    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(messageConverter());
        return template;
    }
}
