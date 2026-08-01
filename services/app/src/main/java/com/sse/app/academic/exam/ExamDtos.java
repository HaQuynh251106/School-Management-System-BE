package com.sse.app.academic.exam;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.LocalDate;
import java.time.Instant;
import java.util.List;

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
    public record BatchSaveRoomsRequest(@NotEmpty List<@NotBlank String> roomCodes) {}
    public record ExamRoomAvailability(String roomId, String roomCode, String roomName, int capacity,
            String roomType, boolean available, boolean selected, String reason,
            String conflictingSubject, String conflictingStartTime) {}
    public record ExamDayPolicy(LocalDate examDate, boolean regularClassesSuspended,
            String title, String description) {}
    public record PreviewOrganizationPlanRequest(@Min(1) @Max(1000) int maxCandidatesPerRoom,
            @Min(1) @Max(4) int studentsPerDesk, Boolean includeSecondProctor) {}
    public record OrganizationPlanRoom(String roomId, String roomCode, int physicalCapacity,
            int effectiveCapacity, int deskCount, String proctorOneId, String proctorOneName,
            String proctorTwoId, String proctorTwoName, int candidateCount, boolean ready) {}
    public record OrganizationPlanCandidate(String studentId, String studentName, String studentCode,
            String classId, String classCode, String candidateNo, String roomId, String roomCode,
            int seatNo, int deskNo, int seatPosition) {}
    public record OrganizationPlanView(String id, String scheduleId, String status,
            int maxCandidatesPerRoom, int studentsPerDesk, boolean includeSecondProctor,
            int candidateCount, int roomCount, int effectiveCapacity, int assignedCount,
            int missingAssignmentCount, String warningSummary, Instant createdAt, Instant appliedAt,
            Instant undoneAt, List<OrganizationPlanRoom> rooms, List<OrganizationPlanCandidate> candidates) {}
    public record PreviewSeatingPlanRequest(List<String> roomIds) {}
    public record OrganizationReadiness(int candidateCount, int allocatedCount, int totalCapacity,
            int proctoredCapacity, int roomCount, int proctoredRoomCount, int missingSeats,
            int missingCandidates, boolean roomsReady, boolean candidatesReady, List<String> warnings) {}
    public record SeatingPlanRoom(String roomId, String roomCode, int capacity, int assignedCount,
            int remainingCapacity, boolean hasMainProctor, List<String> classCodes) {}
    public record SeatingPlanClass(String classId, String classCode, int candidateCount,
            int assignedCount, int roomCount, List<String> roomCodes) {}
    public record SeatingPlanCandidate(String studentId, String studentName, String studentCode,
            String classId, String classCode, String candidateNo, String roomId, String roomCode,
            Integer seatNo, boolean assigned) {}
    public record SeatingPlanView(String id, String scheduleId, String status, int candidateCount,
            int totalCapacity, int assignedCount, int unassignedCount, String warningSummary,
            Instant createdAt, Instant appliedAt, Instant undoneAt, List<SeatingPlanRoom> rooms,
            List<SeatingPlanClass> classes, List<SeatingPlanCandidate> candidates) {}
    public record PreviewProctorPlanRequest(List<String> lockedRoomIds, Boolean includeSecondProctor) {}
    public record EligibleProctor(String teacherId, String teacherCode, String teacherName,
            int currentDutyCount, boolean teachesExamSubject, String recommendation) {}
    public record ProctorPlanItem(String roomId, String roomCode, boolean locked,
            String previousProctorOneId, String previousProctorOneName,
            String previousProctorTwoId, String previousProctorTwoName,
            String proposedProctorOneId, String proposedProctorOneName,
            String proposedProctorTwoId, String proposedProctorTwoName,
            String status, String message, Integer proctorOneDutyCount, Integer proctorTwoDutyCount) {}
    public record ProctorPlanView(String id, String scheduleId, String status, boolean includeSecondProctor,
            int roomCount, int readyRoomCount, int missingAssignmentCount, String warningSummary,
            Instant createdAt, Instant appliedAt, Instant undoneAt, List<ProctorPlanItem> items) {}
    public record SaveGradingAssignmentRequest(@NotBlank String classId, @NotBlank String teacherId) {}
    public record EligibleGrader(String teacherId, String teacherCode, String teacherName) {}
    public record AllocateCandidatesRequest(@NotBlank String classId) {}
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
