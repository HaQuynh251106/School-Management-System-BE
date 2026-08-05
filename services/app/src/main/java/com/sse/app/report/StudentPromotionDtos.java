package com.sse.app.report;

import jakarta.validation.constraints.NotBlank;

import java.time.Instant;
import java.util.List;

public final class StudentPromotionDtos {
    private StudentPromotionDtos() {}

    public record PlacementRequest(@NotBlank String studentId, String targetClassId) {}

    public record PromotionPlanRequest(
            @NotBlank String sourceAcademicYearId,
            @NotBlank String targetAcademicYearId,
            @NotBlank String sourceClassId,
            List<PlacementRequest> placements) {}

    public record ExecutePromotionRequest(
            @NotBlank String sourceAcademicYearId,
            @NotBlank String targetAcademicYearId,
            @NotBlank String sourceClassId,
            List<PlacementRequest> placements,
            boolean confirmed) {}

    public record PromotionTargetClass(
            String id,
            String code,
            String name,
            String gradeLevel,
            int studentCount,
            int maxStudents,
            int availableSeats) {}

    public record PromotionMetrics(
            int totalStudents,
            int ready,
            int needsPlacement,
            int alreadyProcessed,
            int completingSchool,
            int blocked) {}

    public record PromotionStudent(
            String summaryId,
            String studentId,
            String studentCode,
            String studentName,
            String result,
            String action,
            String requiredTargetGradeLevel,
            String targetClassId,
            String targetClassCode,
            String status,
            String message) {}

    public record PromotionPreviewResponse(
            String sourceAcademicYearId,
            String sourceAcademicYearName,
            String targetAcademicYearId,
            String targetAcademicYearName,
            String sourceClassId,
            String sourceClassCode,
            boolean canExecute,
            List<String> blockers,
            PromotionMetrics metrics,
            List<PromotionTargetClass> targetClasses,
            List<PromotionStudent> students,
            Instant generatedAt) {}

    public record PromotionExecutionResponse(
            int enrolled,
            int completedSchool,
            int skipped,
            PromotionPreviewResponse preview) {}

    public record UndoPromotionRequest(
            @NotBlank String sourceAcademicYearId,
            @NotBlank String targetAcademicYearId,
            @NotBlank String sourceClassId,
            @NotBlank String reason,
            boolean confirmed) {}

    public record PromotionUndoResponse(
            int revertedEnrollments,
            int restoredCompletedStudents,
            int skipped,
            PromotionPreviewResponse preview) {}

    public record UpdateProgressionStatusRequest(
            @NotBlank String sourceAcademicYearId,
            @NotBlank String sourceClassId,
            @NotBlank String studentId,
            @NotBlank String status,
            @NotBlank String reason,
            boolean confirmed) {}

    public record ProgressionStatusResponse(
            String studentId,
            String studentCode,
            String studentName,
            String progressionStatus,
            String reason,
            Instant updatedAt) {}
}
