package com.sse.app.academic.timetable;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sse.app.academic.planning.AcademicPlanningService;
import com.sse.app.academic.planning.AcademicTrainingPlan;
import com.sse.app.academic.planning.AcademicTrainingPlanSubject;
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
        TimetablePlanSourceService service = new TimetablePlanSourceService(
                planning, new ObjectMapper().findAndRegisterModules());
        for (String grade : List.of("K10", "K11", "K12")) {
            int version = Integer.parseInt(grade.substring(1)) - 8;
            String planId = "plan-" + grade;
            when(planning.publishedPlan("year-1", grade)).thenReturn(
                    AcademicTrainingPlan.builder().id(planId).academicYearId("year-1")
                            .gradeLevel(grade).versionNumber(version)
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
    }
}
