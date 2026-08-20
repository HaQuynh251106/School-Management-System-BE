package com.sse.app.academic.timetable;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sse.app.academic.planning.AcademicPlanningService;
import com.sse.app.academic.planning.AcademicTrainingPlan;
import com.sse.app.academic.planning.AcademicTrainingPlanSubject;
import com.sse.app.academic.planning.EducationPlanningCatalogService;
import com.sse.app.academic.structure.SchoolClass;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TimetablePlanSourceServiceTest {
    @Test
    void resolvesAndKeepsAllThreePlanVersions() {
        AcademicPlanningService planning = mock(AcademicPlanningService.class);
        EducationPlanningCatalogService catalogs = mock(EducationPlanningCatalogService.class);
        TimetablePlanSourceService service = new TimetablePlanSourceService(
                planning, catalogs, new ObjectMapper().findAndRegisterModules());
        for (String grade : List.of("K10", "K11", "K12")) {
            int version = Integer.parseInt(grade.substring(1)) - 8;
            String planId = "plan-" + grade;
            when(planning.publishedPlan("year-1", grade)).thenReturn(
                    AcademicTrainingPlan.builder().id(planId).academicYearId("year-1")
                            .gradeLevel(grade).programId("program-2018").versionNumber(version)
                            .status("PUBLISHED").build());
            when(planning.publishedPlanSubjects(planId, "semester-1"))
                    .thenReturn(List.of(AcademicTrainingPlanSubject.builder()
                            .id("ps-" + grade).planId(planId)
                            .semesterId("semester-1").subjectId("math")
                            .weeklyPeriods(version).totalPeriods(version * 18).build()));
        }

        var resolved = service.resolve("year-1", "semester-1",
                Set.of("K12", "K10", "K11"));
        TimetableSchedule schedule = TimetableSchedule.builder()
                .sourcePlanSnapshot(service.serialize(resolved)).build();
        var restored = service.parse(schedule);

        assertEquals("K10 v2 · K11 v3 · K12 v4", service.summary(restored));
        assertEquals(3, service.weeklyPeriods(restored, "K11", "math"));
        assertEquals("program-2018", restored.get(0).programId());
    }

    @Test
    void returnsOnlySubjectsThatApplyToTheSelectedClass() {
        AcademicPlanningService planning = mock(AcademicPlanningService.class);
        EducationPlanningCatalogService catalogs = mock(EducationPlanningCatalogService.class);
        TimetablePlanSourceService service = new TimetablePlanSourceService(
                planning, catalogs, new ObjectMapper().findAndRegisterModules());
        SchoolClass schoolClass = SchoolClass.builder()
                .id("class-10a1").gradeLevel("K10").build();
        when(catalogs.subjectAppliesToClass(
                "program-2018", "K10", "class-10a1", "math")).thenReturn(true);
        when(catalogs.subjectAppliesToClass(
                "program-2018", "K10", "class-10a1", "physics")).thenReturn(false);
        var source = new TimetablePlanSourceService.PlanSnapshot(
                "plan-k10", "program-2018", 1, "K10", "PUBLISHED",
                null, "semester-1", List.of(
                new TimetablePlanSourceService.SubjectSnapshot("ps-math", "math", 3, 105),
                new TimetablePlanSourceService.SubjectSnapshot("ps-physics", "physics", 2, 70),
                new TimetablePlanSourceService.SubjectSnapshot("ps-flag", "sj-flag", 1, 35)));

        var result = service.applicableSubjects(List.of(source), schoolClass);

        assertEquals(List.of("math"), result.stream()
                .map(TimetablePlanSourceService.SubjectSnapshot::subjectId).toList());
    }
}
