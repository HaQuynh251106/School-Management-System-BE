package com.sse.app.academic.timetable;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;
import java.util.List;

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

    public record GenerateScheduleRequest(
            String id,
            @NotBlank String academicYearId,
            @NotBlank String semesterId,
            String scopeGradeLevel,
            String name,
            List<String> teachingDays,
            Integer firstPeriod,
            Integer lastPeriod,
            Integer maxPeriodsPerDay,
            Integer maxProgressGapDays,
            Integer maxProgressGapPeriods,
            Integer maxCurriculumGapLessons,
            Integer solveSeconds
    ) {}

    public record MoveDraftSlotRequest(
            @NotBlank String dayOfWeek,
            @NotNull Integer periodNo,
            String roomId
    ) {}

    public record ScheduleIssue(
            String level, String code, String message,
            String classId, String teacherId, String subjectId,
            String dayOfWeek, Integer periodNo
    ) {}

    public record ScheduleValidation(
            boolean valid,
            int requiredPeriods,
            int scheduledPeriods,
            int errorCount,
            int warningCount,
            List<ScheduleIssue> issues
    ) {}

    public record GenerationResult(
            TimetableSchedule schedule,
            ScheduleValidation validation
    ) {}

    public record GenerationReadiness(
            boolean ready,
            String academicYearId,
            String semesterId,
            String scopeGradeLevel,
            String sourcePlanSummary,
            int classCount,
            int assignmentCount,
            int requiredPeriods,
            List<ScheduleIssue> issues
    ) {}

    public record LessonProgressRequest(
            String id,
            @NotBlank String classId,
            @NotBlank String semesterId,
            @NotBlank String subjectId,
            @NotBlank String curriculumItemId,
            @NotNull LocalDate lessonDate,
            @NotNull Integer plannedPeriods,
            @NotNull Integer completedPeriods,
            String status,
            String notes
    ) {}

    public record ClassProgressRow(
            String classId,
            String classCode,
            int completedPeriods,
            int completedLessons,
            LocalDate latestLessonDate,
            String latestLessonTitle,
            int dayLag,
            int periodLag,
            int lessonLag,
            boolean delayed
    ) {}

    public record ProgressComparison(
            String academicYearId,
            String semesterId,
            String gradeLevel,
            String sourcePlanId,
            Integer sourcePlanVersion,
            String subjectId,
            int maxDayGap,
            int maxTeachingDayGap,
            int maxPeriodGap,
            int maxLessonGap,
            int allowedDayGap,
            int allowedPeriodGap,
            int allowedLessonGap,
            boolean balanced,
            List<ClassProgressRow> classes,
            List<String> warnings
    ) {}

    public record MakeupProposalRequest(
            @NotNull LocalDate fromDate,
            @NotNull LocalDate toDate
    ) {}

    public record ReviewMakeupRequest(@NotBlank String status, String reason) {}
}
