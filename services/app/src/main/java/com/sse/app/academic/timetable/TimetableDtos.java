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
            @NotNull @Min(1) @Max(12) Integer periodNo,
            @NotBlank @Pattern(regexp = "(?:[01]\\d|2[0-3]):[0-5]\\d") String startTime,
            @NotBlank @Pattern(regexp = "(?:[01]\\d|2[0-3]):[0-5]\\d") String endTime,
            @NotBlank String semesterId
    ) {}

    public record AutoTimetableRequest(
            @NotBlank String semesterId,
            @NotNull Boolean apply,
            Boolean allowPartial,
            @Pattern(regexp = "K(?:10|11|12)", message = "Phạm vi khối phải là K10, K11 hoặc K12")
            String scopeGradeLevel,
            @Size(max = 255) String draftName
    ) {}

    public record AutoTimetableItem(
            String classId, String classCode, String studyShift,
            String subjectId, String subjectName, String teacherId, String teacherName,
            String roomCode, String dayOfWeek, int periodNo, String startTime, String endTime,
            String status, String message
    ) {}

    public record AutoTimetablePlan(
            String semesterId, String scopeGradeLevel, int existingSlots, int proposedSlots,
            int unscheduledSlots, boolean applied,
            List<AutoTimetableItem> items, List<String> warnings,
            TimetableVersion draftVersion
    ) {}

    public record CreateVersionRequest(
            @NotBlank String semesterId,
            @NotBlank String name
    ) {}

    public record RestoreVersionRequest(@NotBlank String name) {}

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
