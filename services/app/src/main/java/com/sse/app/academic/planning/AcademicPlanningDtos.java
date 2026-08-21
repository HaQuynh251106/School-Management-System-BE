package com.sse.app.academic.planning;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

public final class AcademicPlanningDtos {
    private AcademicPlanningDtos() {}

    public record PlanRequest(
            String id,
            @NotBlank String academicYearId,
            @NotBlank String gradeLevel,
            @NotBlank String name,
            @Min(0) @Max(14) Integer maxProgressGapDays,
            String programId,
            String description) {
        public PlanRequest(String id, String academicYearId, String gradeLevel,
                           String name, Integer maxProgressGapDays) {
            this(id, academicYearId, gradeLevel, name, maxProgressGapDays, null, null);
        }
    }

    public record PlanUpdateRequest(
            @NotBlank String name,
            @Min(0) @Max(14) Integer maxProgressGapDays,
            String programId,
            String description) {}

    public record NewVersionRequest(String name) {}

    public record PlanSubjectRequest(
            String id,
            @NotBlank String semesterId,
            @NotBlank String subjectId,
            @Min(1) @Max(20) int weeklyPeriods,
            @Min(1) @Max(300) int totalPeriods,
            @NotNull LocalDate startDate,
            @NotNull LocalDate endDate,
            Boolean examRequired,
            Integer displayOrder) {}

    public record PlanStageRequest(
            String id,
            @NotBlank String code,
            @NotBlank String name,
            @Min(1) int sequence,
            @NotNull LocalDate startDate,
            @NotNull LocalDate endDate,
            @Min(1) @Max(300) int targetPeriods,
            String description) {}

    public record CurriculumItemRequest(
            String id,
            String parentId,
            @NotBlank String itemType,
            @NotBlank String code,
            @NotBlank String title,
            @Min(1) int sequence,
            @Min(0) @Max(300) int plannedPeriods,
            String description) {}

    public record SpecialWeekRequest(
            String id,
            @NotBlank String weekType,
            @Min(1) @Max(30) int weekNumber,
            @NotBlank String name,
            String description) {}

    public record CurriculumDistributionRequest(
            String id,
            String curriculumItemId,
            @Min(1) @Max(30) int weekNumber,
            @NotBlank String contentType,
            @NotBlank String title,
            @Min(1) @Max(20) int periods,
            String notes) {}

    public record AssessmentPlanRequest(
            String id,
            @NotBlank String semesterId,
            String classId,
            @NotBlank String subjectId,
            @NotBlank String assessmentType,
            String name,
            String assessmentForm,
            List<String> curriculumItemIds,
            String resultMethod,
            @Min(1) @Max(30) int weekNumber,
            @Min(15) @Max(300) int durationMinutes,
            String teacherId,
            List<String> teacherIds,
            String notes) {}

    public record WorkflowRequest(@NotBlank String comment) {}

    public record ValidationIssue(
            String level, String code, String message, String referenceId) {}

    public record PlanValidationReport(
            boolean valid,
            long errorCount,
            long warningCount,
            List<ValidationIssue> issues) {}

    public record ApprovalHistoryView(
            String id,
            String planId,
            String action,
            String fromStatus,
            String toStatus,
            String actorId,
            String actorName,
            String actorRole,
            String comment,
            java.time.Instant createdAt) {}

    public record AnnualSubjectSummary(
            String subjectId,
            String subjectName,
            String subjectType,
            int semester1Periods,
            int semester2Periods,
            int annualPeriods,
            int configuredAnnualPeriods,
            boolean periodsMatch) {}

    public record PlanInitializationResult(
            int subjectRowsCreated,
            int subjectRowsUpdated,
            int stagesCreated,
            int curriculumItemsCreated,
            int distributionsCreated,
            int specialWeeksCreated,
            int assessmentsCreated) {}

    public record PublishedPlanView(
            AcademicTrainingPlan plan,
            String classId,
            String classCode,
            List<AnnualSubjectSummary> subjects,
            List<AcademicAssessmentPlan> assessments) {}

    public record ExamScheduleRequest(
            String id,
            @NotBlank String semesterId,
            @NotBlank String subjectId,
            @NotBlank String name,
            @NotNull LocalDate examDate,
            @NotNull LocalTime startTime,
            @Min(15) @Max(300) int durationMinutes,
            String roomId,
            String proctorTeacherId,
            String status,
            String notes) {}

    public record PlanReadiness(
            boolean ready,
            int semesterCount,
            int configuredSubjectRows,
            int examCount,
            int stageCount,
            int curriculumItemCount,
            int specialWeekCount,
            int versionNumber,
            String status,
            List<String> issues) {}

    public record PlanSubjectDetail(
            AcademicTrainingPlanSubject subject,
            List<AcademicTrainingPlanStage> stages,
            List<AcademicCurriculumItem> curriculum,
            List<AcademicTrainingPlanSpecialWeek> specialWeeks) {}

    public record PlanDetail(
            AcademicTrainingPlan plan,
            List<PlanSubjectDetail> subjects,
            List<AcademicExamSchedule> exams,
            PlanReadiness readiness) {}
}
