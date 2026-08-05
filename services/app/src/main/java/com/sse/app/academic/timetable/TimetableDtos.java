package com.sse.app.academic.timetable;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.time.Instant;

public final class TimetableDtos {
    private TimetableDtos() {}

    public record CreateSlotRequest(
            String id,
            @NotBlank String classId,
            @NotBlank String subjectId,
            @NotBlank String teacherId,
            String roomCode,
            @NotBlank String dayOfWeek,
            @NotNull @Min(1) @Max(5) Integer periodNo,
            @NotBlank @Pattern(regexp = "(?:[01]\\d|2[0-3]):[0-5]\\d") String startTime,
            @NotBlank @Pattern(regexp = "(?:[01]\\d|2[0-3]):[0-5]\\d") String endTime,
            @NotBlank String semesterId
    ) {}

    public record AutoTimetableRequest(
            @NotBlank String semesterId,
            @NotNull Boolean apply,
            Boolean allowPartial,
            Boolean rebuildExisting,
            String strategy
    ) {
        public AutoTimetableRequest(String semesterId, Boolean apply, Boolean allowPartial) {
            this(semesterId, apply, allowPartial, false, "BALANCED");
        }
    }

    public record AutoTimetableItem(
            String classId, String classCode, String studyShift,
            String subjectId, String subjectName, String teacherId, String teacherName,
            String roomCode, String dayOfWeek, int periodNo, String startTime, String endTime,
            String status, String message
    ) {}

    public record AutoTimetablePlan(
            String semesterId, int existingSlots, int proposedSlots,
            int unscheduledSlots, boolean applied,
            List<AutoTimetableItem> items, List<String> warnings,
            String strategy, int qualityScore, String strategySummary
    ) {
        public AutoTimetablePlan(String semesterId, int existingSlots, int proposedSlots,
                                 int unscheduledSlots, boolean applied,
                                 List<AutoTimetableItem> items, List<String> warnings) {
            this(semesterId, existingSlots, proposedSlots, unscheduledSlots, applied,
                    items, warnings, "BALANCED", 100, "Cân bằng toàn diện");
        }
    }

    public record CreateVersionRequest(
            @NotBlank String semesterId,
            @NotBlank String name
    ) {}

    public record RestoreVersionRequest(@NotBlank String name) {}

    public record PublishVersionRequest(
            @NotBlank @Size(max = 255) String versionName,
            @NotBlank @Size(max = 1000) String reason
    ) {}

    public record RetryPublicationRequest(
            @NotBlank @Size(max = 1000) String reason
    ) {}

    public record TimetableChange(
            String type,
            String classId,
            String classCode,
            String subjectId,
            String subjectName,
            String previousTeacherId,
            String previousTeacherName,
            String newTeacherId,
            String newTeacherName,
            String previousRoomCode,
            String newRoomCode,
            String previousDayOfWeek,
            Integer previousPeriodNo,
            String newDayOfWeek,
            Integer newPeriodNo,
            String summary
    ) {}

    public record TimetablePublicationPreview(
            String planId,
            String planName,
            String previousPlanId,
            String previousPlanName,
            String semesterId,
            boolean firstPublication,
            int changeCount,
            int affectedClassCount,
            int teacherRecipientCount,
            int studentRecipientCount,
            int parentRecipientCount,
            int totalRecipientCount,
            List<TimetableChange> changes
    ) {}

    public record TimetablePublishResult(
            TimetableVersion version,
            TimetablePublicationStatus publication
    ) {}

    public record TimetablePublicationStatus(
            String id,
            String planId,
            String previousPlanId,
            String semesterId,
            String eventType,
            String status,
            String reason,
            int changeCount,
            int affectedClassCount,
            int teacherRecipientCount,
            int studentRecipientCount,
            int parentRecipientCount,
            int totalRecipientCount,
            int deliveredRecipientCount,
            int failedRecipientCount,
            int channelPendingCount,
            int channelDeliveredCount,
            int channelFailedCount,
            int attempts,
            String lastError,
            Instant createdAt,
            Instant processedAt,
            Instant updatedAt
    ) {}

    public record TimetableVersion(
            String id, String semesterId, String name, String status,
            Integer versionNo, Integer qualityScore, Integer totalPeriods,
            Integer scheduledPeriods, Integer unscheduledPeriods,
            String conflictSummary, String sourcePlanId,
            String createdBy, Instant createdAt, Instant updatedAt,
            String publishedBy, Instant publishedAt
    ) {}

    public record TimetableVersionSlot(
            String id, String planId, String classId, String classCode, String studyShift,
            String subjectId, String subjectName, String teacherId, String teacherName,
            String roomCode, String dayOfWeek, Integer periodNo,
            String startTime, String endTime, Boolean locked
    ) {}
}
