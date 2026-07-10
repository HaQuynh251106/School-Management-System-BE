package com.sse.app.academic.teaching;

import jakarta.validation.constraints.NotBlank;

import java.time.Instant;

public final class TeachingDtos {
    private TeachingDtos() {}

    public record TeachingAssignmentDto(
            String id,
            String teacherId,
            String teacherName,
            String classId,
            String classCode,
            String subjectId,
            String subjectName,
            String semesterId,
            String status,
            Instant createdAt,
            Instant updatedAt) {}

    public record CreateTeachingAssignmentRequest(
            String id,
            @NotBlank String teacherId,
            @NotBlank String classId,
            @NotBlank String subjectId,
            @NotBlank String semesterId,
            String status) {}

    public record UpdateTeachingAssignmentRequest(
            String teacherId,
            String status) {}
}
