package com.sse.app.academic.timetable;

import com.sse.app.academic.structure.StructureService;
import com.sse.app.academic.structure.SchoolClass;
import com.sse.app.academic.teaching.TeacherClassSubject;
import com.sse.app.academic.teaching.TeachingAssignmentRepository;
import com.sse.app.academic.timetable.TimetableDtos.CreateSlotRequest;
import com.sse.app.common.ApiException;
import com.sse.app.event.DomainEventPublisher;
import com.sse.app.identity.User;
import com.sse.app.identity.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TimetableServiceTest {

    @Mock private TimetableRepository slots;
    @Mock private StructureService structure;
    @Mock private UserService users;
    @Mock private DomainEventPublisher events;
    @Mock private TeachingAssignmentRepository teachingAssignments;
    @Mock private TimetableScheduleRepository schedules;
    @Mock private TimetablePlanSourceService planSources;

    private TimetableService service;

    @BeforeEach
    void setUp() {
        service = new TimetableService(slots, structure, users, events,
                teachingAssignments, schedules, planSources);
    }

    @Test
    void createRejectsSlotWithoutTeachingAssignment() {
        CreateSlotRequest request = request("teacher-1");
        when(teachingAssignments.findByClassIdAndSubjectIdAndSemesterIdAndStatus(
                "class-1", "subject-1", "semester-1", "ACTIVE")).thenReturn(Optional.empty());

        ApiException error = assertThrows(ApiException.class, () -> service.create(request));

        assertEquals(HttpStatus.FORBIDDEN, error.getStatus());
        verify(slots, never()).save(any());
    }

    @Test
    void createRejectsTeacherDifferentFromAssignment() {
        CreateSlotRequest request = request("teacher-2");
        when(teachingAssignments.findByClassIdAndSubjectIdAndSemesterIdAndStatus(
                "class-1", "subject-1", "semester-1", "ACTIVE"))
                .thenReturn(Optional.of(assignment(2)));

        ApiException error = assertThrows(ApiException.class, () -> service.create(request));

        assertEquals(HttpStatus.FORBIDDEN, error.getStatus());
        verify(slots, never()).save(any());
    }

    @Test
    void createRejectsPeriodsAboveWeeklyPlan() {
        CreateSlotRequest request = request("teacher-1");
        when(teachingAssignments.findByClassIdAndSubjectIdAndSemesterIdAndStatus(
                "class-1", "subject-1", "semester-1", "ACTIVE"))
                .thenReturn(Optional.of(assignment(2)));
        when(slots.findByClassId("class-1")).thenReturn(List.of(existing(1), existing(2)));
        when(structure.getClass("class-1")).thenReturn(SchoolClass.builder()
                .id("class-1").academicYearId("year-1").gradeLevel("K10").build());
        when(planSources.resolve("year-1", "semester-1", java.util.Set.of("K10")))
                .thenReturn(List.of());
        when(planSources.weeklyPeriods(any(), org.mockito.ArgumentMatchers.eq("K10"),
                org.mockito.ArgumentMatchers.eq("subject-1"))).thenReturn(2);

        ApiException error = assertThrows(ApiException.class, () -> service.create(request));

        assertEquals(HttpStatus.CONFLICT, error.getStatus());
        verify(slots, never()).save(any());
    }

    private CreateSlotRequest request(String teacherId) {
        return new CreateSlotRequest(null, "class-1", "subject-1", teacherId, "P101",
                "MON", 3, "08:45", "09:30", "semester-1", false);
    }

    private TeacherClassSubject assignment(int weeklyPeriods) {
        return TeacherClassSubject.builder()
                .id("assignment-1")
                .teacherId("teacher-1")
                .classId("class-1")
                .subjectId("subject-1")
                .semesterId("semester-1")
                .weeklyPeriods(weeklyPeriods)
                .status("ACTIVE")
                .build();
    }

    private TimetableSlot existing(int period) {
        return TimetableSlot.builder()
                .id("slot-" + period)
                .teacherId("teacher-1")
                .classId("class-1")
                .subjectId("subject-1")
                .semesterId("semester-1")
                .dayOfWeek("TUE")
                .periodNo(period)
                .build();
    }
}
