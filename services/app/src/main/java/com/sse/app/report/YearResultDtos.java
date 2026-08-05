package com.sse.app.report;

import com.sse.app.report.YearReviewDtos.AnnualSubjectResult;
import com.sse.app.report.YearReviewDtos.SemesterResult;

import java.time.Instant;
import java.util.List;

public final class YearResultDtos {
    private YearResultDtos() {}

    public record PublishYearResultRequest(boolean confirmed, String reason) {}

    public record WithdrawYearResultRequest(boolean confirmed, String reason) {}

    public record YearResultPublicationStatus(
            String academicYearId,
            String academicYearName,
            String classId,
            String classCode,
            int totalStudents,
            int finalizedStudents,
            boolean readyToPublish,
            boolean published,
            String publicationState,
            int publicationVersion,
            String publishedByName,
            Instant publishedAt,
            String withdrawnByName,
            Instant withdrawnAt,
            String withdrawalReason) {}

    public record PublishYearResultResponse(
            YearResultPublicationStatus publication,
            int notificationsQueued,
            boolean newlyPublished) {}

    public record WithdrawYearResultResponse(
            YearResultPublicationStatus publication,
            int notificationsQueued,
            boolean newlyWithdrawn) {}

    public record StudentYearResult(
            String summaryId,
            String academicYearId,
            String academicYearName,
            String classId,
            String classCode,
            String className,
            String studentId,
            String studentCode,
            String studentName,
            Double yearlyAverage,
            Double attendanceRate,
            String conductGrade,
            String result,
            String reason,
            String progressionStatus,
            String nextClassId,
            String nextClassCode,
            List<SemesterResult> semesters,
            List<AnnualSubjectResult> subjects,
            Instant finalizedAt,
            Instant publishedAt) {}

    public record YearResultFile(String filename, String contentType, byte[] content) {}

    public record BatchYearResultRequest(
            java.util.List<String> classIds,
            boolean confirmed,
            String reason) {}

    public record BatchYearResultResponse(
            int requestedClasses,
            int changedClasses,
            int affectedStudents,
            java.util.List<YearResultPublicationStatus> classes) {}
}
