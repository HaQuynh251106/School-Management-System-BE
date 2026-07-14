package com.sse.app.academic.assignment;

import jakarta.validation.constraints.NotBlank;

import java.time.Instant;

public final class AssignmentDtos {
    private AssignmentDtos() {}

    public record CreateAssignmentRequest(
            String id, @NotBlank String classId, @NotBlank String subjectId, @NotBlank String title,
            String description, Instant deadline, Boolean allowLate, String attachmentFileId, Boolean publishNow) {}

    public record SubmitRequest(String content, String attachmentFileId) {}

    public record GradeSubmissionRequest(Double score, String feedback) {}
}
