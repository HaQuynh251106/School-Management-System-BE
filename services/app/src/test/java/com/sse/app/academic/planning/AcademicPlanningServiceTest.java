package com.sse.app.academic.planning;

import com.sse.app.academic.planning.AcademicPlanningDtos.ExamScheduleRequest;
import com.sse.app.academic.planning.AcademicPlanningDtos.CurriculumItemRequest;
import com.sse.app.academic.planning.AcademicPlanningDtos.NewVersionRequest;
import com.sse.app.academic.planning.AcademicPlanningDtos.PlanRequest;
import com.sse.app.academic.planning.AcademicPlanningDtos.PlanStageRequest;
import com.sse.app.academic.structure.AcademicYear;
import com.sse.app.academic.structure.Room;
import com.sse.app.academic.structure.Semester;
import com.sse.app.academic.structure.StructureService;
import com.sse.app.academic.structure.Subject;
import com.sse.app.common.ApiException;
import com.sse.app.identity.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AcademicPlanningServiceTest {
    @Mock AcademicTrainingPlanRepository plans;
    @Mock AcademicTrainingPlanSubjectRepository planSubjects;
    @Mock AcademicExamScheduleRepository exams;
    @Mock AcademicTrainingPlanStageRepository stages;
    @Mock AcademicCurriculumItemRepository curriculum;
    @Mock AcademicTrainingPlanSpecialWeekRepository specialWeeks;
    @Mock StructureService structure;
    @Mock UserRepository users;

    private AcademicPlanningService service;
    private AcademicTrainingPlan draft;
    private Semester semester;

    @BeforeEach
    void setUp() {
        service = new AcademicPlanningService(
                plans, planSubjects, exams, stages, curriculum,
                specialWeeks, structure, users);
        draft = AcademicTrainingPlan.builder()
                .id("plan-1")
                .academicYearId("ay-1")
                .gradeLevel("K10")
                .name("Kế hoạch khối 10")
                .status("DRAFT")
                .versionNumber(1)
                .maxProgressGapDays(2)
                .build();
        semester = Semester.builder()
                .id("sm-1")
                .academicYearId("ay-1")
                .code("HK1")
                .sequence(1)
                .startDate(LocalDate.of(2026, 9, 5))
                .endDate(LocalDate.of(2027, 1, 15))
                .build();
    }

    @Test
    void createsDraftPlanWithDefaultProgressGap() {
        when(structure.getYear("ay-1")).thenReturn(
                AcademicYear.builder().id("ay-1").build());
        when(plans.findByAcademicYearIdAndGradeLevelOrderByVersionNumberDesc(
                "ay-1", "K10")).thenReturn(List.of());
        when(plans.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        AcademicTrainingPlan created = service.createPlan(
                new PlanRequest(null, "ay-1", "10",
                        "Kế hoạch khối 10", null));

        assertEquals("DRAFT", created.getStatus());
        assertEquals("K10", created.getGradeLevel());
        assertEquals(1, created.getVersionNumber());
        assertEquals(2, created.getMaxProgressGapDays());
    }

    @Test
    void rejectsDuplicateYearAndGradePlan() {
        when(structure.getYear("ay-1")).thenReturn(
                AcademicYear.builder().id("ay-1").build());
        when(plans.findByAcademicYearIdAndGradeLevelOrderByVersionNumberDesc(
                "ay-1", "K10")).thenReturn(List.of(draft));

        assertThrows(ApiException.class, () -> service.createPlan(
                new PlanRequest(null, "ay-1", "K10",
                        "Trùng kế hoạch", 2)));
    }

    @Test
    void rejectsLegacyExamScheduleWrites() {
        ExamScheduleRequest request = new ExamScheduleRequest(
                null, "sm-1", "sj-math", "Thi cuối kỳ",
                LocalDate.of(2026, 12, 20), LocalTime.of(8, 0),
                60, "room-1", null, "PLANNED", null);

        assertThrows(ApiException.class,
                () -> service.addExam("plan-1", request));
    }

    @Test
    void readinessExplainsMissingSecondSemesterAndSubjects() {
        when(plans.findById("plan-1")).thenReturn(Optional.of(draft));
        when(structure.listSemesters("ay-1")).thenReturn(List.of(semester));
        when(planSubjects.findByPlanIdOrderByDisplayOrderAscSubjectIdAsc(
                "plan-1")).thenReturn(List.of());
        when(planSubjects.countByPlanIdAndSemesterId("plan-1", "sm-1"))
                .thenReturn(0L);

        var readiness = service.readiness("plan-1");

        assertEquals(false, readiness.ready());
        assertEquals(2, readiness.issues().size());
    }

    @Test
    void readinessAcceptsCompleteCurriculumAndPeriods() {
        Semester semester2 = Semester.builder()
                .id("sm-2").academicYearId("ay-1").code("HK2")
                .sequence(2)
                .startDate(LocalDate.of(2027, 2, 1))
                .endDate(LocalDate.of(2027, 6, 30)).build();
        AcademicTrainingPlanSubject row =
                AcademicTrainingPlanSubject.builder()
                        .id("row-1").planId("plan-1")
                        .semesterId("sm-1").subjectId("sj-math")
                        .totalPeriods(35).examRequired(true).build();
        AcademicTrainingPlanSubject row2 =
                AcademicTrainingPlanSubject.builder()
                        .id("row-2").planId("plan-1")
                        .semesterId("sm-2").subjectId("sj-math")
                        .totalPeriods(35).examRequired(false).build();
        when(plans.findById("plan-1")).thenReturn(Optional.of(draft));
        when(structure.listSemesters("ay-1"))
                .thenReturn(List.of(semester, semester2));
        when(planSubjects.findByPlanIdOrderByDisplayOrderAscSubjectIdAsc(
                "plan-1")).thenReturn(List.of(row, row2));
        when(planSubjects.findById("row-1")).thenReturn(Optional.of(row));
        when(planSubjects.findById("row-2")).thenReturn(Optional.of(row2));
        when(planSubjects.countByPlanIdAndSemesterId("plan-1", "sm-1"))
                .thenReturn(1L);
        when(planSubjects.countByPlanIdAndSemesterId("plan-1", "sm-2"))
                .thenReturn(1L);
        when(structure.subjectName("sj-math")).thenReturn("Toán");
        when(stages.findByPlanSubjectIdOrderBySequenceAsc(any()))
                .thenAnswer(invocation -> List.of(
                        AcademicTrainingPlanStage.builder()
                                .id("stage-" + invocation.getArgument(0))
                                .planSubjectId(invocation.getArgument(0))
                                .targetPeriods(35).build()));
        when(curriculum.findByPlanSubjectIdOrderBySequenceAsc(any()))
                .thenAnswer(invocation -> List.of(
                        AcademicCurriculumItem.builder()
                                .itemType("CHAPTER").plannedPeriods(0).build(),
                        AcademicCurriculumItem.builder()
                                .itemType("TOPIC").plannedPeriods(0).build(),
                        AcademicCurriculumItem.builder()
                                .itemType("LESSON").plannedPeriods(35).build()));
        when(specialWeeks
                .findByPlanSubjectIdOrderByWeekNumberAscWeekTypeAsc(any()))
                .thenReturn(List.of(
                        AcademicTrainingPlanSpecialWeek.builder()
                                .weekType("EXAM").build(),
                        AcademicTrainingPlanSpecialWeek.builder()
                                .weekType("BUFFER").build()));

        var readiness = service.readiness("plan-1");

        assertEquals(true, readiness.ready());
        assertEquals(2, readiness.stageCount());
        assertEquals(6, readiness.curriculumItemCount());
        assertEquals(4, readiness.specialWeekCount());
    }

    @Test
    void readinessDoesNotRequireAcademicDetailsForEducationalActivities() {
        Semester semester2 = Semester.builder()
                .id("sm-2").academicYearId("ay-1").code("HK2").sequence(2)
                .startDate(LocalDate.of(2027, 2, 1))
                .endDate(LocalDate.of(2027, 6, 30)).build();
        AcademicTrainingPlanSubject hk1 = AcademicTrainingPlanSubject.builder()
                .id("flag-1").planId("plan-1").semesterId("sm-1")
                .subjectId("sj-flag").totalPeriods(18).examRequired(false).build();
        AcademicTrainingPlanSubject hk2 = AcademicTrainingPlanSubject.builder()
                .id("flag-2").planId("plan-1").semesterId("sm-2")
                .subjectId("sj-flag").totalPeriods(17).examRequired(false).build();
        when(plans.findById("plan-1")).thenReturn(Optional.of(draft));
        when(structure.listSemesters("ay-1")).thenReturn(List.of(semester, semester2));
        when(planSubjects.findByPlanIdOrderByDisplayOrderAscSubjectIdAsc("plan-1"))
                .thenReturn(List.of(hk1, hk2));
        when(planSubjects.countByPlanIdAndSemesterId("plan-1", "sm-1")).thenReturn(1L);
        when(planSubjects.countByPlanIdAndSemesterId("plan-1", "sm-2")).thenReturn(1L);
        when(structure.subjectName("sj-flag")).thenReturn("Chào cờ");
        when(structure.getSubject("sj-flag")).thenReturn(Subject.builder()
                .id("sj-flag").subjectType("EDUCATIONAL_ACTIVITY").active(true).build());

        var readiness = service.readiness("plan-1");

        assertEquals(true, readiness.ready());
        assertEquals(0, readiness.stageCount());
        assertEquals(0, readiness.curriculumItemCount());
        assertEquals(0, readiness.specialWeekCount());
    }

    @Test
    void rejectsLessonWithoutTopicParent() {
        AcademicTrainingPlanSubject row =
                AcademicTrainingPlanSubject.builder()
                        .id("row-1").planId("plan-1")
                        .totalPeriods(35).build();
        when(plans.findById("plan-1")).thenReturn(Optional.of(draft));
        when(planSubjects.findById("row-1")).thenReturn(Optional.of(row));

        assertThrows(ApiException.class, () ->
                service.addCurriculumItem("plan-1", "row-1",
                        new CurriculumItemRequest(
                                null, null, "LESSON", "BH1",
                                "Bài học 1", 1, 2, null)));
    }

    @Test
    void createsNextVersionFromLockedPlan() {
        AcademicTrainingPlan locked = AcademicTrainingPlan.builder()
                .id("plan-1").academicYearId("ay-1")
                .gradeLevel("K10").name("Kế hoạch khối 10")
                .status("LOCKED").versionNumber(1)
                .maxProgressGapDays(2).build();
        when(plans.findById("plan-1")).thenReturn(Optional.of(locked));
        when(plans.findByAcademicYearIdAndGradeLevelOrderByVersionNumberDesc(
                "ay-1", "K10")).thenReturn(List.of(locked));
        when(plans.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(planSubjects.findByPlanIdOrderByDisplayOrderAscSubjectIdAsc(
                "plan-1")).thenReturn(List.of());

        AcademicTrainingPlan version = service.createVersion(
                "plan-1", new NewVersionRequest(null));

        assertEquals(2, version.getVersionNumber());
        assertEquals("DRAFT", version.getStatus());
        assertEquals("plan-1", version.getBasedOnPlanId());
    }

    @Test
    void locksOnlyPublishedPlan() {
        AcademicTrainingPlan published = AcademicTrainingPlan.builder()
                .id("plan-1")
                .academicYearId("ay-1")
                .gradeLevel("K10")
                .name("Published plan")
                .status("PUBLISHED")
                .versionNumber(1)
                .build();
        when(plans.findById("plan-1")).thenReturn(Optional.of(published));
        when(plans.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        AcademicTrainingPlan closed = service.lockPlan("plan-1", "admin");

        assertEquals("LOCKED", closed.getStatus());
        when(plans.findById("plan-1")).thenReturn(Optional.of(draft));
        assertThrows(ApiException.class,
                () -> service.lockPlan("plan-1", "admin"));
    }

    @Test
    void refusesToPublishApprovedPlanWhenReadinessHasErrors() {
        AcademicTrainingPlan approved = AcademicTrainingPlan.builder()
                .id("plan-1").academicYearId("ay-1").gradeLevel("K10")
                .name("Kế hoạch khối 10").status("APPROVED").versionNumber(1).build();
        when(plans.findById("plan-1")).thenReturn(Optional.of(approved));
        when(structure.listSemesters("ay-1")).thenReturn(List.of());
        when(planSubjects.findByPlanIdOrderByDisplayOrderAscSubjectIdAsc("plan-1"))
                .thenReturn(List.of());

        assertThrows(ApiException.class, () -> service.publishPlan("plan-1", "admin"));
    }

    @Test
    void rejectsOverlappingPlanStages() {
        AcademicTrainingPlanSubject row = AcademicTrainingPlanSubject.builder()
                .id("row-1").planId("plan-1").totalPeriods(35)
                .startDate(LocalDate.of(2026, 9, 1))
                .endDate(LocalDate.of(2027, 1, 31)).build();
        AcademicTrainingPlanStage existing = AcademicTrainingPlanStage.builder()
                .id("stage-1").planSubjectId("row-1").code("GD1").name("Giai đoạn 1")
                .sequence(1).startDate(LocalDate.of(2026, 9, 1))
                .endDate(LocalDate.of(2026, 10, 15)).targetPeriods(15).build();
        when(plans.findById("plan-1")).thenReturn(Optional.of(draft));
        when(planSubjects.findById("row-1")).thenReturn(Optional.of(row));
        when(stages.findByPlanSubjectIdAndCodeIgnoreCase("row-1", "GD2"))
                .thenReturn(Optional.empty());
        when(stages.findByPlanSubjectIdOrderBySequenceAsc("row-1"))
                .thenReturn(List.of(existing));

        PlanStageRequest request = new PlanStageRequest(null, "GD2", "Giai đoạn 2", 2,
                LocalDate.of(2026, 10, 1), LocalDate.of(2026, 11, 1), 10, null);

        assertThrows(ApiException.class, () -> service.addStage("plan-1", "row-1", request));
    }
}
