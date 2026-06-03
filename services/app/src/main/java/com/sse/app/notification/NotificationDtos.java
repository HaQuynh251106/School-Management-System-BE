package com.sse.app.notification;

import jakarta.validation.constraints.NotBlank;

public final class NotificationDtos {
    private NotificationDtos() {}

    public record CreateAnnouncementRequest(
            String id, @NotBlank String title, @NotBlank String body, String audience) {}

    public record CreateTemplateRequest(
            String id, @NotBlank String code, String name, String channel,
            String titleTemplate, String bodyTemplate, Boolean active) {}
}
