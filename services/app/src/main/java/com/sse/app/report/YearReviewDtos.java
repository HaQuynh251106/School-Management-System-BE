package com.sse.app.report;

import jakarta.validation.constraints.NotBlank;

import java.time.Instant;
import java.util.List;

public final class YearReviewDtos {
    private YearReviewDtos() {}

    public record SaveYearDecisionRequest(
            @NotBlank String result,
            @NotBlank String conductGrade,
            String reason) {}
    public record FinalizeYearReviewRequest(boolean confirmed) {}
    public record ReopenYearReviewRequest(@NotBlank String reason, boolean confirmed) {}
    public record ChangeAcademicYearStatusRequest(
            @NotBlank String status,
            @NotBlank String reason,
            boolean confirmed) {}
    public record UpdatePromotionPolicyRequest(
            Double minimumYearlyAverage,
            String minimumConductGrade,
            Double subjectMinimumScore,
            Integer maximumSubjectsBelowMinimum,
            Double minimumAttendanceRate) {}

    public record YearReviewResponse(
            String academicYearId,
            String academicYearName,
            String classId,
            String classCode,
            String className,
            String gradeLevel,
            String yearStatus,
            boolean yearClosed,
            boolean finalized,
            boolean canFinalize,
            List<String> finalizeBlockers,
            String yearlyAverageFormula,
            PromotionPolicy policy,
            YearReviewMetrics metrics,
            List<YearReviewStudent> students,
            Instant generatedAt) {}

    public record YearReviewMetrics(
            int totalStudents,
            int academicallyReady,
            int promoted,
            int retained,
            int eligibleForGraduation,
            int incomplete,
            int conductCompleted,
            int decisionsSaved) {}

    public record YearReviewStudent(
            String studentId,
            String studentCode,
            String studentName,
            List<SemesterResult> semesters,
            List<AnnualSubjectResult> annualSubjects,
            Double yearlyAverage,
            Double attendanceRate,
            boolean academicReady,
            String conductGrade,
            int subjectsBelowMinimum,
            String suggestedResult,
            String result,
            String decisionStatus,
            String reason,
            String reviewedByName,
            Instant reviewedAt,
            Instant finalizedAt) {}

    public record SemesterResult(
            String semesterId,
            String semesterName,
            String periodState,
            Double average,
            Double attendanceRate,
            boolean ready,
            List<String> warnings) {}

    public record AnnualSubjectResult(
            String subjectId,
            String subjectName,
            Double semesterOneAverage,
            Double semesterTwoAverage,
            Double yearlyAverage,
            boolean belowMinimum) {}

    public record PromotionPolicy(
            String academicYearId,
            double minimumYearlyAverage,
            String minimumConductGrade,
            double subjectMinimumScore,
            int maximumSubjectsBelowMinimum,
            Double minimumAttendanceRate) {}
}
