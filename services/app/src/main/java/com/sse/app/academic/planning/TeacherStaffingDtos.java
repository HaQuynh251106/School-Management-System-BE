package com.sse.app.academic.planning;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

import java.math.BigDecimal;
import java.util.List;

public final class TeacherStaffingDtos {
    private TeacherStaffingDtos() {}

    public record StaffingPolicyRequest(
            @NotBlank String schoolType,
            @Min(1) @Max(30) int weeklyTeachingNorm,
            @Min(1) @Max(52) int teachingWeeks) {}

    public record StaffingPolicyDto(
            String academicYearId,
            String schoolType,
            String schoolTypeLabel,
            int weeklyTeachingNorm,
            int teachingWeeks,
            BigDecimal teacherClassRatio,
            boolean managementIncluded) {}

    public record SubjectStaffingRow(
            String subjectId,
            String subjectCode,
            String subjectName,
            String subjectType,
            int applicableClassCount,
            int annualPeriods,
            int selectedSemesterPeriods,
            int selectedWeeklyPeriods,
            int minimumTeachersForSemester,
            int minimumTeachersForYear,
            int qualifiedTeacherCount,
            int assignedTeacherCount,
            int shortage,
            boolean countedAsSubjectTeacher) {}

    public record TeacherStaffingAnalysis(
            String academicYearId,
            String semesterId,
            String scopeGradeLevel,
            int schoolClassCount,
            int scopeClassCount,
            int currentActiveTeacherCount,
            int minimumSubjectTeachersForSemester,
            int minimumSubjectTeachersForYear,
            BigDecimal maximumTeacherFte,
            int maximumWholeTeachers,
            boolean withinLegalCeiling,
            boolean sufficientForTimetable,
            int totalAnnualPeriods,
            int totalSelectedSemesterPeriods,
            int totalSelectedWeeklyPeriods,
            StaffingPolicyDto policy,
            List<SubjectStaffingRow> subjects,
            List<String> errors,
            List<String> warnings) {}
}

