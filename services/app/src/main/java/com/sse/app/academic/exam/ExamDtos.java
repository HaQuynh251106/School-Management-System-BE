package com.sse.app.academic.exam;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.LocalDate;
import java.time.Instant;
import java.util.List;
import java.util.Map;

public final class ExamDtos {
    private ExamDtos() {}

    public record SavePeriodRequest(String id, @NotBlank String code, @NotBlank String name,
            @NotBlank String academicYearId, @NotBlank String semesterId, String gradeLevel,
            @NotNull LocalDate startDate, @NotNull LocalDate endDate) {}
    public record SaveScheduleRequest(String id, @NotBlank String subjectId,
            @NotEmpty List<@NotBlank String> classIds, @NotNull LocalDate examDate,
            @NotBlank @Pattern(regexp = "(?:[01]\\d|2[0-3]):[0-5]\\d") String startTime,
            @Min(15) @Max(480) int durationMinutes, String notes) {}
    public record SaveRoomRequest(String id, @NotBlank String roomCode, @Min(1) @Max(1000) int capacity,
            String proctorOneId, String proctorTwoId) {}
    public record SaveGradingAssignmentRequest(@NotBlank String classId, @NotBlank String teacherId) {}
    public record EligibleGrader(String teacherId, String teacherCode, String teacherName) {}
    public record AllocateCandidatesRequest(@NotBlank String classId) {}
    public record AutoPlanRequest(
            @NotEmpty List<@NotBlank String> subjectIds,
            @NotBlank @Pattern(regexp = "(?:[01]\\d|2[0-3]):[0-5]\\d") String startTime,
            @Min(15) @Max(480) int durationMinutes,
            @NotNull Boolean apply,
            @Size(max = 120) String idempotencyKey) {}
    public record AutoPlanAllocation(String classId, String classCode, int studentCount,
            String roomCode, int capacity, String proctorId, String proctorName,
            String graderId, String graderName) {}
    public record AutoPlanSchedule(String scheduleId, String subjectId, String subjectName,
            LocalDate examDate, String startTime, int durationMinutes,
            List<AutoPlanAllocation> allocations) {}
    public record AutoPlanResult(String examPeriodId, String planKey, String requestHash,
            boolean applied, int scheduleCount, List<AutoPlanSchedule> schedules,
            List<String> blockers, Map<String, Object> constraints) {}
    public record ResultEntry(@NotBlank String studentId, @Min(0) @Max(10) Double score, String note, Long expectedVersion) {}
    public record SaveResultsRequest(@NotBlank String scheduleId, @NotNull @Size(min = 1) List<@Valid ResultEntry> entries) {}
    public record CreateReviewRequest(@NotBlank String resultId, @NotBlank @Size(min = 10, max = 2000) String reason) {}
    public record ResolveReviewRequest(@NotBlank @Pattern(regexp = "APPROVED|REJECTED") String status,
            @NotBlank @Size(min = 5, max = 2000) String resolution, @Min(0) @Max(10) Double resolvedScore) {}
    public record PeriodSummary(ExamPeriod period, int scheduleCount, int roomCount, int candidateCount,
            int resultCount, int pendingReviewCount) {}
    public record CandidateResultRow(ExamCandidate candidate, List<ExamResult> results) {}
    public record ExamAgendaItem(String id, String taskType, String taskLabel,
            String examPeriodId, String examPeriodName, int scheduleRevision,
            String scheduleId, String subjectId, String subjectName, LocalDate examDate,
            String startTime, int durationMinutes, String notes, String roomCode,
            String studentId, String studentName, String classCode, String candidateNo,
            Integer seatNo, String proctorNames, String status) {}
    public record TeacherExamCandidateRow(String candidateId, String studentId, String studentName,
            String studentCode, String candidateNo, Integer seatNo, String roomCode,
            String resultId, Double score, String note, String resultStatus, Long version) {}
    public record TeacherGradingTask(String examPeriodId, String examPeriodName, String scheduleId,
            String subjectId, String subjectName, String classId, String classCode,
            LocalDate examDate, String startTime, Instant scoreEntryOpensAt,
            boolean scoreEntryAvailable, boolean scoreEntryLocked,
            List<TeacherExamCandidateRow> candidates) {}
    public record StudentExamResultView(String resultId, String examPeriodId, String examPeriodName,
            String scheduleId, String subjectId, String subjectName, Double score, String note,
            String resultStatus, String reviewId, String reviewStatus, String reviewReason,
            String reviewResolution, Double resolvedScore) {}
}
