package com.sse.app.academic.timetable;

import com.sse.app.common.ApiException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AutomaticTimetableServiceTest {

    @Test
    void defaultsToAllSupportedSchoolDaysWhenTheOptionIsOmitted() {
        assertThat(AutomaticTimetableService.normalizeAllowedDays(null))
                .containsExactly("MON", "TUE", "WED", "THU", "FRI", "SAT");
    }

    @Test
    void normalizesDeduplicatesAndKeepsCalendarOrder() {
        assertThat(AutomaticTimetableService.normalizeAllowedDays(
                List.of("fri", "MON", "mon", "WED")))
                .containsExactly("MON", "WED", "FRI");
    }

    @Test
    void rejectsEmptyOrUnsupportedSchoolDays() {
        assertThatThrownBy(() -> AutomaticTimetableService.normalizeAllowedDays(List.of()))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Phải chọn ít nhất một ngày học");

        assertThatThrownBy(() -> AutomaticTimetableService.normalizeAllowedDays(List.of("SUN")))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Ngày học không hợp lệ");
    }
}
