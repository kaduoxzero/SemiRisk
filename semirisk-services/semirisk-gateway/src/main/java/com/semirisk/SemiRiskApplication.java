package com.semirisk;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.actuate.autoconfigure.amqp.RabbitHealthContributorAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.elasticsearch.ElasticsearchRestHealthContributorAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.mail.MailHealthContributorAutoConfiguration;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(exclude = {
        // Disable mail health indicator (avoids dragging down status when mail is not configured)
        MailHealthContributorAutoConfiguration.class,
        // Disable Redisson auto-configuration (use custom RedissonConfig)
        org.redisson.spring.starter.RedissonAutoConfigurationV2.class,
        // Disable RabbitMQ health indicator
        RabbitHealthContributorAutoConfiguration.class,
        // Disable Elasticsearch health indicator
        ElasticsearchRestHealthContributorAutoConfiguration.class,
})
public class SemiRiskApplication {

	public static void main(String[] args) {
		SpringApplication.run(SemiRiskApplication.class, args);
	}

}
