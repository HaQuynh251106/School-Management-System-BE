package com.sse.app.academic.exam;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

public final class ExamDtos {
    private ExamDtos() {}

    public record ExamPeriodRequest(
            @NotBlank String code,
            @NotBlank String name,
            @NotBlank String academicYearId,
            @NotBlank String semesterId,
            @NotBlank String examType,
            @NotEmpty List<String> gradeLevels,
            boolean allowSubjectTeacherProctor,
            @NotNull LocalDate startDate,
            @NotNull LocalDate endDate) {}

    public record NewVersionRequest(@NotBlank String reason) {}

    public record RecallVersionRequest(@NotBlank String reason) {}

    public record AutoGenerateRequest(
            @NotEmpty List<LocalDate> examDates,
            @NotEmpty List<LocalTime> startTimes) {}

    public record SessionRequest(
            @NotBlank String sourceAssessmentPlanId,
            @NotNull LocalDate examDate,
            @NotNull LocalTime startTime,
            String scheduleDeviationReason,
            String notes) {}

    public record RoomAssignmentRequest(
            @NotBlank String roomId,
            @NotBlank String primaryProctorId,
            @NotBlank String backupProctorId) {}

    public record TeacherUnavailabilityRequest(
            @NotBlank String teacherId,
            @NotNull LocalDate unavailableDate,
            LocalDate endDate,
            LocalTime startTime,
            LocalTime endTime,
            String unavailabilityType,
            @NotBlank String reason) {}

    public record ExamPeriodResponse(
            String id, String code, String name, String academicYearId,
            String academicYearName, String semesterId, String semesterName,
            String examType, String status, List<String> gradeLevels,
            boolean allowSubjectTeacherProctor, LocalDate startDate,
            LocalDate endDate, String publishedVersionId, int latestVersion,
            String createdBy, String createdByName, boolean canDelete,
            String deleteBlockedReason, Instant createdAt, Instant updatedAt) {}

    public record ExamVersionResponse(
            String id, String examPeriodId, int versionNo, String status,
            String basedOnVersionId, String changeReason, String createdBy,
            String createdByName, Instant createdAt, String publishedBy,
            String publishedByName, Instant publishedAt,
            Instant contentUpdatedAt, Instant lastValidatedAt,
            boolean validationCurrent, Integer lastValidationErrorCount,
            Integer lastValidationWarningCount) {}

    public record ExamStudentResponse(
            String studentId, String studentCode, String studentName,
            String classId, String classCode, int seatNo) {}

    public record ExamRoomResponse(
            String id, String roomId, String roomCode, String roomName,
            int capacity, String primaryProctorId, String primaryProctorName,
            String backupProctorId, String backupProctorName,
            List<ExamStudentResponse> students) {}

    public record ExamSessionResponse(
            String id, String sourceAssessmentPlanId,
            String sourceTrainingPlanId, Integer sourcePlanVersion,
            String sourcePlanName, String sourcePlanStatus,
            String assessmentName, String assessmentType, int assessmentWeek,
            String assessmentForm, LocalDate plannedStartDate,
            LocalDate plannedEndDate, String sourceSyncStatus,
            Instant sourceSyncedAt, String scheduleDeviationReason,
            String subjectId, String subjectCode, String subjectName,
            String gradeLevel, LocalDate examDate, LocalTime startTime,
            LocalTime endTime, int durationMinutes, String notes,
            int studentCount, List<ExamRoomResponse> rooms) {}

    public record ExamVersionDetail(
            ExamPeriodResponse period, ExamVersionResponse version,
            List<ExamSessionResponse> sessions, ExamValidationResponse validation,
            List<TeacherUnavailabilityResponse> teacherUnavailability,
            ExamVersionDiff versionDiff) {}

    public record ExamVersionChange(
            String type, String label, String beforeValue, String afterValue) {}

    public record ExamVersionDiff(
            boolean comparisonAvailable, String baseVersionId, Integer baseVersionNo,
            boolean hasChanges, int totalChanges, int addedSessions,
            int removedSessions, int changedSessions, int changedRooms,
            int changedProctors, int changedStudents,
            List<ExamVersionChange> changes) {}

    public record ExamValidationIssue(
            String severity, String code, String message,
            String sessionId, String roomAssignmentId) {}

    public record ExamValidationResponse(
            boolean valid, int sessionCount, int roomCount, int studentCount,
            int errorCount, int warningCount, List<ExamValidationIssue> issues) {}

    public record TeacherUnavailabilityResponse(
            String id, String teacherId, String teacherName,
            LocalDate unavailableDate, LocalDate endDate, LocalTime startTime,
            LocalTime endTime, String unavailabilityType, String status,
            String reason, String createdByName, Instant createdAt,
            int affectedSessionCount) {}

    public record PublishedExamView(
            String periodId, String periodName, String examType,
            String semesterName, String subjectId, String subjectName,
            String gradeLevel, LocalDate examDate, LocalTime startTime,
            LocalTime endTime, int durationMinutes, String roomCode,
            int seatNo, String primaryProctorName, String backupProctorName,
            String dutyRole, String studentName, String studentCode) {}
}
