package com.sse.app.academic.timetable;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import jakarta.validation.Valid;

import java.time.Instant;
import java.util.List;

public final class TeachingAssignmentDtos {
    private TeachingAssignmentDtos() {}

    public record SaveTeachingAssignmentRequest(
            @NotBlank String classId,
            @NotBlank String subjectId,
            @NotBlank String teacherId,
            @NotBlank String semesterId,
            @Min(1) @Max(20) int weeklyPeriods
    ) {}

    public record BatchClassAssignmentRequest(
            @NotBlank String classId,
            @Min(1) @Max(20) int weeklyPeriods
    ) {}

    public record BatchSaveTeachingAssignmentRequest(
            @Size(max = 50) List<@Valid BatchClassAssignmentRequest> assignments,
            @Size(max = 50) List<@NotBlank String> classIds,
            @NotBlank String subjectId,
            @NotBlank String teacherId,
            @NotBlank String semesterId,
            @Min(1) @Max(20) Integer weeklyPeriods
    ) {}

    public record TeachingAssignmentResponse(
            String id,
            String classId,
            String classCode,
            String subjectId,
            String subjectName,
            String teacherId,
            String teacherName,
            String semesterId,
            int weeklyPeriods,
            int scheduledPeriods,
            int remainingPeriods,
            int teacherClassCount,
            int teacherWeeklyPeriods,
            int teacherScheduledPeriods,
            boolean fullyScheduled,
            boolean teacherBusy,
            boolean canSchedule,
            String availabilityMessage,
            Instant assignedAt,
            String assignedBy,
            Instant updatedAt
    ) {}

    public record TeacherClassAssignmentResponse(
            String id,
            String classId,
            String classCode,
            String subjectId,
            String subjectName,
            String semesterId,
            int weeklyPeriods,
            int scheduledPeriods
    ) {}

    public record TeacherWorkloadResponse(
            String teacherId,
            String teacherCode,
            String teacherName,
            String mainSubject,
            String status,
            int classCount,
            int subjectCount,
            int weeklyPeriods,
            int scheduledPeriods,
            List<String> classCodes,
            List<String> subjectNames,
            List<TeacherClassAssignmentResponse> assignments
    ) {}
}
