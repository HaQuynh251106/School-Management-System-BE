package com.sse.app.academic.timetable;

import com.sse.app.common.ApiException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AutomaticTimetableTeachingDaysTest {

    @Test
    void fiveDayWeekEndsOnFridayAndKeepsBlocksInsideSelectedDays() {
        List<String> days = AutomaticTimetableService.normalizeDays(
                List.of("FRI", "MON", "THU", "WED", "TUE"));

        assertEquals(List.of("MON", "TUE", "WED", "THU", "FRI"), days);
        assertEquals("FRI", AutomaticTimetableService.lastTeachingDay(days));
        assertEquals(List.of("TUE", "THU", "FRI"),
                AutomaticTimetableService.blockTeachingDays(true, days));
        assertEquals(List.of("MON", "WED", "FRI"),
                AutomaticTimetableService.blockTeachingDays(false, days));
    }

    @Test
    void sixDayWeekEndsOnSaturdayAndUsesAlternatingBlockDays() {
        List<String> days = AutomaticTimetableService.normalizeDays(
                List.of("MON", "TUE", "WED", "THU", "FRI", "SAT"));

        assertEquals("SAT", AutomaticTimetableService.lastTeachingDay(days));
        assertEquals(List.of("TUE", "THU", "SAT"),
                AutomaticTimetableService.blockTeachingDays(true, days));
        assertEquals(List.of("MON", "WED", "FRI"),
                AutomaticTimetableService.blockTeachingDays(false, days));
    }

    @Test
    void rejectsIncompleteOrNonContiguousTeachingWeek() {
        assertThrows(ApiException.class, () ->
                AutomaticTimetableService.normalizeDays(List.of("MON", "TUE", "FRI")));
        assertThrows(ApiException.class, () ->
                AutomaticTimetableService.normalizeDays(
                        List.of("MON", "TUE", "WED", "THU", "SAT")));
    }
}
