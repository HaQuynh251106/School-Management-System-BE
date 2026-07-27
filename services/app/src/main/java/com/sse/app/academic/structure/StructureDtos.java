package com.sse.app.academic.structure;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.time.LocalDate;

/** Request DTO cho phân hệ cơ cấu đào tạo (A2). */
public final class StructureDtos {
    private StructureDtos() {}

    public record CreateAcademicYearRequest(
            String id, @NotBlank String code, String name,
            @NotNull LocalDate startDate, @NotNull LocalDate endDate,
            @Pattern(regexp = "PLANNED|ACTIVE|CLOSED") String status) {}

    public record UpdateAcademicYearRequest(
            @NotBlank String code, String name,
            @NotNull LocalDate startDate, @NotNull LocalDate endDate) {}

    public record CreateSemesterRequest(
            String id, @NotBlank String academicYearId, @NotBlank String code, String name,
            @Min(1) @Max(4) Integer sequence,
            @NotNull LocalDate startDate, @NotNull LocalDate endDate,
            @Pattern(regexp = "PLANNED|ACTIVE|CLOSED") String status) {}

    public record UpdateSemesterRequest(
            @NotBlank String academicYearId, @NotBlank String code, String name,
            @Min(1) @Max(4) Integer sequence,
            @NotNull LocalDate startDate, @NotNull LocalDate endDate) {}

    public record ChangeStatusRequest(
            @NotBlank @Pattern(regexp = "PLANNED|ACTIVE|CLOSED") String status) {}

    public record CreateClassRequest(
            String id, @NotBlank String code, String name, @NotBlank String gradeLevel,
            String academicYearId, String homeroomTeacherId,
            @Pattern(regexp = "MORNING|AFTERNOON") String studyShift,
            @Min(1) @Max(100) Integer capacity,
            String roomId) {}

    public record UpdateClassRequest(
            @NotBlank String code, String name, @NotBlank String gradeLevel,
            @NotBlank String academicYearId,
            @Pattern(regexp = "MORNING|AFTERNOON") String studyShift,
            @Min(1) @Max(100) Integer capacity,
            String roomId) {}

    public record AssignHomeroomTeacherRequest(@NotBlank String teacherId) {}

    public record CreateSubjectRequest(String id, @NotBlank String code, @NotBlank String name,
                                       Double coefficient) {}

    public record UpdateSubjectRequest(String code, @NotBlank String name, Double coefficient) {}

    public record CreateRoomRequest(String id, @NotBlank String code, String name,
                                    @Min(1) @Max(1000) Integer capacity,
                                    Boolean supportsMorning, Boolean supportsAfternoon) {}

    public record UpdateRoomRequest(@NotBlank String code, String name,
                                    @Min(1) @Max(1000) Integer capacity,
                                    Boolean supportsMorning, Boolean supportsAfternoon) {}

}
