package com.sse.app.report;

import com.sse.app.academic.attendance.AttendanceRecord;
import com.sse.app.academic.attendance.AttendanceService;
import com.sse.app.academic.grade.ExamCategory;
import com.sse.app.academic.grade.Grade;
import com.sse.app.academic.grade.GradeService;
import com.sse.app.academic.structure.AcademicYear;
import com.sse.app.academic.structure.SchoolClass;
import com.sse.app.academic.structure.Semester;
import com.sse.app.academic.structure.StructureService;
import com.sse.app.academic.teaching.TeachingAssignmentService;
import com.sse.app.academic.teaching.TeachingDtos.TeachingAssignmentDto;
import com.sse.app.common.ApiException;
import com.sse.app.identity.UserDto;
import com.sse.app.identity.UserService;
import com.sse.app.report.YearSummaryPreviewDtos.YearSummaryPreviewResponse;
import com.sse.app.security.CurrentUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class YearSummaryPreviewServiceTest {

    private StructureService structure;
    private TeachingAssignmentService teachingAssignments;
    private GradeService grades;
    private AttendanceService attendance;
    private UserService users;
    private YearSummaryPreviewService service;

    @BeforeEach
    void setUp() {
        structure = mock(StructureService.class);
        teachingAssignments = mock(TeachingAssignmentService.class);
        grades = mock(GradeService.class);
        attendance = mock(AttendanceService.class);
        users = mock(UserService.class);
        service = new YearSummaryPreviewService(structure, teachingAssignments, grades, attendance, users);
    }

    @Test
    void previewsWeightedGradesAttendanceAndMissingData() {
        AcademicYear year = AcademicYear.builder().id("ay1").code("2026-2027")
                .name("Năm học 2026-2027").build();
        Semester semester = Semester.builder().id("sm1").academicYearId("ay1").code("HK1")
                .name("Học kỳ 1").startDate(LocalDate.of(2026, 8, 1))
                .endDate(LocalDate.of(2026, 12, 31)).build();
        SchoolClass schoolClass = SchoolClass.builder().id("c1").code("10A1").name("Lớp 10A1")
                .academicYearId("ay1").homeroomTeacherId("teacher1").build();
        List<ExamCategory> categories = List.of(
                ExamCategory.builder().id("ec1").code("ORAL").name("Miệng").weight(1).build(),
                ExamCategory.builder().id("ec2").code("FINAL").name("Cuối kỳ").weight(3).build());
        List<UserDto> students = List.of(
                student("s1", "HS001", "Nguyễn An"),
                student("s2", "HS002", "Trần Bình"));

        when(structure.listYears()).thenReturn(List.of(year));
        when(structure.listSemesters("ay1")).thenReturn(List.of(semester));
        when(structure.getClass("c1")).thenReturn(schoolClass);
        when(teachingAssignments.list(null, "c1", null, "sm1", "ACTIVE"))
                .thenReturn(List.of(assignment()));
        when(grades.listCategories()).thenReturn(categories);
        when(users.list("STUDENT", null, "c1")).thenReturn(students);
        when(grades.list(eq(null), eq(null), eq("sm1"), eq(null), any()))
                .thenReturn(List.of(
                        grade("g1", "s1", "ORAL", 8),
                        grade("g2", "s1", "FINAL", 10),
                        grade("g3", "s2", "ORAL", 6)));
        when(attendance.list(null, "c1", null, null)).thenReturn(List.of(
                attendance("a1", "s1", "PRESENT", LocalDate.of(2026, 9, 1)),
                attendance("a2", "s1", "LATE", LocalDate.of(2026, 9, 2)),
                attendance("outside", "s2", "PRESENT", LocalDate.of(2027, 1, 2))));

        YearSummaryPreviewResponse result = service.preview(
                "ay1", "sm1", "c1", new CurrentUser("admin", "admin", "ADMIN"));

        assertEquals(2, result.metrics().totalStudents());
        assertEquals(1, result.metrics().readyStudents());
        assertEquals(1, result.metrics().missingGradeStudents());
        assertEquals(1, result.metrics().noAttendanceStudents());
        assertEquals(7.8, result.metrics().classAverage());
        assertEquals(75.0, result.metrics().attendanceRate());
        assertEquals(4, result.subjects().get(0).expectedGradeCount());
        assertEquals(3, result.subjects().get(0).enteredGradeCount());
        assertEquals(75.0, result.subjects().get(0).completionRate());
        assertEquals(9.5, result.students().get(0).overallAverage());
        assertTrue(result.students().get(0).ready());
        assertEquals(List.of("Cuối kỳ"), result.students().get(1).subjects().get(0).missingCategories());
        assertFalse(result.students().get(1).ready());
    }

    @Test
    void teacherCanOnlyPreviewOwnHomeroomClass() {
        when(structure.listYears()).thenReturn(List.of(
                AcademicYear.builder().id("ay1").code("2026-2027").build()));
        when(structure.listSemesters("ay1")).thenReturn(List.of(
                Semester.builder().id("sm1").academicYearId("ay1").code("HK1").build()));
        when(structure.getClass("c1")).thenReturn(SchoolClass.builder()
                .id("c1").academicYearId("ay1").homeroomTeacherId("teacher1").build());

        ApiException error = assertThrows(ApiException.class, () -> service.preview(
                "ay1", "sm1", "c1", new CurrentUser("teacher2", "gv.2", "TEACHER")));

        assertEquals(403, error.getStatus().value());
    }

    @Test
    void rejectsSemesterOutsideSelectedYear() {
        when(structure.listYears()).thenReturn(List.of(
                AcademicYear.builder().id("ay1").code("2026-2027").build()));
        when(structure.listSemesters("ay1")).thenReturn(List.of());

        ApiException error = assertThrows(ApiException.class, () -> service.preview(
                "ay1", "sm2", "c1", new CurrentUser("admin", "admin", "ADMIN")));

        assertEquals(400, error.getStatus().value());
    }

    private UserDto student(String id, String code, String name) {
        return new UserDto(id, code.toLowerCase(), name, "STUDENT", "ACTIVE",
                null, null, null, code, "10A1", "c1", null, null, null);
    }

    private TeachingAssignmentDto assignment() {
        return new TeachingAssignmentDto("ta1", "teacher1", "GV Toán", "c1", "10A1",
                "math", "Toán", "sm1", "ACTIVE", Instant.now(), Instant.now(),
                4, 0, 4, 0, 1, 4, 4, true, false, true, null,
                Instant.now(), "admin");
    }

    private Grade grade(String id, String studentId, String category, double score) {
        return Grade.builder().id(id).studentId(studentId).subjectId("math").subjectName("Toán")
                .semesterId("sm1").category(category).score(score).recordedAt(Instant.now()).build();
    }

    private AttendanceRecord attendance(String id, String studentId, String status, LocalDate date) {
        return AttendanceRecord.builder().id(id).studentId(studentId).classId("c1")
                .status(status).date(date).build();
    }
}
