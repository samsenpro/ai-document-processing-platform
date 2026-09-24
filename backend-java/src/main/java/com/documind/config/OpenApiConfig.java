package com.documind.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    OpenAPI documindOpenApi(@Value("${documind.version}") String version) {
        return new OpenAPI()
                .info(new Info()
                        .title("DocuMind AI API")
                        .version(version)
                        .description("""
                                Intelligent document processing platform. Upload documents, process them \
                                asynchronously (OCR, classification, entity extraction and summarization) and \
                                query the structured results. Errors follow RFC 7807 (application/problem+json) \
                                and always include a stable `code` and the `correlationId` of the request.""")
                        .license(new License().name("MIT").url("https://opensource.org/licenses/MIT")))
                .components(new Components().addSecuritySchemes("bearerAuth", new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .bearerFormat("JWT")));
    }
}
