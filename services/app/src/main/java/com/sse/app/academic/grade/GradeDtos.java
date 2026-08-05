package com.sse.app.academic.grade;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.time.Instant;

public final class GradeDtos {
    private GradeDtos() {}

    public record Entry(@NotBlank String studentId, @NotNull Double score, String note) {}

    public record BulkGradeRequest(
            @NotBlank String subjectId,
            @NotBlank String semesterId,
            @NotBlank String category,
            String reason,
            @NotNull List<Entry> entries
    ) {}

    public record CreateExamCategoryRequest(
            String id, @NotBlank String code, @NotBlank String name, Double weight) {}

    public record GradeCompletenessStudent(
            String studentId,
            String studentCode,
            String studentName,
            int enteredCategories,
            int expectedCategories,
            List<String> missingCategories,
            boolean complete) {}

    public record GradeCompletenessResponse(
            String classId,
            String subjectId,
            String semesterId,
            int totalStudents,
            int completeStudents,
            int incompleteStudents,
            List<GradeCompletenessStudent> students,
            Instant generatedAt) {}
}
