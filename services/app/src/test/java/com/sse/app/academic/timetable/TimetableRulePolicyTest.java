package com.sse.app.academic.timetable;

import com.sse.app.common.ApiException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TimetableRulePolicyTest {
    @Test
    void rejectsThirdPeriodOfTheSameSubjectForAClassAndDay() {
        List<TimetableRulePolicy.SlotView> existing = List.of(
                slot("one", "07:00", "07:45", 1),
                slot("two", "07:50", "08:35", 2)
        );

        ApiException error = assertThrows(ApiException.class,
                () -> TimetableRulePolicy.assertCanAdd(slot("three", "08:50", "09:35", 3), existing, null));
        assertTrue(error.getMessage().contains("tối đa 2 tiết"));
    }

    @Test
    void publicationValidationRejectsOverloadAndAcceptsTwoPeriods() {
        TimetableRulePolicy.Validation valid = TimetableRulePolicy.validate(List.of(
                slot("one", "07:00", "07:45", 1),
                slot("two", "07:50", "08:35", 2)
        ));
        TimetableRulePolicy.Validation invalid = TimetableRulePolicy.validate(List.of(
                slot("one", "07:00", "07:45", 1),
                slot("two", "07:50", "08:35", 2),
                slot("three", "08:50", "09:35", 3)
        ));

        assertTrue(valid.valid());
        assertFalse(invalid.valid());
        assertTrue(invalid.summary().contains("vượt 2 tiết"));
    }

    @Test
    void rejectsSaturdayAndSixthPeriod() {
        ApiException saturday = assertThrows(ApiException.class,
                () -> TimetableRulePolicy.assertCanAdd(slot("sat", "SAT", 1), List.of(), null));
        ApiException sixthPeriod = assertThrows(ApiException.class,
                () -> TimetableRulePolicy.assertCanAdd(slot("six", "MON", 6), List.of(), null));

        assertTrue(saturday.getMessage().contains("Thứ 2 đến Thứ 6"));
        assertTrue(sixthPeriod.getMessage().contains("5 tiết"));
    }

    @Test
    void publicationValidationRejectsGapInsideSchoolDay() {
        TimetableRulePolicy.Validation invalid = TimetableRulePolicy.validate(List.of(
                slot("one", "07:00", "07:45", 1),
                new TimetableRulePolicy.SlotView("three", "class-1", "literature", "teacher-three",
                        "room-three", "MON", 3, "08:50", "09:35", "semester-1")
        ));

        assertFalse(invalid.valid());
        assertTrue(invalid.summary().contains("ngắt quãng"));
    }

    private TimetableRulePolicy.SlotView slot(String id, String start, String end, int period) {
        return new TimetableRulePolicy.SlotView(id, "class-1", "math", "teacher-" + id,
                "room-" + id, "MON", period, start, end, "semester-1");
    }

    private TimetableRulePolicy.SlotView slot(String id, String day, int period) {
        return new TimetableRulePolicy.SlotView(id, "class-1", "math", "teacher-" + id,
                "room-" + id, day, period, "07:00", "07:45", "semester-1");
    }
}
