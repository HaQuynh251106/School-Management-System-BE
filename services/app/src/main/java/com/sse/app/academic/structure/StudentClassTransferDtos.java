package com.sse.app.academic.structure;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

public final class StudentClassTransferDtos {
    private StudentClassTransferDtos() {}

    public record TransferWindow(
            String academicYearId,
            String academicYearCode,
            boolean eligible,
            String boundaryType,
            String reason,
            int closedSemesterCount,
            int activeSemesterCount,
            String latestClosedSemesterId,
            String latestClosedSemesterName,
            LocalDate latestClosedSemesterEndDate,
            String nextSemesterId,
            String nextSemesterName,
            LocalDate nextSemesterStartDate,
            LocalDate defaultEffectiveDate) {}

    public record TransferRequest(
            @NotBlank String studentId,
            @NotBlank String targetClassId,
            @NotNull LocalDate effectiveDate,
            @NotBlank @Size(min = 10, max = 1000) String reason) {}

    public record UndoRequest(
            @NotBlank @Size(min = 10, max = 1000) String reason) {}
}
