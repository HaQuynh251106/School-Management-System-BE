package com.sse.app.student.support;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public final class StudentSupportDtos {
    private StudentSupportDtos() {}

    public record SaveInterventionRequest(
            @NotBlank String studentId,
            @NotBlank String classId,
            @NotBlank String category,
            @NotBlank String severity,
            @NotBlank @Size(max = 300) String title,
            @NotBlank @Size(max = 3000) String description,
            @Size(max = 3000) String actionTaken,
            LocalDate followUpDate,
            @NotBlank String status) {}

    public record UpdateInterventionRequest(
            @NotBlank String category,
            @NotBlank String severity,
            @NotBlank @Size(max = 300) String title,
            @NotBlank @Size(max = 3000) String description,
            @Size(max = 3000) String actionTaken,
            LocalDate followUpDate,
            @NotBlank String status) {}

    public record FamilyContactRequest(@NotBlank @Size(max = 2000) String message) {}

    public record ParentContact(String id, String fullName) {}

    public record InterventionView(
            String id,
            String studentId,
            String studentCode,
            String studentName,
            String classId,
            String classCode,
            String teacherId,
            String teacherName,
            String category,
            String severity,
            String title,
            String description,
            String actionTaken,
            LocalDate followUpDate,
            String status,
            boolean parentContacted,
            Instant parentContactedAt,
            Instant resolvedAt,
            Instant createdAt,
            Instant updatedAt,
            List<ParentContact> parentContacts,
            boolean editable,
            boolean familyContactAllowed) {}

    public record FamilyContactResult(String interventionId, int recipients, Instant sentAt) {}
}
