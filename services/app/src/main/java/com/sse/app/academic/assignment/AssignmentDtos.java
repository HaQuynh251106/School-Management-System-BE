package com.sse.app.academic.assignment;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;

public final class AssignmentDtos {
    private AssignmentDtos() {}

    public record CreateAssignmentRequest(
            String id, @NotBlank String classId, @NotBlank String subjectId, @NotBlank String title,
            String description, Instant deadline, Boolean allowLate, String attachmentName, Boolean publishNow,
            String attachmentFileId) {}

    public record SubmitRequest(String content, String attachmentName, String attachmentFileId) {}

    public record GradeSubmissionRequest(
            Double score,
            String feedback,
            @Size(max = 500, message = "Lý do sửa kết quả không được vượt quá 500 ký tự") String reason) {}
}
