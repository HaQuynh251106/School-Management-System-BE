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

    private TimetableRulePolicy.SlotView slot(String id, String start, String end, int period) {
        return new TimetableRulePolicy.SlotView(id, "class-1", "math", "teacher-" + id,
                "room-" + id, "MON", period, start, end, "semester-1");
    }
}
