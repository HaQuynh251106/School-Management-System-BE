package com.sse.app.academic.teaching;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import java.time.Instant;
import java.util.List;

public final class TeachingDtos {
    private TeachingDtos() {}

    public record TeachingAssignmentDto(
            String id,
            String teacherId,
            String teacherName,
            String classId,
            String classCode,
            String subjectId,
            String subjectName,
            String semesterId,
            String status,
            Instant createdAt,
            Instant updatedAt,
            int weeklyPeriods,
            int specializedRoomPeriods,
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
            String assignedBy) {}

    public record CreateTeachingAssignmentRequest(
            String id,
            @NotBlank String teacherId,
            @NotBlank String classId,
            @NotBlank String subjectId,
            @NotBlank String semesterId,
            String status,
            @Min(1) @Max(20) Integer weeklyPeriods,
            @Min(0) @Max(20) Integer specializedRoomPeriods) {}

    public record UpdateTeachingAssignmentRequest(
            String teacherId,
            String classId,
            String subjectId,
            String semesterId,
            String status,
            @Min(1) @Max(20) Integer weeklyPeriods,
            @Min(0) @Max(20) Integer specializedRoomPeriods) {}

    public record TeacherClassAssignmentDto(
            String id,
            String classId,
            String classCode,
            String subjectId,
            String subjectName,
            String semesterId,
            int weeklyPeriods,
            int specializedRoomPeriods,
            int scheduledPeriods) {}

    public record TeacherWorkloadDto(
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
            List<TeacherClassAssignmentDto> assignments) {}
}
