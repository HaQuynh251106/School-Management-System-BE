package com.sse.app.notification;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public final class NotificationDtos {
    private NotificationDtos() {}

    public record CreateAnnouncementRequest(
            String id, @NotBlank String title, @NotBlank String body, String audience) {}

    public record CreateTemplateRequest(
            String id, @NotBlank String code, String name, String channel,
            String titleTemplate, String bodyTemplate, Boolean active) {}

    public record UpdatePreferenceRequest(@NotBlank String channel, Boolean enabled) {}

    public record RegisterDeviceRequest(
            @NotBlank @Size(max = 1000) String deviceToken,
            @NotBlank @Pattern(regexp = "ANDROID|IOS|WEB") String platform) {}
}
