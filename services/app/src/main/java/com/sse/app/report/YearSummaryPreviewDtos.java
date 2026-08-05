package com.sse.app.report;

import java.time.Instant;
import java.util.List;

public final class YearSummaryPreviewDtos {
    private YearSummaryPreviewDtos() {}

    public record YearSummaryPreviewResponse(
            String academicYearId,
            String academicYearName,
            String semesterId,
            String semesterName,
            String classId,
            String classCode,
            String className,
            String periodState,
            String periodMessage,
            Instant generatedAt,
            PreviewMetrics metrics,
            List<ExpectedSubject> subjects,
            List<StudentSummaryRow> students,
            List<String> warnings) {}

    public record PreviewMetrics(
            int totalStudents,
            int readyStudents,
            int missingGradeStudents,
            int noAttendanceStudents,
            Double classAverage,
            Double attendanceRate) {}

    public record ExpectedSubject(
            String subjectId,
            String subjectName,
            int requiredGradeCount,
            int expectedGradeCount,
            int enteredGradeCount,
            double completionRate) {}

    public record StudentSummaryRow(
            String studentId,
            String studentCode,
            String studentName,
            Double overallAverage,
            AttendanceSummary attendance,
            List<SubjectSummary> subjects,
            int missingGradeCount,
            boolean ready,
            List<String> warnings) {}

    public record SubjectSummary(
            String subjectId,
            String subjectName,
            Double average,
            int enteredGradeCount,
            int requiredGradeCount,
            List<String> missingCategories) {}

    public record AttendanceSummary(
            int present,
            int late,
            int absentExcused,
            int absentUnexcused,
            int total,
            Double attendanceRate) {}
}
