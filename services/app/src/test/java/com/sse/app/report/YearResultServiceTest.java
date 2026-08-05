package com.sse.app.report;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sse.app.academic.structure.AcademicYear;
import com.sse.app.academic.structure.SchoolClass;
import com.sse.app.academic.structure.StructureService;
import com.sse.app.audit.AuditService;
import com.sse.app.common.ApiException;
import com.sse.app.event.DomainEventPublisher;
import com.sse.app.identity.UserService;
import com.sse.app.report.YearResultDtos.PublishYearResultResponse;
import com.sse.app.report.YearResultDtos.StudentYearResult;
import com.sse.app.report.YearReviewDtos.AnnualSubjectResult;
import com.sse.app.report.YearReviewDtos.SemesterResult;
import com.sse.app.report.YearReviewDtos.YearReviewMetrics;
import com.sse.app.report.YearReviewDtos.YearReviewResponse;
import com.sse.app.report.YearReviewDtos.YearReviewStudent;
import com.sse.app.security.CurrentUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class YearResultServiceTest {
    private StudentYearlySummaryRepository summaries;
    private YearResultPublicationRepository publications;
    private YearReviewService reviews;
    private StructureService structure;
    private UserService users;
    private DomainEventPublisher events;
    private AuditService audit;
    private YearResultPublicationHistoryRepository history;
    private YearResultService service;
    private AcademicYear year;
    private SchoolClass schoolClass;
    private StudentYearlySummary summary;
    private AtomicReference<YearResultPublication> savedPublication;

    @BeforeEach
    void setUp() {
        summaries = mock(StudentYearlySummaryRepository.class);
        publications = mock(YearResultPublicationRepository.class);
        reviews = mock(YearReviewService.class);
        structure = mock(StructureService.class);
        users = mock(UserService.class);
        events = mock(DomainEventPublisher.class);
        audit = mock(AuditService.class);
        history = mock(YearResultPublicationHistoryRepository.class);
        savedPublication = new AtomicReference<>();
        YearResultPdfRenderer pdf = mock(YearResultPdfRenderer.class);
        YearResultExcelExporter excel = mock(YearResultExcelExporter.class);
        YearResultSnapshotBuilder snapshotBuilder = mock(YearResultSnapshotBuilder.class);
        service = new YearResultService(summaries, publications, reviews, structure,
                users, events, audit, new ObjectMapper(), pdf, excel, snapshotBuilder,
                history);

        year = AcademicYear.builder().id("ay").code("2026-2027")
                .name("Năm học 2026-2027").status("CLOSED")
                .endDate(LocalDate.now().minusDays(1)).build();
        schoolClass = SchoolClass.builder().id("class").code("10A1")
                .name("Lớp 10A1").gradeLevel("K10").academicYearId("ay").build();
        summary = StudentYearlySummary.builder()
                .id("summary").academicYearId("ay").classId("class")
                .studentId("student").studentCode("HS001").studentName("Nguyễn An")
                .yearlyAverage(8.2).attendanceRate(98.0).conductGrade("GOOD")
                .result("PROMOTED").status("FINALIZED").finalizedAt(Instant.now())
                .build();

        when(structure.listYears()).thenReturn(List.of(year));
        when(structure.getClass("class")).thenReturn(schoolClass);
        when(summaries.findByAcademicYearIdAndClassId("ay", "class"))
                .thenReturn(List.of(summary));
        when(publications.save(any())).thenAnswer(invocation -> {
            YearResultPublication publication = invocation.getArgument(0);
            savedPublication.set(publication);
            return publication;
        });
        when(publications.findByAcademicYearIdAndClassId("ay", "class"))
                .thenAnswer(ignored -> Optional.ofNullable(savedPublication.get()));
        when(users.fullNameOf(any())).thenReturn("Admin");
    }

    @Test
    void publishSnapshotsResultAndQueuesNotificationOnce() {
        when(reviews.review(eq("ay"), eq("class"), any())).thenReturn(review(true));
        CurrentUser admin = new CurrentUser("admin", "admin", "ADMIN");

        PublishYearResultResponse first = service.publish("ay", "class", true, null, admin);
        PublishYearResultResponse replay = service.publish("ay", "class", true, null, admin);

        assertTrue(first.newlyPublished());
        assertEquals(1, first.notificationsQueued());
        assertFalse(replay.newlyPublished());
        assertEquals(0, replay.notificationsQueued());
        assertTrue(summary.getSemesterResultsJson().contains("HK1"));
        assertTrue(summary.getSubjectResultsJson().contains("Toán"));
        verify(events).publish(eq("academic.year_result.published"), eq("admin"),
                eq("student_yearly_summary"), eq("summary"), any());
        verify(audit).record(eq("admin"), any(), eq("ADMIN"),
                eq("PUBLISH_YEAR_RESULTS"), eq("academic"),
                eq("year_result_publication"), any(), any());
    }

    @Test
    void unfinalizedClassCannotBePublished() {
        when(reviews.review(eq("ay"), eq("class"), any())).thenReturn(review(false));

        ApiException error = assertThrows(ApiException.class, () -> service.publish(
                "ay", "class", true, null,
                new CurrentUser("admin", "admin", "ADMIN")));

        assertEquals(409, error.getStatus().value());
        verify(events, never()).publish(any(), any(), any(), any(), any());
    }

    @Test
    void parentCanReadPublishedChildResult() throws Exception {
        summary.setSemesterResultsJson(new ObjectMapper().writeValueAsString(
                List.of(new SemesterResult("s1", "HK1", "CLOSED",
                        8.0, 98.0, true, List.of()))));
        summary.setSubjectResultsJson(new ObjectMapper().writeValueAsString(
                List.of(new AnnualSubjectResult("math", "Toán",
                        8.0, 8.3, 8.2, false))));
        when(summaries.findByStudentId("student")).thenReturn(List.of(summary));
        savedPublication.set(YearResultPublication.builder()
                .id("publication").academicYearId("ay").classId("class")
                .status("PUBLISHED").publishedAt(Instant.now()).build());
        CurrentUser parent = new CurrentUser("parent", "parent", "PARENT");

        List<StudentYearResult> results = service.resultsForStudent("student", parent);

        assertEquals(1, results.size());
        assertEquals("Toán", results.get(0).subjects().get(0).subjectName());
        verify(users).assertParentOf("parent", "student");
    }

    @Test
    void unpublishedResultIsHidden() {
        when(summaries.findByStudentId("student")).thenReturn(List.of(summary));

        List<StudentYearResult> results = service.resultsForStudent(
                "student", new CurrentUser("student", "hs", "STUDENT"));

        assertTrue(results.isEmpty());
    }

    @Test
    void withdrawHidesResultAndRepublishCreatesNewVersion() {
        when(reviews.review(eq("ay"), eq("class"), any())).thenReturn(review(true));
        CurrentUser admin = new CurrentUser("admin", "admin", "ADMIN");

        service.publish("ay", "class", true, null, admin);
        var withdrawn = service.withdraw(
                "ay", "class", true, "Điều chỉnh lại lớp đích", admin);

        assertTrue(withdrawn.newlyWithdrawn());
        assertEquals("WITHDRAWN", withdrawn.publication().publicationState());
        assertEquals(1, withdrawn.publication().publicationVersion());
        when(summaries.findByStudentId("student")).thenReturn(List.of(summary));
        assertTrue(service.resultsForStudent(
                "student", new CurrentUser("student", "hs", "STUDENT")).isEmpty());
        verify(events).publish(eq("academic.year_result.withdrawn"), eq("admin"),
                eq("student_yearly_summary"), eq("summary"), any());

        PublishYearResultResponse republished = service.publish(
                "ay", "class", true, "Đã kiểm tra và sửa lớp đích", admin);

        assertTrue(republished.newlyPublished());
        assertEquals(2, republished.publication().publicationVersion());
        assertEquals("PUBLISHED", republished.publication().publicationState());
        verify(audit).record(eq("admin"), any(), eq("ADMIN"),
                eq("WITHDRAW_YEAR_RESULTS"), eq("academic"),
                eq("year_result_publication"), any(), any());
        verify(audit).record(eq("admin"), any(), eq("ADMIN"),
                eq("REPUBLISH_YEAR_RESULTS"), eq("academic"),
                eq("year_result_publication"), any(), any());
    }

    @Test
    void republishRequiresReason() {
        when(reviews.review(eq("ay"), eq("class"), any())).thenReturn(review(true));
        CurrentUser admin = new CurrentUser("admin", "admin", "ADMIN");
        service.publish("ay", "class", true, null, admin);
        service.withdraw("ay", "class", true, "Điều chỉnh dữ liệu học sinh", admin);

        ApiException error = assertThrows(ApiException.class,
                () -> service.publish("ay", "class", true, "ngắn", admin));

        assertEquals(400, error.getStatus().value());
    }

    @Test
    void publishWithdrawAndRepublishAppendImmutableHistory() {
        when(reviews.review(eq("ay"), eq("class"), any())).thenReturn(review(true));
        when(history.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        CurrentUser admin = new CurrentUser("admin", "admin", "ADMIN");

        service.publish("ay", "class", true, null, admin);
        service.withdraw("ay", "class", true,
                "Điều chỉnh kết quả sau rà soát", admin);
        service.publish("ay", "class", true,
                "Đã rà soát và xác nhận lại kết quả", admin);

        ArgumentCaptor<YearResultPublicationHistory> captor =
                ArgumentCaptor.forClass(YearResultPublicationHistory.class);
        verify(history, org.mockito.Mockito.times(3)).save(captor.capture());
        assertEquals(List.of("PUBLISH", "WITHDRAW", "REPUBLISH"),
                captor.getAllValues().stream()
                        .map(YearResultPublicationHistory::getAction).toList());
        assertEquals(List.of(1, 1, 2),
                captor.getAllValues().stream()
                        .map(YearResultPublicationHistory::getPublicationVersion)
                        .toList());
    }

    private YearReviewResponse review(boolean finalized) {
        YearReviewStudent student = new YearReviewStudent(
                "student", "HS001", "Nguyễn An",
                List.of(new SemesterResult("s1", "HK1", "CLOSED",
                        8.0, 98.0, true, List.of())),
                List.of(new AnnualSubjectResult("math", "Toán",
                        8.0, 8.3, 8.2, false)),
                8.2, 98.0, true, "GOOD", 0,
                "PROMOTED", "PROMOTED", finalized ? "FINALIZED" : "DRAFT",
                null, "Admin", Instant.now(), finalized ? Instant.now() : null);
        return new YearReviewResponse(
                "ay", "Năm học 2026-2027", "class", "10A1", "Lớp 10A1",
                "K10", "CLOSED", true, finalized, false, List.of(),
                "(HK1 + HK2 x 2) / 3", null,
                new YearReviewMetrics(1, 1, 1, 0, 0, 0, 1, 1),
                List.of(student), Instant.now());
    }
}
