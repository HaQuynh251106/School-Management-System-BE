package com.sse.app.academic.leave;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;

public final class LeaveRequestDtos {
    private LeaveRequestDtos() {}

    public record CreateLeaveRequest(@NotNull LocalDate startDate, @NotNull LocalDate endDate,
                                     @NotBlank String reason) {}
    public record DecisionRequest(String note) {}
}
