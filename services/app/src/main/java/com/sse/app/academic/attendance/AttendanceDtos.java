package com.sse.app.academic.attendance;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;
import java.util.List;

public final class AttendanceDtos {
    private AttendanceDtos() {}

    public record Mark(@NotBlank String studentId, @NotBlank String status, String note) {}

    public record BulkAttendanceRequest(
            @NotBlank String slotId,
            String classId,
            @NotNull LocalDate date,
            String subjectName,
            Integer periodNo,
            @NotNull List<Mark> marks
    ) {}
}
