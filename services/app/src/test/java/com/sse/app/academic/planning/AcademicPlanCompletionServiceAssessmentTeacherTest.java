package com.sse.app.academic.planning;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sse.app.academic.planning.AcademicPlanningDtos.AssessmentPlanRequest;
import com.sse.app.academic.structure.Semester;
import com.sse.app.academic.structure.StructureService;
import com.sse.app.academic.teaching.TeachingAssignmentService;
import com.sse.app.event.DomainEventPublisher;
import com.sse.app.identity.User;
import com.sse.app.identity.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AcademicPlanCompletionServiceAssessmentTeacherTest {
    @Mock AcademicTrainingPlanRepository plans;
    @Mock AcademicTrainingPlanSubjectRepository planSubjects;
    @Mock AcademicTrainingPlanStageRepository stages;
    @Mock AcademicCurriculumItemRepository curriculum;
    @Mock AcademicTrainingPlanSpecialWeekRepository specialWeeks;
    @Mock AcademicCurriculumDistributionRepository distributions;
    @Mock AcademicAssessmentPlanRepository assessments;
    @Mock AcademicAssessmentPlanTeacherRepository assessmentTeachers;
    @Mock AcademicPlanApprovalHistoryRepository approvalHistory;
    @Mock EducationProgramRepository programs;
    @Mock EducationProgramSubjectRepository programSubjects;
    @Mock ClassSubjectCombinationRepository classCombinations;
    @Mock SubjectCombinationSubjectRepository combinationSubjects;
    @Mock TeacherSubjectCapabilityRepository capabilities;
    @Mock StructureService structure;
    @Mock TeachingAssignmentService teaching;
    @Mock DomainEventPublisher events;
    @Mock UserRepository users;

    private AcademicPlanCompletionService service;

    @BeforeEach
    void setUp() {
        service = new AcademicPlanCompletionService(
                plans, planSubjects, stages, curriculum, specialWeeks, distributions,
                assessments, assessmentTeachers, approvalHistory, programs, programSubjects,
                classCombinations, combinationSubjects, capabilities, structure, teaching,
                events, users, new ObjectMapper());
    }

    @Test
    void savesSeveralResponsibleTeachersAndMarksOnlyTheFirstAsPrimary() {
        AcademicTrainingPlan plan = AcademicTrainingPlan.builder()
                .id("plan-1").academicYearId("year-1").gradeLevel("K10")
                .name("Kế hoạch K10").status("DRAFT").versionNumber(1).build();
        Semester semester = Semester.builder().id("semester-1").academicYearId("year-1")
                .sequence(1).startDate(LocalDate.of(2027, 9, 1))
                .endDate(LocalDate.of(2028, 1, 31)).build();
        AcademicTrainingPlanSubject planSubject = AcademicTrainingPlanSubject.builder()
                .id("plan-subject-1").planId("plan-1").semesterId("semester-1")
                .subjectId("math").totalPeriods(35).build();
        User teacherOne = User.builder().id("teacher-1").fullName("Giáo viên Một")
                .role("TEACHER").status("ACTIVE").build();
        User teacherTwo = User.builder().id("teacher-2").fullName("Giáo viên Hai")
                .role("TEACHER").status("ACTIVE").build();
        TeacherSubjectCapability capabilityOne = TeacherSubjectCapability.builder()
                .id("cap-1").teacherId("teacher-1").subjectId("math").active(true).build();
        TeacherSubjectCapability capabilityTwo = TeacherSubjectCapability.builder()
                .id("cap-2").teacherId("teacher-2").subjectId("math").active(true).build();
        List<AcademicAssessmentPlanTeacher> savedTeachers = new ArrayList<>();

        when(plans.findById("plan-1")).thenReturn(Optional.of(plan));
        when(structure.getSemester("semester-1")).thenReturn(semester);
        when(planSubjects.findByPlanIdAndSemesterIdAndSubjectId(
                "plan-1", "semester-1", "math")).thenReturn(Optional.of(planSubject));
        when(users.findById("teacher-1")).thenReturn(Optional.of(teacherOne));
        when(users.findById("teacher-2")).thenReturn(Optional.of(teacherTwo));
        when(capabilities.findByTeacherIdAndSubjectId("teacher-1", "math"))
                .thenReturn(Optional.of(capabilityOne));
        when(capabilities.findByTeacherIdAndSubjectId("teacher-2", "math"))
                .thenReturn(Optional.of(capabilityTwo));
        when(assessments.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(specialWeeks.findByPlanSubjectIdAndWeekNumber("plan-subject-1", 8))
                .thenReturn(Optional.empty());
        when(specialWeeks.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(assessmentTeachers.save(any())).thenAnswer(invocation -> {
            AcademicAssessmentPlanTeacher saved = invocation.getArgument(0);
            savedTeachers.add(saved);
            return saved;
        });
        when(assessmentTeachers.findByAssessmentPlanIdOrderByPrimaryTeacherDescTeacherIdAsc(any()))
                .thenAnswer(invocation -> savedTeachers.stream()
                        .sorted(java.util.Comparator.comparing(AcademicAssessmentPlanTeacher::isPrimaryTeacher)
                                .reversed())
                        .toList());

        AcademicAssessmentPlan saved = service.saveAssessment("plan-1", null,
                new AssessmentPlanRequest(null, "semester-1", null, "math", "MIDTERM",
                        "Giữa kỳ Toán", "WRITTEN", List.of(), "SCORE", 8, 45,
                        null, List.of("teacher-1", "teacher-2", "teacher-1"), null));

        verify(assessmentTeachers).deleteByAssessmentPlanId(saved.getId());
        assertEquals(List.of("teacher-1", "teacher-2"), saved.getTeacherIds());
        assertEquals("teacher-1", saved.getTeacherId());
        assertEquals(2, savedTeachers.size());
        assertTrue(savedTeachers.get(0).isPrimaryTeacher());
        assertFalse(savedTeachers.get(1).isPrimaryTeacher());
    }
}
