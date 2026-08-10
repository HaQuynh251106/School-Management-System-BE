package com.sse.app.notification;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sse.app.common.Ids;
import com.sse.app.identity.UserDevice;
import com.sse.app.identity.UserDto;
import com.sse.app.identity.UserService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Email/push provider adapter with bounded retry and per-attempt delivery logs. */
@Service
public class NotificationChannelDispatcher {
    private final NotificationRepository notifications;
    private final NotificationDeliveryLogRepository logs;
    private final UserService users;
    private final ObjectMapper mapper;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8)).build();

    @Value("${sse.notifications.provider-mode:mock}")
    private String providerMode;
    @Value("${sse.notifications.max-attempts:3}")
    private int maxAttempts;
    @Value("${sse.notifications.sendgrid.api-key:}")
    private String sendGridApiKey;
    @Value("${sse.notifications.sendgrid.from-email:no-reply@sse.local}")
    private String sendGridFromEmail;
    @Value("${sse.notifications.fcm.access-token:}")
    private String fcmAccessToken;
    @Value("${sse.notifications.fcm.send-url:https://fcm.googleapis.com/v1/projects/PROJECT_ID/messages:send}")
    private String fcmSendUrl;

    public NotificationChannelDispatcher(NotificationRepository notifications,
                                         NotificationDeliveryLogRepository logs,
                                         UserService users,
                                         ObjectMapper mapper) {
        this.notifications = notifications;
        this.logs = logs;
        this.users = users;
        this.mapper = mapper;
    }

    public Notification dispatch(String recipientId, String type, String channel,
                                 String title, String body, String refType,
                                 String refId, String deepLink, String groupKey) {
        Notification row = notifications.save(Notification.builder()
                .id(Ids.gen("noti")).recipientId(recipientId).type(type)
                .channel(channel).title(title).body(body).read(false)
                .refType(refType).refId(refId).deepLink(deepLink).groupKey(groupKey)
                .status("QUEUED").attemptCount(0).createdAt(Instant.now()).build());
        return deliver(row);
    }

    public Notification retry(Notification row) {
        row.setStatus("QUEUED");
        row.setErrorMessage(null);
        return deliver(row);
    }

    private Notification deliver(Notification row) {
        int startAttempt = row.getAttemptCount() == null ? 0 : row.getAttemptCount();
        Exception lastError = null;
        for (int offset = 1; offset <= maxAttempts; offset++) {
            int attempt = startAttempt + offset;
            try {
                String response = send(row);
                log(row, attempt, "SENT", response, null);
                row.setStatus("SENT");
                row.setAttemptCount(attempt);
                row.setSentAt(Instant.now());
                row.setErrorMessage(null);
                return notifications.save(row);
            } catch (Exception error) {
                lastError = error;
                log(row, attempt, "FAILED", null, safeMessage(error));
                row.setAttemptCount(attempt);
                row.setStatus(offset == maxAttempts ? "FAILED" : "RETRYING");
                row.setErrorMessage(safeMessage(error));
                notifications.save(row);
                if (offset < maxAttempts) {
                    try {
                        Thread.sleep(Math.min(2_000L, 250L * (1L << (offset - 1))));
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }
        row.setStatus("FAILED");
        row.setErrorMessage(lastError == null ? "Delivery interrupted" : safeMessage(lastError));
        return notifications.save(row);
    }

    private String send(Notification row) throws Exception {
        if ("mock".equalsIgnoreCase(providerMode)) {
            return "MOCK provider accepted " + row.getChannel();
        }
        return switch (row.getChannel()) {
            case "EMAIL" -> sendEmail(row);
            case "PUSH" -> sendPush(row);
            default -> throw new IllegalArgumentException("Unsupported external channel " + row.getChannel());
        };
    }

    private String sendEmail(Notification row) throws Exception {
        if (sendGridApiKey.isBlank()) throw new IllegalStateException("SendGrid API key chưa được cấu hình");
        UserDto recipient = users.dtoById(row.getRecipientId());
        if (recipient.email() == null || recipient.email().isBlank()) {
            throw new IllegalStateException("Người nhận chưa có email");
        }
        Map<String, Object> payload = Map.of(
                "personalizations", List.of(Map.of("to", List.of(Map.of("email", recipient.email())))),
                "from", Map.of("email", sendGridFromEmail),
                "subject", row.getTitle(),
                "content", List.of(Map.of("type", "text/plain", "value", row.getBody())));
        return postJson("https://api.sendgrid.com/v3/mail/send", "Bearer " + sendGridApiKey, payload);
    }

    private String sendPush(Notification row) throws Exception {
        if (fcmAccessToken.isBlank()) throw new IllegalStateException("FCM access token chưa được cấu hình");
        List<UserDevice> devices = users.devices(row.getRecipientId(), false);
        if (devices.isEmpty()) throw new IllegalStateException("Người nhận chưa đăng ký thiết bị push");
        String lastResponse = "";
        for (UserDevice device : devices) {
            Map<String, Object> payload = Map.of("message", Map.of(
                    "token", device.getDeviceToken(),
                    "notification", Map.of("title", row.getTitle(), "body", row.getBody()),
                    "data", Map.of("deepLink", row.getDeepLink() == null ? "" : row.getDeepLink(),
                            "notificationId", row.getId())));
            lastResponse = postJson(fcmSendUrl, "Bearer " + fcmAccessToken, payload);
        }
        return lastResponse;
    }

    private String postJson(String url, String authorization, Object payload) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(15))
                .header("Authorization", authorization)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(payload)))
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("Provider HTTP " + response.statusCode() + ": " + response.body());
        }
        return "HTTP " + response.statusCode() + (response.body().isBlank() ? "" : ": " + response.body());
    }

    private void log(Notification row, int attempt, String status, String response, String error) {
        logs.save(NotificationDeliveryLog.builder()
                .id(Ids.gen("ndl")).notificationId(row.getId())
                .channel(row.getChannel())
                .provider("EMAIL".equals(row.getChannel()) ? "SENDGRID" : "FCM")
                .attemptNo(attempt).status(status).providerResponse(response)
                .errorMessage(error).attemptedAt(Instant.now()).build());
    }

    private String safeMessage(Exception error) {
        String message = error.getMessage();
        return message == null ? error.getClass().getSimpleName()
                : message.substring(0, Math.min(message.length(), 1000));
    }
}
