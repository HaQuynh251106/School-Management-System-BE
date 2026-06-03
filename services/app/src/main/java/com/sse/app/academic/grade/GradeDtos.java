package com.sse.app.academic.grade;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.List;

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
}
