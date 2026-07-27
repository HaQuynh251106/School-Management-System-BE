package com.sse.app.academic.summary;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public final class YearEndDtos {
    private YearEndDtos() {}

    public record ConductRequest(
            @Pattern(regexp = "GOOD|FAIR|AVERAGE|WEAK", message = "Hạnh kiểm không hợp lệ")
            String conductGrade) {}

    public record RolloverRequest(
            @NotBlank String nextYearCode,
            String nextYearName,
            @NotNull LocalDate startDate,
            @NotNull LocalDate endDate,
            Boolean createIntakeClasses,
            Boolean activateNextYear) {}

    public record RolloverClassPlan(
            String sourceClassId, String sourceClassCode,
            String targetClassCode, String targetGradeLevel,
            String type, int capacity, String studyShift) {}

    public record RolloverPreview(
            String academicYearId, String academicYearCode, String status,
            int semesterCount, int classCount, int studentCount,
            int readyCount, int incompleteCount, int expectedPromoted,
            int expectedRetained, int expectedGraduated,
            List<RolloverClassPlan> classPlan,
            List<String> blockers) {}

    public record RolloverResult(
            String closedYearId, String nextYearId, String nextYearCode,
            int createdSemesterCount, int createdClassCount,
            int promotedCount, int retainedCount, int graduatedCount,
            boolean nextYearActivated, Instant completedAt) {}
}
