package com.sse.app.academic.planning;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TeacherStaffingRulesTest {

    @Test
    void tenClassesWithTwoPeriodsPerWeekNeedTwoTeachers() {
        assertEquals(2, TeacherStaffingRules.minimumTeachers(20, 17));
    }

    @Test
    void thirtyClassesWithTwoPeriodsPerWeekNeedFourTeachers() {
        assertEquals(4, TeacherStaffingRules.minimumTeachers(60, 17));
    }

    @Test
    void regularPublicSchoolUsesConservativeWholeTeacherCeiling() {
        BigDecimal ratio = TeacherStaffingRules.ratioFor("PUBLIC_REGULAR");
        assertEquals(new BigDecimal("67.50"),
                TeacherStaffingRules.maximumTeacherFte(30, ratio));
        assertEquals(67, TeacherStaffingRules.maximumWholeTeachers(30, ratio));
    }

    @Test
    void missingSchoolTypeDefaultsToPublicRegular() {
        assertEquals("PUBLIC_REGULAR", TeacherStaffingRules.normalizeSchoolType(null));
        assertEquals(new BigDecimal("2.25"), TeacherStaffingRules.ratioFor(null));
    }

    @Test
    void rejectsUnsupportedSchoolTypes() {
        assertThrows(RuntimeException.class,
                () -> TeacherStaffingRules.ratioFor("PUBLIC_UNKNOWN"));
        assertThrows(RuntimeException.class,
                () -> TeacherStaffingRules.ratioFor("ETHNIC_BOARDING"));
        assertThrows(RuntimeException.class,
                () -> TeacherStaffingRules.ratioFor("SPECIALIZED"));
    }
}
