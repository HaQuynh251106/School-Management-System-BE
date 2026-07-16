package com.sse.app.finance;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.time.LocalDate;

public final class FinanceDtos {
    private FinanceDtos() {}

    public record CreateFeePeriodRequest(
            String id, @NotBlank String code, String name, String academicYearId,
            String applyToGrades, LocalDate dueDate) {}

    public record AddFeeItemRequest(String id, @NotBlank String name, @NotNull @Positive Long amount, String gradeLevel) {}

    public record PayRequest(@NotBlank String invoiceId, String method) {}

    public record PaymentCallbackRequest(
            @NotBlank String txnRef,
            @NotBlank String status,
            @NotNull @Positive Long amount,
            @NotBlank String signature) {}
}
