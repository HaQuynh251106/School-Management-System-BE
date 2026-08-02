package com.sse.app.academic.timetable;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public final class TeachingOperationDtos {
    private TeachingOperationDtos() {}

    public record LessonOccurrence(
            String occurrenceKey, String slotId, LocalDate date, String classId, String classCode,
            String subjectId, String subjectName, int periodNo, String startTime, String endTime,
            String roomCode, String originalTeacherId, String originalTeacherName,
            String effectiveTeacherId, String effectiveTeacherName, String assignmentState,
            String changeRequestId, String diaryId, String diaryStatus) {}

    public record SaveLessonDiaryRequest(
            @Size(max = 500) String topic,
            @Size(max = 4000) String lessonContent,
            @Size(max = 2000) String homework,
            @Size(max = 2000) String classNote,
            @Size(max = 1000) String attendanceSummary,
            @NotBlank String status) {}

    public record ChangeRequestCreate(
            @NotBlank String slotId,
            @NotNull LocalDate occurrenceDate,
            @NotBlank String requestType,
            String substituteTeacherId,
            LocalDate proposedDate,
            Integer proposedPeriodNo,
            String proposedStartTime,
            String proposedEndTime,
            String proposedRoomCode,
            @NotBlank @Size(max = 2000) String reason) {}

    public record ChangeDecision(@NotNull Boolean approved, @Size(max = 2000) String note) {}

    public record ChangeRequestView(
            String id, String slotId, LocalDate occurrenceDate, String requestType,
            String classId, String classCode, String subjectName,
            String originalTeacherId, String originalTeacherName,
            String substituteTeacherId, String substituteTeacherName,
            LocalDate proposedDate, Integer proposedPeriodNo, String proposedStartTime,
            String proposedEndTime, String proposedRoomCode, String reason, String status,
            String reviewedBy, String reviewerName, String reviewNote, Instant createdAt, Instant reviewedAt) {}

    public record SubstituteCandidate(String id, String fullName, String mainSubject, boolean available, String reason) {}

    public record TeachingWorkspace(List<LessonOccurrence> lessons, List<ChangeRequestView> changes) {}
}
