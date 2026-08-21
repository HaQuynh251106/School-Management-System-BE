package com.sse.app.academic.planning;

import com.sse.app.academic.structure.SchoolClass;
import com.sse.app.audit.AuditService;
import com.sse.app.identity.User;
import com.sse.app.identity.UserService;
import com.sse.app.report.AcademicEnrollmentService;
import com.sse.app.security.CurrentUser;
import com.sse.app.security.CurrentUserHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AcademicPlanningControllerTest {
    @Mock AcademicPlanningService planning;
    @Mock AcademicPlanCompletionService completion;
    @Mock AcademicPlanReportService reports;
    @Mock EducationPlanningCatalogService catalog;
    @Mock AuditService audit;
    @Mock UserService users;
    @Mock AcademicEnrollmentService enrollments;

    @AfterEach
    void clearCurrentUser() {
        CurrentUserHolder.clear();
    }

    @Test
    void studentPublishedPlanUsesActiveEnrollmentInsteadOfStaleUserClass() {
        AcademicPlanningController controller = new AcademicPlanningController(
                planning, completion, reports, catalog, audit, users, enrollments);
        CurrentUserHolder.set(new CurrentUser(
                "student-1", "student.one", "STUDENT"));

        User student = User.builder()
                .id("student-1")
                .role("STUDENT")
                .classId("class-old")
                .build();
        SchoolClass activeClass = SchoolClass.builder()
                .id("class-active")
                .code("10A1")
                .academicYearId("year-active")
                .gradeLevel("K10")
                .build();
        AcademicTrainingPlan publishedPlan = AcademicTrainingPlan.builder()
                .id("plan-active")
                .academicYearId("year-active")
                .gradeLevel("K10")
                .status("PUBLISHED")
                .build();

        when(users.getById("student-1")).thenReturn(student);
        when(enrollments.activeClassId("student-1"))
                .thenReturn(Optional.of("class-active"));
        when(planning.schoolClass("class-active")).thenReturn(activeClass);
        when(planning.listPlans("year-active", "K10"))
                .thenReturn(List.of(publishedPlan));
        when(completion.annualSummary("plan-active")).thenReturn(List.of());
        when(completion.listAssessments("plan-active")).thenReturn(List.of());

        ResponseEntity<AcademicPlanningDtos.PublishedPlanView> response =
                controller.publishedForMe(null);

        assertEquals(200, response.getStatusCode().value());
        assertEquals("class-active", response.getBody().classId());
        assertEquals("plan-active", response.getBody().plan().getId());
        verify(planning).schoolClass("class-active");
        verify(planning, never()).schoolClass("class-old");
    }
}
