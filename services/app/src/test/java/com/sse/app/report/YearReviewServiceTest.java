package com.sse.app.report;

import com.sse.app.academic.structure.AcademicYear;
import com.sse.app.academic.structure.SchoolClass;
import com.sse.app.academic.structure.Semester;
import com.sse.app.academic.structure.StructureService;
import com.sse.app.audit.AuditService;
import com.sse.app.common.ApiException;
import com.sse.app.identity.UserService;
import com.sse.app.report.YearReviewDtos.YearReviewResponse;
import com.sse.app.report.YearSummaryPreviewDtos.AttendanceSummary;
import com.sse.app.report.YearSummaryPreviewDtos.PreviewMetrics;
import com.sse.app.report.YearSummaryPreviewDtos.StudentSummaryRow;
import com.sse.app.report.YearSummaryPreviewDtos.YearSummaryPreviewResponse;
import com.sse.app.security.CurrentUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class YearReviewServiceTest {
    private StructureService structure;
    private YearSummaryPreviewService previews;
    private StudentYearlySummaryRepository summaries;
    private AcademicPromotionPolicyRepository policies;
    private AcademicResultLockService locks;
    private AuditService audit;
    private UserService users;
    private YearReviewService service;
    private AcademicYear year;
    private SchoolClass schoolClass;
    private Semester semester1;
    private Semester semester2;

    @BeforeEach
    void setUp() {
        structure = mock(StructureService.class);
        previews = mock(YearSummaryPreviewService.class);
        summaries = mock(StudentYearlySummaryRepository.class);
        policies = mock(AcademicPromotionPolicyRepository.class);
        locks = mock(AcademicResultLockService.class);
        audit = mock(AuditService.class);
        users = mock(UserService.class);
        service = new YearReviewService(
                structure, previews, summaries, policies, locks, audit, users);
        year = AcademicYear.builder().id("ay").code("2025-2026").name("Năm học 2025-2026")
                .status("CLOSED").endDate(LocalDate.now().minusDays(1)).build();
        schoolClass = SchoolClass.builder().id("class").code("11A1").name("Lớp 11A1")
                .gradeLevel("K11").academicYearId("ay").homeroomTeacherId("teacher").build();
        semester1 = Semester.builder().id("s1").academicYearId("ay").name("Học kỳ 1").sequence(1).build();
        semester2 = Semester.builder().id("s2").academicYearId("ay").name("Học kỳ 2").sequence(2).build();
        when(structure.listYears()).thenReturn(List.of(year));
        when(structure.getClass("class")).thenReturn(schoolClass);
        when(structure.listSemesters("ay")).thenReturn(List.of(semester1, semester2));
        when(summaries.findByAcademicYearIdAndClassId("ay", "class")).thenReturn(List.of());
        when(policies.findByAcademicYearId("ay")).thenReturn(Optional.empty());
        when(users.fullNameOf(any())).thenReturn("Người kiểm tra");
    }

    @Test
    void reviewSuggestsPromotionWhenBothSemestersAreReady() {
        stubReadyPreviews();
        stubDraftDecision("PROMOTED", "GOOD");

        YearReviewResponse response = service.review(
                "ay", "class", new CurrentUser("admin", "admin", "ADMIN"));

        assertTrue(response.yearClosed());
        assertTrue(response.canFinalize());
        assertEquals("PROMOTED", response.students().get(0).suggestedResult());
        assertEquals(8.2, response.students().get(0).yearlyAverage());
        assertEquals("(HK1 + HK2 x 2) / 3", response.yearlyAverageFormula());
        assertEquals(1, response.metrics().promoted());
    }

    @Test
    void retainedDecisionRequiresReason() {
        stubReadyPreviews();

        ApiException error = assertThrows(ApiException.class, () -> service.saveDecision(
                "ay", "class", "student", "RETAINED", "GOOD", null,
                new CurrentUser("teacher", "gv", "TEACHER")));

        assertEquals(400, error.getStatus().value());
    }

    @Test
    void finalizePersistsSnapshotsLocksGradesAndAudits() {
        stubReadyPreviews();
        stubDraftDecision("PROMOTED", "GOOD");

        service.finalizeClass("ay", "class", true,
                new CurrentUser("admin", "admin", "ADMIN"));

        verify(summaries).save(any(StudentYearlySummary.class));
        verify(locks).lock("ay", "class", List.of("s1", "s2"), "admin");
        verify(audit).record(eq("admin"), any(), eq("ADMIN"), eq("FINALIZE"),
                eq("academic"), eq("year_review"), eq("ay:class"), any());
    }

    @Test
    void incompleteStudentBlocksFinalization() {
        when(previews.preview(eq("ay"), eq("s1"), eq("class"), any()))
                .thenReturn(preview("s1", 7.0, true));
        when(previews.preview(eq("ay"), eq("s2"), eq("class"), any()))
                .thenReturn(preview("s2", null, false));

        YearReviewResponse response = service.review(
                "ay", "class", new CurrentUser("admin", "admin", "ADMIN"));

        assertFalse(response.canFinalize());
        assertEquals("INCOMPLETE", response.students().get(0).result());
        assertFalse(response.finalizeBlockers().isEmpty());
    }

    @Test
    void gradeTwelveIsOnlyEligibleForGraduation() {
        schoolClass.setGradeLevel("K12");
        stubReadyPreviews();
        stubDraftDecision("ELIGIBLE_FOR_GRADUATION", "GOOD");

        YearReviewResponse response = service.review(
                "ay", "class", new CurrentUser("admin", "admin", "ADMIN"));

        assertEquals("ELIGIBLE_FOR_GRADUATION",
                response.students().get(0).suggestedResult());
    }

    @Test
    void missingConductAndUnsavedDecisionBlockFinalization() {
        stubReadyPreviews();

        YearReviewResponse response = service.review(
                "ay", "class", new CurrentUser("admin", "admin", "ADMIN"));

        assertFalse(response.canFinalize());
        assertTrue(response.finalizeBlockers().stream()
                .anyMatch(blocker -> blocker.contains("hạnh kiểm")));
        assertTrue(response.finalizeBlockers().stream()
                .anyMatch(blocker -> blocker.contains("chưa được lưu")));
    }

    @Test
    void policyCannotChangeAfterAnyClassWasFinalized() {
        when(summaries.existsByAcademicYearIdAndStatus("ay", "FINALIZED"))
                .thenReturn(true);

        ApiException error = assertThrows(ApiException.class, () -> service.updatePolicy(
                "ay", new YearReviewDtos.UpdatePromotionPolicyRequest(
                        5.0, "PASS", 5.0, 0, null),
                new CurrentUser("admin", "admin", "ADMIN")));

        assertEquals(409, error.getStatus().value());
    }

    @Test
    void reopeningClassReturnsSummariesToDraftAndUnlocksGrades() {
        stubReadyPreviews();
        StudentYearlySummary summary = StudentYearlySummary.builder()
                .id("summary").academicYearId("ay").classId("class")
                .studentId("student").studentCode("HS001").studentName("Nguyễn An")
                .result("PROMOTED").conductGrade("GOOD").status("FINALIZED")
                .finalizedBy("admin").finalizedAt(Instant.now()).build();
        when(summaries.findByAcademicYearIdAndClassId("ay", "class"))
                .thenReturn(List.of(summary));

        YearReviewResponse response = service.reopenClass(
                "ay", "class", "Bổ sung điểm hợp lệ", true,
                new CurrentUser("admin", "admin", "ADMIN"));

        assertEquals("DRAFT", summary.getStatus());
        assertFalse(response.finalized());
        verify(locks).unlock("ay", "class");
        verify(audit).record(eq("admin"), any(), eq("ADMIN"),
                eq("REOPEN_CLASS_RESULT"), eq("academic"), eq("year_review"),
                eq("ay:class"), any());
    }

    @Test
    void finalizedSnapshotRemainsVisibleAfterStudentMovesToNewClass() {
        when(previews.preview(eq("ay"), eq("s1"), eq("class"), any()))
                .thenReturn(emptyPreview("s1"));
        when(previews.preview(eq("ay"), eq("s2"), eq("class"), any()))
                .thenReturn(emptyPreview("s2"));
        StudentYearlySummary summary = StudentYearlySummary.builder()
                .id("summary").academicYearId("ay").classId("class")
                .studentId("student").studentCode("HS001").studentName("Nguyễn An")
                .yearlyAverage(8.2).attendanceRate(95.0)
                .result("PROMOTED").conductGrade("GOOD").status("FINALIZED")
                .finalizedAt(Instant.now()).build();
        when(summaries.findByAcademicYearIdAndClassId("ay", "class"))
                .thenReturn(List.of(summary));

        YearReviewResponse response = service.review(
                "ay", "class", new CurrentUser("admin", "admin", "ADMIN"));

        assertTrue(response.finalized());
        assertEquals(1, response.students().size());
        assertEquals(8.2, response.students().get(0).yearlyAverage());
    }

    private void stubReadyPreviews() {
        when(previews.preview(eq("ay"), eq("s1"), eq("class"), any()))
                .thenReturn(preview("s1", 7.5, true));
        when(previews.preview(eq("ay"), eq("s2"), eq("class"), any()))
                .thenReturn(preview("s2", 8.5, true));
    }

    private void stubDraftDecision(String result, String conductGrade) {
        StudentYearlySummary summary = StudentYearlySummary.builder()
                .id("summary").academicYearId("ay").classId("class")
                .studentId("student").studentCode("HS001").studentName("Nguyễn An")
                .result(result).conductGrade(conductGrade).status("DRAFT").build();
        when(summaries.findByAcademicYearIdAndClassId("ay", "class"))
                .thenReturn(List.of(summary));
        when(summaries.findByAcademicYearIdAndStudentId("ay", "student"))
                .thenReturn(Optional.of(summary));
    }

    private YearSummaryPreviewResponse preview(String semesterId, Double average, boolean ready) {
        AttendanceSummary attendance = new AttendanceSummary(8, 1, 1, 0, 10, 85.0);
        StudentSummaryRow student = new StudentSummaryRow(
                "student", "HS001", "Nguyễn An", average, attendance,
                List.of(), ready ? 0 : 1, ready,
                ready ? List.of() : List.of("Thiếu đầu điểm"));
        return new YearSummaryPreviewResponse(
                "ay", "Năm học 2025-2026", semesterId,
                "s1".equals(semesterId) ? "Học kỳ 1" : "Học kỳ 2",
                "class", "11A1", "Lớp 11A1", "CLOSED", "Đã kết thúc",
                Instant.now(), new PreviewMetrics(1, ready ? 1 : 0, ready ? 0 : 1,
                0, average, 85.0), List.of(), List.of(student), List.of());
    }

    private YearSummaryPreviewResponse emptyPreview(String semesterId) {
        return new YearSummaryPreviewResponse(
                "ay", "Năm học 2025-2026", semesterId,
                "s1".equals(semesterId) ? "Học kỳ 1" : "Học kỳ 2",
                "class", "11A1", "Lớp 11A1", "CLOSED", "Đã kết thúc",
                Instant.now(), new PreviewMetrics(0, 0, 0, 0, null, null),
                List.of(), List.of(), List.of());
    }
}
