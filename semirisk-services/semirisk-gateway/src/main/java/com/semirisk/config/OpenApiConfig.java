package com.semirisk.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI semiRiskOpenApi() {
        return new OpenAPI().info(new Info()
                .title("SemiRisk API")
                .version("0.0.1")
                .description("SemiRisk AI supply-chain risk intelligence API"));
    }
}
