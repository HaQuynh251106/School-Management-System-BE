package com.sse.app.report;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.time.Instant;
import java.util.List;

public final class AcademicEnrollmentDtos {
    private AcademicEnrollmentDtos() {}

    public record EnrollmentView(
            String id,
            String academicYearId,
            String classId,
            String classCode,
            String studentId,
            String studentCode,
            String studentName,
            String status,
            String enrollmentType,
            Instant enrolledAt) {}

    public record StudentCandidate(
            String id,
            String studentCode,
            String fullName,
            String currentClassId,
            String currentClassName) {}

    public record BulkEnrollmentRequest(
            @NotBlank String academicYearId,
            @NotBlank String classId,
            @NotEmpty List<String> studentIds,
            @NotBlank String reason) {}

    public record RemoveEnrollmentRequest(@NotBlank String reason) {}

    public record BulkEnrollmentResult(
            int assigned,
            int transferred,
            int unchanged,
            String classId) {}
}
