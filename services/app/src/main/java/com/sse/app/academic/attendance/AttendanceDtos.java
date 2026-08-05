package com.sse.app.academic.attendance;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;
import java.time.Instant;
import java.util.List;

public final class AttendanceDtos {
    private AttendanceDtos() {}

    public record Mark(@NotBlank String studentId, @NotBlank String status,
                       String note, Integer lateMinutes) {}

    public record BulkAttendanceRequest(
            @NotBlank String slotId,
            String classId,
            @NotNull LocalDate date,
            String subjectName,
            Integer periodNo,
            @NotNull List<Mark> marks
    ) {}

    public record CreateExcuseRequest(@NotBlank String reason) {}

    public record ReviewExcuseRequest(@NotBlank String decision, String note) {}

    public record AttendanceSummary(
            String studentId,
            LocalDate fromDate,
            LocalDate toDate,
            int total,
            int present,
            int absentExcused,
            int absentUnexcused,
            int late,
            int totalLateMinutes,
            double attendanceRate,
            boolean repeatedViolation,
            Instant generatedAt) {}
}
