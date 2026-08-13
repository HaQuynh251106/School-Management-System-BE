package com.sse.app.academic.timetable;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

public final class EducationPlanDtos {
    private EducationPlanDtos() {}

    public record CreateEducationPlanRequest(
            @NotBlank String academicYearId,
            @NotBlank String gradeLevel,
            @NotBlank @Size(max = 255) String name,
            @Size(max = 2000) String description,
            String sourcePlanId,
            @Size(max = 1000) String revisionReason) {}

    public record PlanActionRequest(@Size(max = 1000) String comment) {}

    public record EducationPlanIssue(
            String severity, String code, String entityType, String entityId, String message) {}

    public record EducationPlanValidation(
            String planId, boolean valid, int errorCount, int warningCount,
            List<EducationPlanIssue> issues) {}
}

