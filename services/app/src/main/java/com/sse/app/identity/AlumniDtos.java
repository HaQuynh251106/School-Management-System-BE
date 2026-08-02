package com.sse.app.identity;

import java.time.Instant;
import java.time.LocalDate;

public final class AlumniDtos {
    private AlumniDtos() {}

    public record AlumniClassSummary(
            String classId,
            String classCode,
            String className,
            String cohortId,
            String cohortCode,
            String cohortName,
            String graduationAcademicYearId,
            String graduationAcademicYearCode,
            Long studentCount,
            Double averageScore,
            Long goodConductCount,
            Long activeAccountCount
    ) {}

    public record AlumniRecord(
            String id,
            String studentCode,
            String fullName,
            LocalDate dateOfBirth,
            String gender,
            String email,
            String phone,
            String cohortId,
            String cohortCode,
            String cohortName,
            Integer entryYear,
            Integer graduationYear,
            Instant graduatedAt,
            String graduationAcademicYearId,
            String graduationAcademicYearCode,
            String graduationClassId,
            String graduationClassCode,
            Double annualAverage,
            String conductGrade,
            String accountStatus
    ) {}
}
