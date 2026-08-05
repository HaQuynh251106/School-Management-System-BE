package com.sse.app.academic.timetable;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.time.LocalDate;
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

    public record TeacherLoadResponse(
            String id, String teacherId, String teacherCode, String teacherName,
            String mainSubject, String semesterId,
            int baseWeeklyPeriods, int reductionWeeklyPeriods, int convertedWeeklyPeriods,
            int targetDirectWeeklyPeriods, int approvedOvertimeWeeklyPeriods,
            int legalWeeklyCap, int annualTargetPeriods, int teachingWeeks,
            boolean homeroomTeacher, String policySource,
            int standardWeeklyPeriods,
            int minWeeklyPeriods, int maxWeeklyPeriods, int maxDailyPeriods,
            int maxConsecutivePeriods,
            int assignedWeeklyPeriods, int remainingWeeklyPeriods,
            int targetBalancePeriods, int overloadPeriods, String workloadStatus,
            long actualTaughtPeriods, long actualTaughtAnnualPeriods, long remainingAnnualPeriods,
            List<String> assignedClasses, List<String> assignedSubjects,
            long approvedRestrictionCount, long pendingRestrictionCount,
            List<String> unavailableSlots, List<String> preferredGradeLevels,
            List<String> preferredDaysOff,
            String note, String reviewNote, String status,
            Instant submittedAt, Instant reviewedAt, String reviewedBy,
            LocalDate extendedClosesOn,
            Instant createdAt, Instant updatedAt) {}

    public record SchedulingReadinessResponse(
            String semesterId, boolean curriculumReady,
            int classCount, int roomCount, int teacherCount,
            int teachersMissingSpecialization, int teachersOverLimit,
            long pendingRestrictionCount, long approvedRestrictionCount,
            int requirementCount, int assignmentCount, int missingAssignmentCount,
            int timetableSlotCount, int expectedTimetableSlotCount,
            boolean assignmentReady, boolean timetableReady,
            List<String> blockingWarnings, List<String> advisoryWarnings) {}

    public record WorkloadPolicyResponse(
            String id, String academicYearId, String schoolLevel,
            int baseWeeklyPeriods, int teachingWeeks, int maxOvertimePercent,
            int homeroomReductionPeriods, LocalDate effectiveFrom, LocalDate effectiveTo,
            String sourceDocument, boolean active, String configuredBy, Instant updatedAt) {}

    public record SaveWorkloadPolicyRequest(
            @NotBlank String academicYearId,
            @Min(1) @Max(52) int teachingWeeks,
            LocalDate effectiveFrom, LocalDate effectiveTo) {}

    public record SaveWorkloadAdjustmentRequest(
            @NotBlank String teacherId, @NotBlank String academicYearId,
            @NotBlank String category, @NotBlank String dutyType, @NotBlank String title,
            @Min(1) @Max(17) int weeklyPeriods,
            LocalDate effectiveFrom, LocalDate effectiveTo,
            @NotBlank @Size(min = 5, max = 1000) String reason) {}

    public record RevokeWorkloadAdjustmentRequest(@NotBlank @Size(min = 5, max = 1000) String reason) {}

    public record WorkloadAdjustmentResponse(
            String id, String teacherId, String academicYearId, String category,
            String dutyType, String title, int weeklyPeriods,
            LocalDate effectiveFrom, LocalDate effectiveTo, String reason,
            String status, String approvedBy, Instant approvedAt,
            String revokedBy, Instant revokedAt, String revokeReason,
            Instant createdAt, Instant updatedAt) {}

    public record SaveScheduleRestrictionRequest(
            @NotBlank String semesterId,
            @NotNull @Size(min = 1, max = 60) List<@NotBlank String> restrictedSlots,
            @NotNull LocalDate effectiveFrom,
            @NotNull LocalDate effectiveTo,
            @NotBlank @Size(min = 10, max = 1000) String reason,
            @Size(max = 1000) String evidenceUrl) {}

    public record ReviewScheduleRestrictionRequest(
            @NotBlank String action,
            @Size(max = 1000) String decisionNote) {}

    public record RevokeScheduleRestrictionRequest(
            @NotBlank @Size(min = 5, max = 1000) String reason) {}

    public record ScheduleRestrictionResponse(
            String id, String teacherId, String teacherName, String teacherCode,
            String semesterId, List<String> restrictedSlots,
            LocalDate effectiveFrom, LocalDate effectiveTo,
            String reason, String evidenceUrl, String status,
            String decisionNote, String reviewedBy, Instant reviewedAt,
            String revokedBy, Instant revokedAt, String revokeReason,
            Instant submittedAt, Instant createdAt, Instant updatedAt) {}

    public record ScheduleRestrictionHistoryResponse(
            String id, String requestId, String semesterId, String teacherId,
            String action, String previousStatus, String newStatus,
            String details, String actorId, Instant createdAt) {}

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
            List<AutoAssignmentItem> items, List<String> warnings,
            String versionId, Integer versionNo) {}

    public record AssignmentVersionResponse(
            String id, String semesterId, String name, String status, int versionNo,
            int assignmentCount, String warningSummary, String sourcePlanId,
            String createdBy, Instant createdAt, Instant updatedAt,
            String publishedBy, Instant publishedAt) {}

    public record AssignmentVersionItemResponse(
            String id, String planId, String classId, String classCode,
            String subjectId, String subjectName, String teacherId, String teacherName,
            int weeklyPeriods) {}

    public record RestoreAssignmentVersionRequest(@NotBlank String name) {}
}
