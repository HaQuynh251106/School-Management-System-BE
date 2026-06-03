package com.sse.app.common;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;

@RestController
public class HealthController {

    @GetMapping({"/", "/health"})
    public Map<String, Object> health() {
        return Map.of(
                "service", "sse-app",
                "status", "UP",
                "time", Instant.now().toString());
    }
}
