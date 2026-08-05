package com.sse.app.common;

import com.sse.app.file.MinioStorageProperties;
import com.sse.app.security.CurrentUserHolder;
import io.minio.BucketExistsArgs;
import io.minio.MinioClient;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.sql.DataSource;
import java.sql.Connection;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
public class HealthController {
    private final DataSource dataSource;
    private final RabbitTemplate rabbit;
    private final MinioClient minio;
    private final MinioStorageProperties minioProperties;

    public HealthController(DataSource dataSource, RabbitTemplate rabbit,
                            MinioClient minio,
                            MinioStorageProperties minioProperties) {
        this.dataSource = dataSource;
        this.rabbit = rabbit;
        this.minio = minio;
        this.minioProperties = minioProperties;
    }

    @GetMapping({"/", "/health"})
    public Map<String, Object> health() {
        return Map.of(
                "service", "sse-app",
                "status", "UP",
                "time", Instant.now().toString());
    }

    @GetMapping("/health/ready")
    public ResponseEntity<Map<String, Object>> readiness() {
        Map<String, Object> body = dependencyHealth();
        boolean ready = body.values().stream()
                .filter(Map.class::isInstance)
                .map(Map.class::cast)
                .allMatch(item -> "UP".equals(item.get("status")));
        body.put("status", ready ? "UP" : "DEGRADED");
        return ResponseEntity.status(
                ready ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE)
                .body(body);
    }

    @GetMapping("/admin/operations/health")
    public Map<String, Object> operationsHealth() {
        CurrentUserHolder.requireRole("ADMIN");
        Map<String, Object> body = dependencyHealth();
        body.put("service", "sse-app");
        body.put("time", Instant.now().toString());
        return body;
    }

    private Map<String, Object> dependencyHealth() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("postgresql", checkDatabase());
        body.put("rabbitmq", checkRabbit());
        body.put("minio", checkMinio());
        return body;
    }

    private Map<String, Object> checkDatabase() {
        try (Connection connection = dataSource.getConnection()) {
            return Map.of("status", connection.isValid(2) ? "UP" : "DOWN");
        } catch (Exception ex) {
            return Map.of("status", "DOWN", "error", safe(ex));
        }
    }

    private Map<String, Object> checkRabbit() {
        try {
            Boolean open = rabbit.execute(channel -> channel.isOpen());
            return Map.of("status", Boolean.TRUE.equals(open) ? "UP" : "DOWN");
        } catch (Exception ex) {
            return Map.of("status", "DOWN", "error", safe(ex));
        }
    }

    private Map<String, Object> checkMinio() {
        try {
            boolean exists = minio.bucketExists(BucketExistsArgs.builder()
                    .bucket(minioProperties.getBucket()).build());
            return Map.of("status", exists ? "UP" : "DOWN",
                    "bucket", minioProperties.getBucket());
        } catch (Exception ex) {
            return Map.of("status", "DOWN", "error", safe(ex),
                    "bucket", minioProperties.getBucket());
        }
    }

    private String safe(Exception ex) {
        String message = ex.getMessage();
        if (message == null || message.isBlank()) {
            return ex.getClass().getSimpleName();
        }
        return message.length() <= 240 ? message : message.substring(0, 240);
    }
}
