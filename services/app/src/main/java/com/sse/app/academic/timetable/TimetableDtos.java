package com.sse.app.academic.timetable;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Pattern;

public final class TimetableDtos {
    private TimetableDtos() {}

    public record CreateSlotRequest(
            String id,
            @NotBlank String classId,
            @NotBlank String subjectId,
            @NotBlank String teacherId,
            String roomCode,
            @NotBlank String dayOfWeek,
            @NotNull @Min(1) @Max(12) Integer periodNo,
            @NotBlank @Pattern(regexp = "(?:[01]\\d|2[0-3]):[0-5]\\d") String startTime,
            @NotBlank @Pattern(regexp = "(?:[01]\\d|2[0-3]):[0-5]\\d") String endTime,
            @NotBlank String semesterId
    ) {}
}
