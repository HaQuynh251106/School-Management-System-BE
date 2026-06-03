package com.sse.app.security;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "sse.jwt")
public class JwtProperties {
    private String secret = "dev-secret-do-not-use-in-prod-change-me-please-0123456789";
    private long accessTtlSeconds = 3600;
    private long refreshTtlSeconds = 604800;
}
