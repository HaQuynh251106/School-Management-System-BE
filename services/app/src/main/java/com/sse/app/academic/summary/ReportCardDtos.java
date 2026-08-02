package com.sse.app.academic.summary;

import jakarta.validation.constraints.NotBlank;

import java.util.List;

public final class ReportCardDtos {
    private ReportCardDtos() {}

    public record SubjectResult(
            String subjectId, String subjectName, Double semesterOneAverage,
            Double semesterTwoAverage, Double annualAverage, boolean complete) {}

    public record AttendanceSummary(
            int present, int excusedAbsence, int unexcusedAbsence, int late, int total) {}

    public record ReportCardView(
            String id, String academicYearId, String academicYearCode, String studentId,
            String studentCode, String studentName, String classId, String classCode,
            String status, String homeroomTeacherId, String homeroomTeacherName,
            String homeroomComment, Double semesterOneAverage, Double semesterTwoAverage,
            Double annualAverage, String conductGrade, String promotionStatus,
            String missingRequirements, int subjectCount, List<SubjectResult> subjects,
            AttendanceSummary attendance, String verificationCode,
            String submittedAt, String approvedAt, String lockedAt, String publishedAt,
            boolean editableByHomeroom, List<ReportCardAudit> audits) {}

    public record ReportCardListItem(
            String id, String studentId, String studentCode, String studentName,
            String classId, String classCode, String status, int subjectCount,
            Double annualAverage, String conductGrade, String promotionStatus,
            String missingRequirements, String updatedAt) {}

    /** Tổng quan nhẹ theo phạm vi niên khóa/năm học, không trả danh sách học sinh. */
    public record ReportCardScopeOverview(
            String academicYearId, String cohortId, int classCount, long studentCount,
            long draftCount, long submittedCount, long approvedCount, long lockedCount,
            long publishedCount, long incompleteCount, double completionPercent,
            double publishedPercent) {}

    /** Một lớp trong màn hình điều hướng học bạ phân cấp. */
    public record ReportCardClassSummary(
            String classId, String classCode, String className, String gradeLevel,
            String cohortId, String homeroomTeacherId, String homeroomTeacherName,
            long studentCount, long draftCount, long submittedCount, long approvedCount,
            long lockedCount, long publishedCount, long incompleteCount,
            double completionPercent, double publishedPercent) {}

    public record HomeroomUpdateRequest(@NotBlank String conductGrade, @NotBlank String homeroomComment) {}
    public record TransitionRequest(String note) {}
    public record ReopenRequest(@NotBlank String reason) {}

    public record ReportCardAudit(
            String id, String action, String fromStatus, String toStatus,
            String note, String actorId, String actorName, String createdAt) {}
}
