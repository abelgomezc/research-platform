package com.abegomez.research.common.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI researchPlatformOpenApi() {
        return new OpenAPI().info(new Info()
                .title("AI Research & Intelligence Platform")
                .description("API de investigaciones agenticas con evidencia verificada y citas.")
                .version("0.1.0"));
    }
}
