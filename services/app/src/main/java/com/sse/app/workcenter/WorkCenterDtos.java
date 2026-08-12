package com.sse.app.workcenter;

import jakarta.validation.constraints.*;
import java.time.*;
import java.util.*;

public final class WorkCenterDtos {
    private WorkCenterDtos() {}

    public record CreateTaskRequest(
            @NotBlank @Size(max = 255) String title,
            @Size(max = 4000) String description,
            @NotBlank String module,
            @NotBlank String priority,
            @NotBlank String assignedRole,
            String assignedTo,
            LocalDate dueDate,
            String sourceType,
            String sourceId,
            String parentTaskId,
            List<@NotBlank @Size(max = 500) String> checklist
    ) {}

    public record UpdateTaskRequest(
            @NotBlank @Size(max = 255) String title,
            @Size(max = 4000) String description,
            @NotBlank String module,
            @NotBlank String priority,
            @NotBlank String assignedRole,
            String assignedTo,
            LocalDate dueDate,
            @Min(0) @Max(100) Integer progressPercent
    ) {}

    public record TransitionRequest(
            @NotBlank String status,
            @Size(max = 4000) String note,
            @Min(0) @Max(100) Integer progressPercent
    ) {}

    public record AddCommentRequest(@NotBlank @Size(max = 4000) String body) {}
    public record AddChecklistRequest(@NotBlank @Size(max = 500) String title, Integer position) {}
    public record ChecklistStateRequest(boolean completed) {}
    public record SnoozeRequest(@NotNull Instant until, @NotBlank @Size(max = 2000) String reason) {}
    public record AddAttachmentRequest(
            @NotBlank @Size(max = 500) String fileName,
            @NotBlank @Size(max = 2000) String fileUrl,
            String contentType,
            @PositiveOrZero Long fileSize
    ) {}

    public record TaskSummary(
            String id, String title, String description, String module, String priority,
            String status, String effectiveStatus, String assignedRole, String assignedTo,
            String assignedToName, LocalDate dueDate, int progressPercent, String slaLevel,
            boolean autoManaged, String sourceType, String sourceId, String parentTaskId,
            String createdBy, String creatorName, Instant createdAt, Instant updatedAt,
            Instant snoozedUntil, boolean overdue
    ) {}

    public record TaskDetail(
            TaskSummary task,
            String resolution,
            String rejectionReason,
            String delayReason,
            Instant acceptedAt,
            Instant startedAt,
            Instant submittedAt,
            Instant completedAt,
            Instant rejectedAt,
            Boolean completedOnTime,
            List<OperationTaskChecklistItem> checklist,
            List<OperationTaskComment> comments,
            List<OperationTaskAttachment> attachments,
            List<OperationTaskHistory> history
    ) {}

    public record WorkCenterStats(
            long total, long newCount, long inProgress, long waitingConfirmation,
            long completed, long overdue, long rejected, long completedOnTime,
            double onTimeRate, Map<String, Long> byPriority, Map<String, Long> byModule,
            Map<String, Long> byAssignee
    ) {}

    public record AssigneeOption(String id, String fullName, String role, String subtitle) {}

    public record AutoTaskCommand(
            @NotBlank String sourceKey, @NotBlank String sourceType, @NotBlank String sourceId,
            @NotBlank String title, String description, @NotBlank String module,
            @NotBlank String priority, @NotBlank String assignedRole, String assignedTo,
            LocalDate dueDate, boolean resolved
    ) {}
}
