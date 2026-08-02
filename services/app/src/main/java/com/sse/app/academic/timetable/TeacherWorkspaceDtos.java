package com.sse.app.academic.timetable;

import java.time.LocalDate;
import java.util.List;

/** Ngữ cảnh điều hướng của riêng giáo viên, được tính ở backend để FE không tự suy đoán quyền. */
public final class TeacherWorkspaceDtos {
    private TeacherWorkspaceDtos() {}

    public record HomeroomClass(String id, String code, String name, int studentCount) {}

    public record WorkspaceContext(
            boolean homeroomTeacher,
            List<HomeroomClass> homeroomClasses,
            int teachingClassCount,
            boolean examResponsibilities,
            int invigilationDutyCount,
            int gradingDutyCount,
            int pendingReviewCount,
            String semesterId,
            String semesterName,
            boolean loadRegistrationVisible,
            boolean loadRegistrationOpen,
            boolean loadRegistrationEditable,
            LocalDate loadRegistrationOpensOn,
            LocalDate loadRegistrationClosesOn,
            String loadRegistrationStatus) {}

    public record LoadRegistrationWindow(
            String semesterId,
            String semesterName,
            LocalDate opensOn,
            LocalDate closesOn,
            boolean open) {}
}
