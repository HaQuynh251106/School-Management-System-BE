package com.sse.app.academic.planning;

import com.sse.app.common.ApiException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;

final class TeacherStaffingRules {
    static final String PUBLIC_REGULAR = "PUBLIC_REGULAR";
    private static final BigDecimal PUBLIC_REGULAR_RATIO = new BigDecimal("2.25");

    private TeacherStaffingRules() {}

    static String normalizeSchoolType(String value) {
        String normalized = value == null || value.isBlank()
                ? PUBLIC_REGULAR
                : value.trim().toUpperCase(Locale.ROOT);
        if (!PUBLIC_REGULAR.equals(normalized)) {
            throw ApiException.badRequest(
                    "Phien ban hien tai chi ho tro truong THPT cong lap thong thuong");
        }
        return normalized;
    }

    static BigDecimal ratioFor(String schoolType) {
        normalizeSchoolType(schoolType);
        return PUBLIC_REGULAR_RATIO;
    }

    static String labelFor(String schoolType) {
        normalizeSchoolType(schoolType);
        return "THPT c\u00f4ng l\u1eadp th\u00f4ng th\u01b0\u1eddng";
    }

    static int minimumTeachers(int requiredWeeklyPeriods, int weeklyTeachingNorm) {
        if (requiredWeeklyPeriods <= 0) return 0;
        if (weeklyTeachingNorm <= 0) {
            throw ApiException.badRequest("Dinh muc tiet day phai lon hon 0");
        }
        return (requiredWeeklyPeriods + weeklyTeachingNorm - 1) / weeklyTeachingNorm;
    }

    static BigDecimal maximumTeacherFte(int classCount, BigDecimal ratio) {
        return ratio.multiply(BigDecimal.valueOf(Math.max(0, classCount)))
                .setScale(2, RoundingMode.HALF_UP);
    }

    static int maximumWholeTeachers(int classCount, BigDecimal ratio) {
        return maximumTeacherFte(classCount, ratio)
                .setScale(0, RoundingMode.FLOOR).intValue();
    }
}
