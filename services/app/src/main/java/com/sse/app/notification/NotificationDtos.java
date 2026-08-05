package com.sse.app.notification;

import jakarta.validation.constraints.NotBlank;

import java.time.Instant;
import java.util.Map;

public final class NotificationDtos {
    private NotificationDtos() {}

    public record CreateAnnouncementRequest(
            String id, @NotBlank String title, @NotBlank String body, String audience) {}

    public record CreateTemplateRequest(
            String id, @NotBlank String code, String name, String channel,
            String titleTemplate, String bodyTemplate, Boolean active) {}

    public record UpdatePreferenceRequest(
            @NotBlank String notificationType,
            @NotBlank String channel,
            boolean enabled) {}

    public record NotificationDeliveryStatus(
            Notification notification,
            java.util.List<NotificationDeliveryLog> attempts) {}

    public record NotificationOperationsSummary(
            long totalNotifications,
            long queued,
            long sent,
            long failed,
            long retrying,
            long deliveryAttempts,
            long successfulAttempts,
            long failedAttempts,
            double failureRatePercent,
            Map<String, Long> notificationsByChannel,
            Instant generatedAt) {}
}
