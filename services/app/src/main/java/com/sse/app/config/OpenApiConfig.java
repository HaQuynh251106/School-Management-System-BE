package com.sse.app.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {
    @Bean
    public OpenAPI sseOpenApi() {
        String scheme = "bearerAuth";
        return new OpenAPI()
                .info(new Info().title("Smart School Ecosystem API")
                        .version("v1").description("Shared contract for Web FE and Mobile FE"))
                .servers(java.util.List.of(
                        new Server().url("/api/v1").description("Versioned API for Web and Mobile"),
                        new Server().url("/").description("Legacy-compatible Web API")))
                .addSecurityItem(new SecurityRequirement().addList(scheme))
                .components(new Components().addSecuritySchemes(scheme,
                        new SecurityScheme().type(SecurityScheme.Type.HTTP)
                                .scheme("bearer").bearerFormat("JWT")));
    }
}
