package com.sse.app.club;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

import java.time.Instant;
import java.time.LocalDate;

public final class ClubDtos {
    private ClubDtos() {}

    public record CreateClubRequest(
            String id,
            @NotBlank String code,
            @NotBlank String name,
            String description,
            @NotBlank String schedule,
            @Min(1) int capacity,
            @PositiveOrZero long feeAmount,
            boolean approvalRequired,
            @NotNull LocalDate registrationStart,
            @NotNull LocalDate registrationEnd,
            Boolean active) {}

    public record RegisterClubRequest(String studentId) {}

    public record RegistrationDecisionRequest(String note) {}

    public record CancelClubRegistrationRequest(String reason) {}

    public record ClubView(
            String id, String code, String name, String description, String schedule,
            int capacity, int approvedCount, int availableSlots, int waitlistCount,
            long feeAmount, boolean approvalRequired, LocalDate registrationStart,
            LocalDate registrationEnd, boolean active) {}

    public record ClubRegistrationView(
            String id, String clubId, String clubName, String studentId, String studentName,
            String requestedBy, String status, String invoiceId, String decisionNote,
            int waitlistPosition, Instant createdAt, Instant decidedAt, Instant cancelledAt) {}
}
