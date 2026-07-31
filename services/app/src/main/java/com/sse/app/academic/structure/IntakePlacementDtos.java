package com.sse.app.academic.structure;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

import java.util.List;

public final class IntakePlacementDtos {
    private IntakePlacementDtos() {}

    public record LockedPlacement(@NotBlank String studentId, @NotBlank String classCode) {}

    public record PreviewRequest(
            @NotBlank String academicYearId,
            @NotBlank String gradeLevel,
            @Min(1) @Max(60) int maxStudentsPerClass,
            @Min(0) @Max(30) int desiredClassCount,
            boolean autoCreateClasses,
            boolean balanceGender,
            String defaultStudyShift,
            List<LockedPlacement> lockedPlacements
    ) {}

    public record Candidate(
            String id,
            String studentCode,
            String fullName,
            String gender,
            String previousClassId,
            boolean locked
    ) {}

    public record ClassPlan(
            String classId,
            String classCode,
            boolean newClass,
            int capacity,
            int existingStudents,
            int assignedStudents,
            int maleCount,
            int femaleCount,
            int otherCount,
            List<Candidate> students
    ) {}

    public record PreviewResponse(
            String academicYearId,
            String gradeLevel,
            int candidateCount,
            int requiredClassCount,
            int existingClassCount,
            int newClassCount,
            int assignedCount,
            int unassignedCount,
            List<ClassPlan> classes,
            List<Candidate> unassignedStudents,
            List<String> warnings
    ) {}

    public record ApplyResponse(
            String runId,
            int assignedCount,
            int createdClassCount,
            List<String> createdClassCodes,
            List<ClassPlan> classes
    ) {}

    public record UndoRequest(@NotBlank String academicYearId, @NotBlank String gradeLevel) {}

    public record UndoResponse(String runId, int restoredStudents, int removedClasses) {}

    public record RunSummary(
            String id,
            String academicYearId,
            String gradeLevel,
            String status,
            int assignedCount,
            String createdAt,
            String createdBy
    ) {}
}
