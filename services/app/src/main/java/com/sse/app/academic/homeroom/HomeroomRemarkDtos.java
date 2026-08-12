package com.sse.app.academic.homeroom;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;

public final class HomeroomRemarkDtos {
    private HomeroomRemarkDtos() {}

    public record SaveRemarkRequest(
            @NotBlank String semesterId,
            @NotBlank @Size(max = 4000) String body,
            boolean publish) {}

    public record RemarkResponse(
            String id,
            String studentId,
            String studentName,
            String classId,
            String classCode,
            String academicYearId,
            String semesterId,
            String semesterName,
            String teacherId,
            String teacherName,
            String body,
            String status,
            Instant publishedAt,
            Instant updatedAt) {}
}
