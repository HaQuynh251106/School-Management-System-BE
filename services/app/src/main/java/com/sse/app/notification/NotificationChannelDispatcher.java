package com.sse.app.notification;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sse.app.common.Ids;
import com.sse.app.identity.UserDevice;
import com.sse.app.identity.UserDto;
import com.sse.app.identity.UserService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static com.sse.app.notification.NotificationDtos.NotificationProviderStatus;

/** Email/push provider adapter with bounded retry and per-attempt delivery logs. */
@Service
public class NotificationChannelDispatcher {
    private final NotificationRepository notifications;
    private final NotificationDeliveryLogRepository logs;
    private final UserService users;
    private final ObjectMapper mapper;
    private final FcmAccessTokenProvider fcmTokens;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8)).build();

    @Value("${sse.notifications.provider-mode:mock}")
    private String providerMode;
    @Value("${sse.notifications.max-attempts:3}")
    private int maxAttempts;
    @Value("${sse.notifications.email-provider:sendgrid}")
    private String emailProvider;
    @Value("${sse.notifications.sendgrid.api-key:}")
    private String sendGridApiKey;
    @Value("${sse.notifications.sendgrid.from-email:no-reply@sse.local}")
    private String sendGridFromEmail;
    @Value("${sse.notifications.smtp.host:smtp.gmail.com}")
    private String smtpHost;
    @Value("${sse.notifications.smtp.port:587}")
    private int smtpPort;
    @Value("${sse.notifications.smtp.username:}")
    private String smtpUsername;
    @Value("${sse.notifications.smtp.password:}")
    private String smtpPassword;
    @Value("${sse.notifications.smtp.from-email:}")
    private String smtpFromEmail;
    @Value("${sse.notifications.smtp.auth:true}")
    private boolean smtpAuth;
    @Value("${sse.notifications.smtp.starttls:true}")
    private boolean smtpStartTls;
    @Value("${sse.notifications.fcm.project-id:}")
    private String fcmProjectId;
    @Value("${sse.notifications.fcm.send-url:}")
    private String fcmSendUrl;

    public NotificationChannelDispatcher(NotificationRepository notifications,
                                         NotificationDeliveryLogRepository logs,
                                         UserService users,
                                         ObjectMapper mapper,
                                         FcmAccessTokenProvider fcmTokens) {
        this.notifications = notifications;
        this.logs = logs;
        this.users = users;
        this.mapper = mapper;
        this.fcmTokens = fcmTokens;
    }

    public NotificationProviderStatus providerStatus() {
        boolean smtp = "smtp".equalsIgnoreCase(emailProvider);
        boolean emailConfigured = smtp ? smtpConfigured()
                : sendGridApiKey != null && !sendGridApiKey.isBlank();
        String fromEmail = smtp ? smtpFromEmail : sendGridFromEmail;
        return new NotificationProviderStatus(
                providerMode == null ? "MOCK" : providerMode.trim().toUpperCase(),
                emailConfigured,
                fromEmail,
                fcmTokens.isConfigured() && fcmProjectId != null && !fcmProjectId.isBlank(),
                fcmTokens.source(),
                fcmProjectId);
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
        if ("smtp".equalsIgnoreCase(emailProvider)) return sendSmtp(row);
        if (sendGridApiKey == null || sendGridApiKey.isBlank()) {
            throw new IllegalStateException("SendGrid API key chua duoc cau hinh");
        }
        UserDto recipient = users.dtoById(row.getRecipientId());
        if (recipient.email() == null || recipient.email().isBlank()) {
            throw new IllegalStateException("Nguoi nhan chua co email");
        }
        Map<String, Object> payload = Map.of(
                "personalizations", List.of(Map.of("to", List.of(Map.of("email", recipient.email())))),
                "from", Map.of("email", sendGridFromEmail),
                "subject", row.getTitle(),
                "content", List.of(Map.of("type", "text/plain", "value", row.getBody())));
        return postJson("https://api.sendgrid.com/v3/mail/send", "Bearer " + sendGridApiKey, payload);
    }

    private String sendSmtp(Notification row) {
        if (!smtpConfigured()) {
            throw new IllegalStateException(
                    "SMTP chua duoc cau hinh day du host, username, password va from-email");
        }
        UserDto recipient = users.dtoById(row.getRecipientId());
        if (recipient.email() == null || recipient.email().isBlank()) {
            throw new IllegalStateException("Nguoi nhan chua co email");
        }

        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost(smtpHost.trim());
        sender.setPort(smtpPort);
        sender.setUsername(smtpUsername.trim());
        sender.setPassword(smtpPassword);
        Properties properties = sender.getJavaMailProperties();
        properties.put("mail.smtp.auth", Boolean.toString(smtpAuth));
        properties.put("mail.smtp.starttls.enable", Boolean.toString(smtpStartTls));
        properties.put("mail.smtp.starttls.required", Boolean.toString(smtpStartTls));
        properties.put("mail.smtp.connectiontimeout", "10000");
        properties.put("mail.smtp.timeout", "15000");
        properties.put("mail.smtp.writetimeout", "15000");

        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(smtpFromEmail.trim());
        message.setTo(recipient.email().trim());
        message.setSubject(row.getTitle());
        message.setText(row.getBody());
        sender.send(message);
        return "SMTP accepted by " + smtpHost.trim();
    }

    private boolean smtpConfigured() {
        return smtpHost != null && !smtpHost.isBlank()
                && smtpPort > 0
                && smtpUsername != null && !smtpUsername.isBlank()
                && smtpPassword != null && !smtpPassword.isBlank()
                && smtpFromEmail != null && !smtpFromEmail.isBlank();
    }

    private String sendPush(Notification row) throws Exception {
        if (fcmProjectId == null || fcmProjectId.isBlank()) {
            throw new IllegalStateException("FCM project ID chua duoc cau hinh");
        }
        List<UserDevice> devices = users.devices(row.getRecipientId(), false);
        if (devices.isEmpty()) {
            throw new IllegalStateException("Nguoi nhan chua dang ky thiet bi push");
        }
        String lastResponse = "";
        String accessToken = fcmTokens.accessToken();
        for (UserDevice device : devices) {
            Map<String, Object> payload = Map.of("message", Map.of(
                    "token", device.getDeviceToken(),
                    "notification", Map.of("title", row.getTitle(), "body", row.getBody()),
                    "data", Map.of("deepLink", row.getDeepLink() == null ? "" : row.getDeepLink(),
                            "notificationId", row.getId())));
            lastResponse = postJson(resolveFcmSendUrl(), "Bearer " + accessToken, payload);
        }
        return lastResponse;
    }

    private String resolveFcmSendUrl() {
        if (fcmSendUrl != null && !fcmSendUrl.isBlank()) return fcmSendUrl;
        return "https://fcm.googleapis.com/v1/projects/" + fcmProjectId + "/messages:send";
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
        String emailProviderName = "smtp".equalsIgnoreCase(emailProvider)
                ? "SMTP" : "SENDGRID";
        logs.save(NotificationDeliveryLog.builder()
                .id(Ids.gen("ndl")).notificationId(row.getId())
                .channel(row.getChannel())
                .provider("EMAIL".equals(row.getChannel()) ? emailProviderName : "FCM")
                .attemptNo(attempt).status(status).providerResponse(response)
                .errorMessage(error).attemptedAt(Instant.now()).build());
    }

    private String safeMessage(Exception error) {
        String message = error.getMessage();
        return message == null ? error.getClass().getSimpleName()
                : message.substring(0, Math.min(message.length(), 1000));
    }
}
