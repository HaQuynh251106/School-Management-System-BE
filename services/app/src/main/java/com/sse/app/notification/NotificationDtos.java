package com.sse.app.notification;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.time.LocalDate;

public final class NotificationDtos {
    private NotificationDtos() {}

    public record CreateAnnouncementRequest(
            String id,
            @NotBlank @Size(max = 255) String title,
            @NotBlank @Size(max = 4000) String body,
            @Pattern(regexp = "ALL|PARENT|STUDENT|TEACHER|CLASS:[A-Za-z0-9._-]+|CLASS_(STUDENTS|PARENTS|ALL):[A-Za-z0-9._-]+",
                    message = "Phạm vi nhận thông báo không hợp lệ") String audience,
            @Pattern(regexp = "GENERAL|HOLIDAY_EVENT|ADMINISTRATIVE|MEETING|EMERGENCY|HOLIDAY|EVENT|PARENT_MEETING|GRADE|LEARNING|STUDENT_STATUS|ATTENDANCE|FEE|INVOICE|DEBT",
                    message = "Loại thông báo không hợp lệ") String category,
            @Pattern(regexp = "NORMAL|IMPORTANT|URGENT", message = "Mức độ thông báo không hợp lệ") String priority,
            LocalDate holidayStartDate,
            LocalDate holidayEndDate) {}

    public record TeacherAnnouncementScope(
            String classId,
            String classCode,
            int studentCount,
            int parentCount,
            List<String> subjects,
            boolean homeroom) {}

    public record CreateTemplateRequest(
            String id, @NotBlank String code, String name, String channel,
            String titleTemplate, String bodyTemplate, Boolean active) {}

    public record UpdatePreferenceRequest(@NotBlank String channel, Boolean enabled) {}

    public record RegisterDeviceRequest(
            @NotBlank @Size(max = 1000) String deviceToken,
            @NotBlank @Pattern(regexp = "ANDROID|IOS|WEB") String platform) {}

}
