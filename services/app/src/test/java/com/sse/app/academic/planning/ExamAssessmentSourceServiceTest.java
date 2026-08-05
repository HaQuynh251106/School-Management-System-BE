package com.sse.app.academic.planning;

import com.sse.app.academic.structure.StructureService;
import com.sse.app.academic.structure.Subject;
import com.sse.app.academic.structure.Semester;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ExamAssessmentSourceServiceTest {
    @Mock AcademicTrainingPlanRepository plans;
    @Mock AcademicAssessmentPlanRepository assessments;
    @Mock StructureService structure;

    private ExamAssessmentSourceService service;

    @BeforeEach
    void setUp() {
        service = new ExamAssessmentSourceService(plans, assessments, structure);
    }

    @Test
    void exposesOnlyPublishedWholeGradeAssessmentPlans() {
        AcademicTrainingPlan plan = AcademicTrainingPlan.builder()
                .id("plan-k10-v2").academicYearId("ay-1").gradeLevel("K10")
                .versionNumber(2).status("PUBLISHED").build();
        AcademicAssessmentPlan wholeGrade = AcademicAssessmentPlan.builder()
                .id("assessment-math").planId(plan.getId()).semesterId("sm-1")
                .subjectId("math").assessmentType("FINAL").name("Cuối kỳ Toán")
                .weekNumber(17).durationMinutes(90).assessmentForm("WRITTEN").build();
        AcademicAssessmentPlan classOnly = AcademicAssessmentPlan.builder()
                .id("assessment-class").planId(plan.getId()).semesterId("sm-1")
                .classId("10a1").subjectId("math").assessmentType("FINAL")
                .name("Riêng 10A1").weekNumber(17).durationMinutes(90)
                .assessmentForm("WRITTEN").build();
        when(plans.findByAcademicYearIdAndGradeLevelOrderByVersionNumberDesc("ay-1", "K10"))
                .thenReturn(List.of(plan));
        when(assessments.findByPlanIdOrderBySemesterIdAscWeekNumberAscSubjectIdAsc(plan.getId()))
                .thenReturn(List.of(wholeGrade, classOnly));
        when(structure.listSubjects()).thenReturn(List.of(Subject.builder()
                .id("math").code("MATH").name("Toán").active(true).build()));
        when(structure.getSemester("sm-1")).thenReturn(Semester.builder()
                .id("sm-1").academicYearId("ay-1")
                .startDate(LocalDate.of(2027, 9, 1))
                .endDate(LocalDate.of(2028, 1, 31)).build());
        when(structure.listHolidays("ay-1")).thenReturn(List.of());

        var readiness = service.readiness("ay-1", "sm-1", "FINAL", List.of("K10"));

        assertTrue(readiness.ready());
        assertEquals(1, readiness.sourceCount());
        assertEquals("assessment-math", readiness.sources().get(0).assessmentPlanId());
        assertEquals(90, readiness.sources().get(0).durationMinutes());
        assertEquals(2, readiness.sources().get(0).planVersion());
        assertEquals(1, readiness.subjectCount());
        assertEquals(1, readiness.requiredDays());
        assertEquals(LocalDate.of(2027, 12, 22), readiness.suggestedStartDate());
        assertEquals(LocalDate.of(2027, 12, 28), readiness.suggestedEndDate());
        assertTrue(readiness.suggestedExamDates().contains(LocalDate.of(2027, 12, 22)));
    }

    @Test
    void reportsMissingPublishedPlanForEveryRequestedGrade() {
        when(plans.findByAcademicYearIdAndGradeLevelOrderByVersionNumberDesc("ay-1", "K10"))
                .thenReturn(List.of());
        when(plans.findByAcademicYearIdAndGradeLevelOrderByVersionNumberDesc("ay-1", "K11"))
                .thenReturn(List.of(AcademicTrainingPlan.builder()
                        .id("draft").academicYearId("ay-1").gradeLevel("K11")
                        .versionNumber(1).status("DRAFT").build()));
        when(structure.listSubjects()).thenReturn(List.of());

        var readiness = service.readiness(
                "ay-1", "sm-1", "MIDTERM", List.of("K10", "K11"));

        assertFalse(readiness.ready());
        assertEquals(2, readiness.issues().size());
    }
}
