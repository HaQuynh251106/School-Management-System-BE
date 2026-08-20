package com.sse.app.academic.structure;

import jakarta.validation.constraints.NotBlank;

import java.time.LocalDate;
import java.util.List;

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
            String academicYearId, String homeroomTeacherId, String homeRoomId,
            Integer maxStudents) {}

    public record AssignHomeroomTeacherRequest(String homeroomTeacherId) {}
    public record AssignHomeRoomRequest(@NotBlank String homeRoomId) {}

    public record CreateSubjectRequest(
            String id, @NotBlank String code, @NotBlank String name,
            Double coefficient, String requiredRoomType, Boolean active,
            String subjectType, String departmentName,
            String assessmentMethod, String facilityNote) {}

    public record CreateRoomRequest(
            String id, @NotBlank String code, String name,
            Integer capacity, String roomType, Boolean active) {}

    public record CreateHolidayRequest(
            String id, String academicYearId, LocalDate date, LocalDate endDate,
            @NotBlank String name, String description) {}

    public record UpdateAcademicYearRequest(
            @NotBlank String code, String name, LocalDate startDate,
            LocalDate endDate, String status) {}

    public record UpdateSemesterRequest(
            @NotBlank String code, String name, Integer sequence,
            LocalDate startDate, LocalDate endDate, String status) {}

    public record UpdateClassRequest(
            @NotBlank String code, String name, @NotBlank String gradeLevel,
            String homeroomTeacherId, String homeRoomId, Integer maxStudents) {}

    public record ClassCountPlanRequest(
            @NotBlank String academicYearId,
            Integer grade10Count, Integer grade11Count, Integer grade12Count) {}

    public record ClassCountPlanRow(
            String gradeLevel, int currentCount, int targetCount,
            List<String> classesToCreate, List<String> classesToReactivate,
            List<String> classesToDeactivate, List<String> blockingReasons) {}

    public record ClassCountPlanResponse(
            String academicYearId, boolean applicable,
            List<ClassCountPlanRow> grades) {}
}
