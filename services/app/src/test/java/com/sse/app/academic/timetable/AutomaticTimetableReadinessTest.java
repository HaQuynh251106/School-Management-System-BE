package com.sse.app.academic.timetable;

import com.sse.app.academic.planning.TeacherStaffingDtos.StaffingPolicyDto;
import com.sse.app.academic.planning.TeacherStaffingDtos.TeacherStaffingAnalysis;
import com.sse.app.academic.planning.TeacherStaffingService;
import com.sse.app.academic.structure.AcademicYear;
import com.sse.app.academic.structure.Room;
import com.sse.app.academic.structure.SchoolClass;
import com.sse.app.academic.structure.Semester;
import com.sse.app.academic.structure.StructureService;
import com.sse.app.academic.structure.Subject;
import com.sse.app.academic.teaching.TeacherClassSubject;
import com.sse.app.academic.teaching.TeachingAssignmentRepository;
import com.sse.app.event.DomainEventPublisher;
import com.sse.app.identity.User;
import com.sse.app.identity.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AutomaticTimetableReadinessTest {
    @Mock private TimetableScheduleRepository schedules;
    @Mock private TimetableDraftSlotRepository draftSlots;
    @Mock private TimetableRepository liveSlots;
    @Mock private TeachingAssignmentRepository assignments;
    @Mock private StructureService structure;
    @Mock private UserService users;
    @Mock private DomainEventPublisher events;
    @Mock private TimetablePlanSourceService planSources;
    @Mock private TeacherStaffingService staffing;

    private AutomaticTimetableService service;

    @BeforeEach
    void setUp() {
        service = new AutomaticTimetableService(
                schedules, draftSlots, liveSlots, assignments, structure,
                users, events, planSources, staffing);
    }

    @Test
    void readinessNamesTheExactClassAndSubjectMissingAnAssignment() {
        SchoolClass schoolClass = SchoolClass.builder()
                .id("class-10a1").code("10A1").gradeLevel("K10")
                .academicYearId("year-1").homeRoomId("room-10a1")
                .homeroomTeacherId("teacher-math").status("ACTIVE").build();
        TeacherClassSubject mathAssignment = TeacherClassSubject.builder()
                .id("assignment-math").classId("class-10a1").classCode("10A1")
                .subjectId("math").subjectName("Toán")
                .teacherId("teacher-math").teacherName("Nguyễn Văn Toán")
                .semesterId("semester-1").weeklyPeriods(3).status("ACTIVE").build();
        var math = new TimetablePlanSourceService.SubjectSnapshot(
                "ps-math", "math", 3, 54);
        var literature = new TimetablePlanSourceService.SubjectSnapshot(
                "ps-literature", "literature", 3, 54);
        var source = new TimetablePlanSourceService.PlanSnapshot(
                "plan-k10", "program-2018", 1, "K10", "PUBLISHED",
                null, "semester-1", List.of(math, literature));

        when(structure.getYear("year-1")).thenReturn(AcademicYear.builder()
                .id("year-1").status("ACTIVE").build());
        when(structure.getSemester("semester-1")).thenReturn(Semester.builder()
                .id("semester-1").academicYearId("year-1").build());
        when(structure.listClasses("year-1", "K10")).thenReturn(List.of(schoolClass));
        when(planSources.resolve("year-1", "semester-1", Set.of("K10")))
                .thenReturn(List.of(source));
        when(planSources.applicableSubjects(List.of(source), schoolClass))
                .thenReturn(List.of(math, literature));
        when(planSources.weeklyPeriods(List.of(source), "K10", "math")).thenReturn(3);
        when(planSources.summary(List.of(source))).thenReturn("K10 v1");
        when(assignments.findBySemesterIdAndStatus("semester-1", "ACTIVE"))
                .thenReturn(List.of(mathAssignment));
        when(structure.getSubject("math")).thenReturn(Subject.builder()
                .id("math").code("MATH").name("Toán").active(true).build());
        when(structure.subjectName("literature")).thenReturn("Ngữ văn");
        when(users.getById("teacher-math")).thenReturn(User.builder()
                .id("teacher-math").fullName("Nguyễn Văn Toán")
                .mainSubject("MATH").role("TEACHER").status("ACTIVE").build());
        when(structure.listRooms()).thenReturn(List.of(Room.builder()
                .id("room-10a1").code("10A1").active(true).build()));
        when(staffing.analyze("year-1", "semester-1", "K10"))
                .thenReturn(staffingAnalysis());

        var result = service.generationReadiness("year-1", "semester-1", "K10");

        assertTrue(result.issues().stream().anyMatch(issue ->
                "CLASS_SUBJECT_ASSIGNMENT_MISSING".equals(issue.code())
                        && "class-10a1".equals(issue.classId())
                        && "literature".equals(issue.subjectId())
                        && issue.message().contains("10A1 thiếu phân công môn Ngữ văn")));
    }

    private TeacherStaffingAnalysis staffingAnalysis() {
        StaffingPolicyDto policy = new StaffingPolicyDto(
                "year-1", "PUBLIC_REGULAR", "THPT công lập",
                17, 35, new BigDecimal("2.25"), true);
        return new TeacherStaffingAnalysis(
                "year-1", "semester-1", "K10",
                1, 1, 1, 1, 1,
                BigDecimal.ONE, 1, new BigDecimal("2.4"), 3,
                true, true, 105, 54, 3,
                policy, List.of(), List.of(), List.of());
    }
}
