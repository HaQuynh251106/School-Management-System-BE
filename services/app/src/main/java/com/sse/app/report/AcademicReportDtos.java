package com.sse.app.report;

import java.time.Instant;
import java.util.List;

public final class AcademicReportDtos {
    private AcademicReportDtos() {}

    public record AcademicReportFilter(String academicYearId, String semesterId,
                                       String gradeLevel, String classId, String subjectId) {}
    public record AcademicStudentRow(String studentId, String studentCode, String studentName,
                                     String classId, String className, int gradeEntries,
                                     Double averageScore, int present, int late,
                                     int absentExcused, int absentUnexcused,
                                     Double attendanceRate, int assignments,
                                     int submittedAssignments, int gradedAssignments) {}
    public record AcademicSubjectRow(String subjectId, String subjectName,
                                     int gradeEntries, int studentCount, Double averageScore) {}
    public record AcademicReportSummary(int studentCount, int classCount, int subjectCount,
                                        int gradeEntries, Double averageScore,
                                        int attendanceEntries, Double attendanceRate,
                                        int assignments, int submittedAssignments,
                                        int gradedAssignments) {}
    public record AcademicReportResponse(AcademicReportFilter filter,
                                         AcademicReportSummary summary,
                                         List<AcademicStudentRow> students,
                                         List<AcademicSubjectRow> subjects,
                                         Instant generatedAt) {}
    public record AcademicReportFile(String filename, String contentType, byte[] content) {}
}
