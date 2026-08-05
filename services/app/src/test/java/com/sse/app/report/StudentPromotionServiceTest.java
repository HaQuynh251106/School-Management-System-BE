package com.sse.app.report;

import com.sse.app.academic.structure.AcademicYear;
import com.sse.app.academic.structure.SchoolClass;
import com.sse.app.academic.structure.StructureService;
import com.sse.app.audit.AuditService;
import com.sse.app.identity.User;
import com.sse.app.identity.UserRepository;
import com.sse.app.identity.UserService;
import com.sse.app.report.StudentPromotionDtos.ExecutePromotionRequest;
import com.sse.app.report.StudentPromotionDtos.PromotionExecutionResponse;
import com.sse.app.report.StudentPromotionDtos.PromotionPlanRequest;
import com.sse.app.report.StudentPromotionDtos.PromotionPreviewResponse;
import com.sse.app.report.StudentPromotionDtos.UndoPromotionRequest;
import com.sse.app.security.CurrentUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StudentPromotionServiceTest {
    private StructureService structure;
    private StudentYearlySummaryRepository summaries;
    private StudentClassEnrollmentRepository enrollments;
    private YearResultPublicationRepository publications;
    private UserRepository users;
    private UserService userService;
    private AuditService audit;
    private StudentPromotionService service;
    private AcademicYear sourceYear;
    private AcademicYear targetYear;
    private SchoolClass sourceClass;
    private SchoolClass targetClass;
    private StudentYearlySummary summary;
    private User student;

    @BeforeEach
    void setUp() {
        structure = mock(StructureService.class);
        summaries = mock(StudentYearlySummaryRepository.class);
        enrollments = mock(StudentClassEnrollmentRepository.class);
        publications = mock(YearResultPublicationRepository.class);
        users = mock(UserRepository.class);
        userService = mock(UserService.class);
        audit = mock(AuditService.class);
        service = new StudentPromotionService(
                structure, summaries, enrollments, publications,
                users, userService, audit);

        sourceYear = AcademicYear.builder().id("2025").code("2025-2026")
                .name("Năm học 2025-2026").status("CLOSED").build();
        targetYear = AcademicYear.builder().id("2026").code("2026-2027")
                .name("Năm học 2026-2027").status("ACTIVE").build();
        sourceClass = SchoolClass.builder().id("10a1-old").code("10A1")
                .name("Lớp 10A1").gradeLevel("K10").academicYearId("2025").build();
        targetClass = SchoolClass.builder().id("11a1-new").code("11A1")
                .name("Lớp 11A1").gradeLevel("K11").academicYearId("2026").build();
        summary = StudentYearlySummary.builder().id("summary").academicYearId("2025")
                .classId("10a1-old").studentId("student").studentCode("HS001")
                .studentName("Nguyễn An").result("PROMOTED").conductGrade("GOOD")
                .status("FINALIZED").build();
        student = User.builder().id("student").username("hs001").role("STUDENT")
                .status("ACTIVE").studentCode("HS001").fullName("Nguyễn An")
                .classId("10a1-old").className("10A1").build();

        when(structure.listYears()).thenReturn(List.of(sourceYear, targetYear));
        when(structure.getClass("10a1-old")).thenReturn(sourceClass);
        when(structure.listClasses("2026", null)).thenReturn(List.of(targetClass));
        when(summaries.findByAcademicYearIdAndClassId("2025", "10a1-old"))
                .thenReturn(List.of(summary));
        when(enrollments.findByAcademicYearIdAndStudentId("2026", "student"))
                .thenReturn(Optional.empty());
        when(users.findById("student")).thenReturn(Optional.of(student));
        when(userService.fullNameOf(any())).thenReturn("School Administrator");
    }

    @Test
    void previewSuggestsSameClassSuffixInNextGrade() {
        PromotionPreviewResponse response = service.preview(plan());

        assertTrue(response.canExecute());
        assertEquals("11a1-new", response.students().get(0).targetClassId());
        assertEquals("PROMOTE", response.students().get(0).action());
        assertEquals("READY", response.students().get(0).status());
    }

    @Test
    void retainedStudentStaysInSameGrade() {
        summary.setResult("RETAINED");
        targetClass.setId("10a1-new");
        targetClass.setCode("10A1");
        targetClass.setGradeLevel("K10");

        PromotionPreviewResponse response = service.preview(plan());

        assertEquals("RETAIN", response.students().get(0).action());
        assertEquals("10a1-new", response.students().get(0).targetClassId());
    }

    @Test
    void gradeTwelveCompletionDoesNotCreateEnrollment() {
        sourceClass.setGradeLevel("K12");
        sourceClass.setCode("12A1");
        summary.setResult("ELIGIBLE_FOR_GRADUATION");
        when(structure.listClasses("2026", null)).thenReturn(List.of());

        PromotionPreviewResponse response = service.preview(plan());

        assertTrue(response.canExecute());
        assertEquals("COMPLETE_SCHOOL", response.students().get(0).action());
        assertEquals(1, response.metrics().completingSchool());
    }

    @Test
    void plannedTargetYearBlocksExecution() {
        targetYear.setStatus("PLANNED");

        PromotionPreviewResponse response = service.preview(plan());

        assertFalse(response.canExecute());
        assertTrue(response.blockers().stream()
                .anyMatch(blocker -> blocker.contains("đang hoạt động")));
    }

    @Test
    void fullTargetClassBlocksPromotion() {
        targetClass.setStudentCount(45);
        targetClass.setMaxStudents(45);

        PromotionPreviewResponse response = service.preview(plan());

        assertFalse(response.canExecute());
        assertEquals("BLOCKED", response.students().get(0).status());
        assertTrue(response.students().get(0).message().contains("sức chứa"));
    }

    @Test
    void executeCreatesEnrollmentAndSecondRunIsIdempotent() {
        PromotionExecutionResponse response = service.execute(
                new ExecutePromotionRequest(
                        "2025", "2026", "10a1-old", List.of(), true),
                new CurrentUser("admin", "admin", "ADMIN"));

        assertEquals(1, response.enrolled());
        assertEquals("11a1-new", student.getClassId());
        assertEquals("ENROLLED", summary.getProgressionStatus());
        verify(enrollments).save(any(StudentClassEnrollment.class));
        verify(audit).record(eq("admin"), any(), eq("ADMIN"),
                eq("EXECUTE_YEAR_PROMOTION"), eq("academic"),
                eq("student_class_enrollment"), eq("2025:10a1-old"), any());

        PromotionExecutionResponse replay = service.execute(
                new ExecutePromotionRequest(
                        "2025", "2026", "10a1-old", List.of(), true),
                new CurrentUser("admin", "admin", "ADMIN"));

        assertEquals(0, replay.enrolled());
        assertEquals(1, replay.skipped());
    }

    @Test
    void undoRestoresSourceClassAndKeepsRevertedEnrollmentHistory() {
        summary.setProgressionStatus("ENROLLED");
        summary.setNextClassId("11a1-new");
        student.setClassId("11a1-new");
        student.setClassName("11A1");
        StudentClassEnrollment enrollment = StudentClassEnrollment.builder()
                .id("enrollment").academicYearId("2026").classId("11a1-new")
                .studentId("student").sourceAcademicYearId("2025")
                .sourceClassId("10a1-old").sourceSummaryId("summary")
                .status("ACTIVE").build();
        when(enrollments.findBySourceAcademicYearIdAndSourceClassId(
                "2025", "10a1-old")).thenReturn(List.of(enrollment));
        when(summaries.findByAcademicYearIdAndStudentId(
                "2026", "student")).thenReturn(Optional.empty());

        var result = service.undo(new UndoPromotionRequest(
                        "2025", "2026", "10a1-old",
                        "Xếp nhầm lớp đích của học sinh", true),
                new CurrentUser("admin", "admin", "ADMIN"));

        assertEquals(1, result.revertedEnrollments());
        assertEquals("REVERTED", enrollment.getStatus());
        assertEquals("10a1-old", student.getClassId());
        assertEquals(null, summary.getProgressionStatus());
        verify(audit).record(eq("admin"), any(), eq("ADMIN"),
                eq("UNDO_YEAR_PROMOTION"), eq("academic"),
                eq("student_class_enrollment"), eq("2025:10a1-old"), any());
    }

    @Test
    void undoRequiresPublishedResultToBeWithdrawnFirst() {
        when(publications.findByAcademicYearIdAndClassId(
                "2025", "10a1-old")).thenReturn(Optional.of(
                YearResultPublication.builder().id("publication")
                        .academicYearId("2025").classId("10a1-old")
                        .status("PUBLISHED").build()));

        var error = org.junit.jupiter.api.Assertions.assertThrows(
                com.sse.app.common.ApiException.class,
                () -> service.undo(new UndoPromotionRequest(
                                "2025", "2026", "10a1-old",
                                "Cần điều chỉnh lại lớp đích", true),
                        new CurrentUser("admin", "admin", "ADMIN")));

        assertEquals(409, error.getStatus().value());
    }

    private PromotionPlanRequest plan() {
        return new PromotionPlanRequest(
                "2025", "2026", "10a1-old", List.of());
    }
}
