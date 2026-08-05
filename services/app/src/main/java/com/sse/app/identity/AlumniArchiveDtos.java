package com.sse.app.identity;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public final class AlumniArchiveDtos {
    private AlumniArchiveDtos() {}

    public record CohortArchiveSummary(
            String id, String code, String name, Integer entryYear, Integer graduationYear,
            String status, Instant completedAt, long studentCount, long graduatedCount,
            long retainedCount, long transferredCount, double completionRate,
            Double averageScore, long goodConductCount) {}

    public record ClassDistribution(
            String classId, String classCode, long studentCount, Double averageScore) {}

    public record CohortArchiveOverview(
            CohortArchiveSummary cohort, long maleCount, long femaleCount, long otherGenderCount,
            long excellentCount, long goodAcademicCount, long averageAcademicCount, long weakAcademicCount,
            long goodConductCount, long fairConductCount, long averageConductCount, long weakConductCount,
            List<ClassDistribution> classes) {}

    public record CohortStudentListItem(
            String id, String studentCode, String fullName, LocalDate dateOfBirth, String gender,
            String email, String finalClassId, String finalClassCode, String graduationAcademicYearId,
            String graduationAcademicYearCode, String finalYearResult, String graduationResult,
            Double annualAverage, String academicPerformance, String conductGrade,
            String recordStatus, String studentStatus, Instant graduatedAt) {}

    public record EnrollmentHistory(
            String academicYearId, String academicYearCode, String classId, String classCode,
            String gradeLevel, String status, Instant enrolledAt, Instant endedAt) {}

    public record SubjectYearResult(
            String subjectId, String subjectName, Double semesterOneAverage,
            Double semesterTwoAverage, Double annualAverage, boolean complete) {}

    public record AttendanceYearSummary(
            int present, int excusedAbsence, int unexcusedAbsence, int late, int total) {}

    public record AcademicYearRecord(
            String academicYearId, String academicYearCode, String gradeLevel,
            String classId, String classCode, Double semesterOneAverage,
            Double semesterTwoAverage, Double annualAverage, String academicPerformance,
            String conductGrade, String finalYearResult, String missingRequirements,
            String reportCardStatus, String verificationCode, String reportCardPublishedAt,
            AttendanceYearSummary attendance, List<SubjectYearResult> subjects) {}

    public record StudentArchiveProfile(
            String id, String studentCode, String fullName, LocalDate dateOfBirth, String gender,
            String email, String phone, String address, String placeOfBirth, String ethnicity,
            String nationality, String cohortId, String cohortCode, String cohortName,
            Integer entryYear, Integer graduationYear, String cohortStatus,
            String finalClassId, String finalClassCode, String graduationAcademicYearCode,
            String graduationResult, String recordStatus, Instant graduatedAt,
            Double wholeProgramAverage, List<EnrollmentHistory> enrollments,
            List<AcademicYearRecord> academicYears) {}
}
