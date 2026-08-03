package com.sse.app.academic.timetable;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

public final class WorkloadPlanningDtos {
    private WorkloadPlanningDtos() {}

    public record SaveCurriculumRequirementRequest(
            @NotBlank String semesterId,
            @NotBlank String gradeLevel,
            @NotBlank String subjectId,
            @Min(1) @Max(20) int weeklyPeriods) {}

    public record CopyCurriculumRequirementsRequest(
            @NotBlank String sourceSemesterId,
            @NotBlank String sourceGradeLevel,
            @NotBlank String targetSemesterId,
            @NotBlank String targetGradeLevel,
            @NotNull Boolean overwrite) {}

    public record MissingCurriculumSubject(String subjectId, String subjectName) {}

    public record GradeCurriculumReadiness(
            String gradeLevel, int expectedSubjectCount, int configuredSubjectCount,
            int totalWeeklyPeriods, boolean complete, List<MissingCurriculumSubject> missingSubjects) {}

    public record CurriculumReadiness(
            String semesterId, int expectedSubjectCount, int configuredRequirementCount,
            int totalWeeklyPeriods, boolean complete, List<GradeCurriculumReadiness> grades) {}

    public record CurriculumRequirementHistoryResponse(
            String id, String semesterId, String gradeLevel, String subjectId, String subjectName,
            String action, Integer previousWeeklyPeriods, Integer newWeeklyPeriods,
            String actorId, Instant createdAt) {}

    public record SaveTeacherLoadRequest(
            @NotBlank String semesterId,
            @Min(1) @Max(60) int maxWeeklyPeriods,
            @Size(max = 60) List<@NotBlank String> unavailableSlots,
            @Size(max = 20) List<@NotBlank String> preferredGradeLevels,
            @Size(max = 1000) String note) {}

    public record ReviewTeacherLoadRequest(
            @NotBlank String status,
            @Size(max = 1000) String reviewNote) {}

    public record TeacherLoadResponse(
            String id, String teacherId, String teacherCode, String teacherName,
            String mainSubject, String semesterId, int maxWeeklyPeriods,
            int assignedWeeklyPeriods, int remainingWeeklyPeriods,
            List<String> unavailableSlots, List<String> preferredGradeLevels,
            String note, String reviewNote, String status,
            Instant submittedAt, Instant reviewedAt, String reviewedBy,
            Instant createdAt, Instant updatedAt) {}

    public record AutoAssignmentRequest(
            @NotBlank String semesterId,
            @NotNull Boolean apply,
            Boolean allowPartial) {}

    public record AutoAssignmentItem(
            String classId, String classCode, String gradeLevel,
            String subjectId, String subjectName, int weeklyPeriods,
            String teacherId, String teacherName, int projectedTeacherPeriods,
            String status, String message) {}

    public record AutoAssignmentPlan(
            String semesterId, int requirementCount, int existingCount,
            int proposedCount, int unassignedCount, boolean applied,
            List<AutoAssignmentItem> items, List<String> warnings) {}
}
