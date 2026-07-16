package com.sse.app.academic.structure;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import java.time.LocalDate;

/** Request DTO cho phân hệ cơ cấu đào tạo (A2). */
public final class StructureDtos {
    private StructureDtos() {}

    public record CreateAcademicYearRequest(
            String id, @NotBlank String code, String name,
            LocalDate startDate, LocalDate endDate, String status) {}

    public record CreateSemesterRequest(
            String id, @NotBlank String academicYearId, @NotBlank String code, String name,
            Integer sequence, LocalDate startDate, LocalDate endDate, String status) {}

    public record CreateClassRequest(
            String id, @NotBlank String code, String name, @NotBlank String gradeLevel,
            String academicYearId, String homeroomTeacherId,
            @Min(1) @Max(100) Integer capacity) {}

    public record AssignHomeroomTeacherRequest(@NotBlank String teacherId) {}

    public record CreateSubjectRequest(String id, @NotBlank String code, @NotBlank String name,
                                       Double coefficient) {}

    public record UpdateSubjectRequest(@NotBlank String name, Double coefficient) {}

    public record CreateRoomRequest(String id, @NotBlank String code, String name, Integer capacity) {}

    public record CreateHolidayRequest(String id, LocalDate date, @NotBlank String name, String description) {}
}
