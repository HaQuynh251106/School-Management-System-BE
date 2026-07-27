package com.sse.app.notification;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.auth.oauth2.GoogleCredentials;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;

@Service
class FirebasePushSender {
    private static final String FIREBASE_SCOPE = "https://www.googleapis.com/auth/firebase.messaging";
    private final boolean enabled;
    private final String projectId;
    private final String credentialsPath;
    private final String credentialsBase64;
    private final ObjectMapper mapper;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private volatile GoogleCredentials credentials;

    FirebasePushSender(@Value("${sse.push.firebase.enabled:false}") boolean enabled,
                       @Value("${sse.push.firebase.project-id:}") String projectId,
                       @Value("${sse.push.firebase.credentials-path:}") String credentialsPath,
                       @Value("${sse.push.firebase.credentials-base64:}") String credentialsBase64,
                       ObjectMapper mapper) {
        this.enabled = enabled;
        this.projectId = projectId == null ? "" : projectId.trim();
        this.credentialsPath = credentialsPath == null ? "" : credentialsPath.trim();
        this.credentialsBase64 = credentialsBase64 == null ? "" : credentialsBase64.trim();
        this.mapper = mapper;
    }

    boolean configured() {
        boolean hasCredentials = !credentialsBase64.isBlank()
                || !credentialsPath.isBlank() && Files.isRegularFile(Path.of(credentialsPath));
        return enabled && !projectId.isBlank() && hasCredentials;
    }

    void send(String deviceToken, String title, String body) throws IOException, InterruptedException {
        if (!configured()) throw new IOException("Firebase Cloud Messaging chưa được cấu hình đầy đủ");
        GoogleCredentials activeCredentials = credentials();
        activeCredentials.refreshIfExpired();
        String json = mapper.writeValueAsString(Map.of("message", Map.of(
                "token", deviceToken,
                "notification", Map.of("title", title, "body", body),
                "webpush", Map.of("headers", Map.of("Urgency", "high"))
        )));
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://fcm.googleapis.com/v1/projects/" + projectId + "/messages:send"))
                .timeout(Duration.ofSeconds(20))
                .header("Authorization", "Bearer " + activeCredentials.getAccessToken().getTokenValue())
                .header("Content-Type", "application/json; charset=UTF-8")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("FCM HTTP " + response.statusCode() + ": " + abbreviate(response.body()));
        }
    }

    private synchronized GoogleCredentials credentials() throws IOException {
        if (credentials != null) return credentials;
        InputStream source = !credentialsBase64.isBlank()
                ? new java.io.ByteArrayInputStream(Base64.getDecoder().decode(credentialsBase64))
                : Files.newInputStream(Path.of(credentialsPath));
        try (InputStream input = source) {
            credentials = GoogleCredentials.fromStream(input).createScoped(List.of(FIREBASE_SCOPE));
        }
        return credentials;
    }

    private String abbreviate(String value) {
        if (value == null) return "";
        return value.length() <= 300 ? value : value.substring(0, 300);
    }
}
