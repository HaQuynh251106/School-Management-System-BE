package com.sse.app.notification;

import com.google.auth.oauth2.GoogleCredentials;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

@Component
public class FcmAccessTokenProvider {
    private static final String FIREBASE_MESSAGING_SCOPE =
            "https://www.googleapis.com/auth/firebase.messaging";

    private final String staticAccessToken;
    private final String serviceAccountFile;
    private GoogleCredentials credentials;

    public FcmAccessTokenProvider(
            @Value("${sse.notifications.fcm.access-token:}") String staticAccessToken,
            @Value("${sse.notifications.fcm.service-account-file:}") String serviceAccountFile) {
        this.staticAccessToken = staticAccessToken == null ? "" : staticAccessToken.trim();
        this.serviceAccountFile = serviceAccountFile == null ? "" : serviceAccountFile.trim();
    }

    public boolean isConfigured() {
        return !staticAccessToken.isBlank() || !serviceAccountFile.isBlank();
    }

    public String source() {
        if (!staticAccessToken.isBlank()) return "STATIC_ACCESS_TOKEN";
        if (!serviceAccountFile.isBlank()) return "SERVICE_ACCOUNT";
        return "NOT_CONFIGURED";
    }

    public synchronized String accessToken() throws IOException {
        if (!staticAccessToken.isBlank()) return staticAccessToken;
        if (serviceAccountFile.isBlank()) {
            throw new IllegalStateException("FCM service account chua duoc cau hinh");
        }
        if (credentials == null) {
            Path path = Path.of(serviceAccountFile).toAbsolutePath().normalize();
            if (!Files.isRegularFile(path)) {
                throw new IllegalStateException("Khong tim thay FCM service account file: " + path);
            }
            try (InputStream input = Files.newInputStream(path)) {
                credentials = GoogleCredentials.fromStream(input)
                        .createScoped(List.of(FIREBASE_MESSAGING_SCOPE));
            }
        }
        credentials.refreshIfExpired();
        if (credentials.getAccessToken() == null
                || credentials.getAccessToken().getTokenValue() == null
                || credentials.getAccessToken().getTokenValue().isBlank()) {
            credentials.refresh();
        }
        return credentials.getAccessToken().getTokenValue();
    }
}
