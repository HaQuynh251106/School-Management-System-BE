package com.sse.app.academic.timetable;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public final class TimetableDtos {
    private TimetableDtos() {}

    public record CreateSlotRequest(
            String id,
            @NotBlank String classId,
            @NotBlank String subjectId,
            @NotBlank String teacherId,
            String roomCode,
            @NotBlank String dayOfWeek,
            @NotNull Integer periodNo,
            String startTime,
            String endTime,
            String semesterId,
            Boolean overrideHoliday
    ) {}
}
