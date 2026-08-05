package com.sse.app.academic.assignment;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

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

    public record BatchGradeEntry(
            @NotBlank String submissionId,
            Double score,
            String feedback,
            String reason) {}

    public record BatchGradeRequest(List<BatchGradeEntry> entries) {}

    public record RequestResubmissionRequest(
            @NotBlank @Size(max = 1000) String reason,
            Instant allowedUntil) {}

    public record AssignmentReminderResponse(
            String assignmentId,
            int recipientStudents,
            Instant remindedAt) {}

    public record AssignmentExportFile(
            String filename,
            String contentType,
            byte[] content) {}
}
