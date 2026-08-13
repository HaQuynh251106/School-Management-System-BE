package com.sse.app.academic.timetable;

import jakarta.validation.constraints.*;

import java.time.LocalDate;

public final class TeachingProgressDtos {
    private TeachingProgressDtos() {}

    public record SaveProgressRequest(
            @NotBlank String timetableSlotId,
            @NotNull LocalDate lessonDate,
            @Min(0) @Max(6) int completedPeriods,
            @NotBlank @Size(max = 1000) String topic,
            @NotBlank @Pattern(regexp = "COMPLETED|CANCELLED") String status,
            @Size(max = 1000) String reason,
            LocalDate makeupDate) {}

    public record ReviewMakeupRequest(
            @NotBlank @Pattern(regexp = "APPROVED|REJECTED") String status,
            @Size(max = 1000) String reviewNote) {}
}
