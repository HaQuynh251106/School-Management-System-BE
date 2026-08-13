package com.sse.app.academic.grade;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

import java.util.List;

public final class GradeDtos {
    private GradeDtos() {}

    public record Entry(@NotBlank String studentId, @NotNull Double score, String note,
                        @Min(0) Long expectedVersion) {}

    public record BulkGradeRequest(
            String subjectId,
            String classId,
            @NotBlank String semesterId,
            @NotBlank String category,
            @Min(1) Integer assessmentIndex,
            String reason,
            @NotNull @Size(min = 1) List<Entry> entries
    ) {}

    public record CreateGradeRequest(
            @NotBlank String studentId,
            String subjectId,
            @NotBlank String semesterId,
            @NotBlank String category,
            @Min(1) Integer assessmentIndex,
            @NotNull Double score,
            String note
    ) {}

    public record UpdateGradeRequest(
            @NotNull Double score,
            String note,
            @NotBlank String reason,
            @NotNull @Min(0) Long expectedVersion
    ) {}

    public record GradebookSubject(
            String subjectId,
            String subjectName,
            String teacherName,
            boolean editable
    ) {}

    public record TeacherGradebookContext(
            String classId,
            String semesterId,
            String subjectId,
            String subjectName,
            boolean homeroomTeacher,
            boolean canEdit,
            List<GradebookSubject> subjects
    ) {}

    public record GradeSubjectSummary(
            String studentId,
            String subjectId,
            String subjectName,
            String semesterId,
            Double average,
            boolean complete,
            List<String> missingAssessmentKeys
    ) {}

    public record CreateExamCategoryRequest(
            String id, @NotBlank String code, @NotBlank String name, Double weight,
            @Min(1) Integer requiredCount) {}
}
