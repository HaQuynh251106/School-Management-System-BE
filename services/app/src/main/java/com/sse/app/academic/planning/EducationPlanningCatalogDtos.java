package com.sse.app.academic.planning;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;

public final class EducationPlanningCatalogDtos {
    private EducationPlanningCatalogDtos() {}

    public record ProgramRequest(
            String id,
            @NotBlank String code,
            @NotBlank String name,
            @Min(2000) @Max(2200) int startYear,
            String description,
            String status) {}

    public record ProgramSubjectRequest(
            String id,
            @NotBlank String gradeLevel,
            @NotBlank String subjectId,
            @NotBlank String subjectType,
            @Min(1) @Max(500) int annualPeriods,
            @Min(0) @Max(300) int semester1Periods,
            @Min(0) @Max(300) int semester2Periods,
            @Min(1) @Max(20) int weeklyPeriods,
            boolean required,
            String notes) {}

    public record AutoConfigureProgramRequest(String gradeLevel) {}

    public record AutoConfigureProgramResult(int created, List<String> grades) {}

    public record AutoConfigureTeachersResult(
            int capabilitiesConfigured,
            int homeroomAssignmentsAdjusted,
            int assignmentsRebalanced) {}

    public record CombinationRequest(
            String id,
            @NotBlank String code,
            @NotBlank String name,
            @NotBlank String academicYearId,
            @NotBlank String gradeLevel,
            @Min(1) @Max(20) int expectedClassCount,
            @Min(1) @Max(100) int maxStudents,
            String status,
            @NotEmpty List<String> subjectIds) {}

    public record CombinationDetail(
            SubjectCombination combination,
            List<String> subjectIds,
            List<String> classIds) {}

    public record AssignCombinationRequest(
            @NotBlank String combinationId,
            @NotNull List<String> classIds) {}

    public record TeacherCapabilityRequest(
            @NotBlank String teacherId,
            @NotEmpty List<String> subjectIds,
            String primarySubjectId) {}
}
