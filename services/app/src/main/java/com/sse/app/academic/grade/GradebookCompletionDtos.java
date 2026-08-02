package com.sse.app.academic.grade;

import jakarta.validation.constraints.NotBlank;

import java.util.List;

public final class GradebookCompletionDtos {
    private GradebookCompletionDtos() {}

    public record CompletionView(
            String semesterId, String classId, String subjectId, String subjectName,
            boolean completed, String completedBy, String completedAt,
            int studentCount, int missingStudentCount, List<String> missingDetails) {}

    public record CompletionAudit(
            String id, String action, String note, String actorId, String createdAt) {}

    public record CompletionRequest(String note) {}

    public record ReopenRequest(@NotBlank String reason) {}
}
