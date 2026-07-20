package com.sse.app.academic.attendance;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import jakarta.validation.Valid;

import java.time.LocalDate;
import java.util.List;

public final class AttendanceDtos {
    private AttendanceDtos() {}

    public record Mark(
            @NotBlank String studentId,
            @NotBlank @Pattern(regexp = "PRESENT|LATE|ABSENT_UNEXCUSED|ABSENT_EXCUSED") String status,
            @Size(max = 255) String note
    ) {}

    public record BulkAttendanceRequest(
            @NotBlank String slotId,
            String classId,
            @NotNull LocalDate date,
            String subjectName,
            Integer periodNo,
            @NotEmpty List<@Valid Mark> marks
    ) {}

    public record AttendanceDayStatus(
            boolean attendanceRequired,
            String announcementId,
            String title,
            String reason,
            LocalDate holidayStartDate,
            LocalDate holidayEndDate
    ) {}

    public record AttendanceSessionStatus(
            String state,
            boolean canMark,
            boolean requiresUnlockReason,
            String message,
            LocalDate date,
            String startTime,
            String endTime,
            String unlockReason,
            java.time.Instant unlockedAt
    ) {}

    public record UnlockAttendanceRequest(
            @NotBlank String slotId,
            @NotNull LocalDate date,
            @NotBlank @Size(min = 10, max = 1000) String reason
    ) {}
}
