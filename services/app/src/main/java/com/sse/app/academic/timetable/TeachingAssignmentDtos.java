package com.sse.app.academic.timetable;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

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
