package com.sse.app.file;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "sse.storage.minio")
public class MinioStorageProperties {
    private String endpoint = "http://localhost:9000";
    private String accessKey = "sse";
    private String secretKey = "sse_dev_minio";
    private String bucket = "sse-files";
    private int presignExpirySeconds = 900;
}
