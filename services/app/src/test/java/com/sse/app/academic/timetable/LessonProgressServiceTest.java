package com.sse.app.academic.timetable;

import com.sse.app.academic.planning.AcademicPlanningService;
import com.sse.app.academic.structure.SchoolClass;
import com.sse.app.academic.structure.StructureService;
import com.sse.app.academic.teaching.TeachingAssignmentRepository;
import com.sse.app.event.DomainEventPublisher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class LessonProgressServiceTest {
    @Mock ClassLessonProgressRepository progress;
    @Mock TimetableMakeupProposalRepository makeup;
    @Mock TimetableScheduleRepository schedules;
    @Mock TimetableRepository liveSlots;
    @Mock StructureService structure;
    @Mock TeachingAssignmentRepository assignments;
    @Mock AcademicPlanningService planning;
    @Mock DomainEventPublisher events;
    @Mock TimetablePlanSourceService planSources;

    @Test
    void approvingMakeupPublishesRealtimeNotificationEventForClassAndTeacher() {
        TimetableMakeupProposal proposal = TimetableMakeupProposal.builder()
                .id("makeup-1").scheduleId("schedule-1").classId("class-1")
                .subjectId("math").teacherId("teacher-1").roomCode("P101")
                .missedDate(LocalDate.of(2028, 1, 3)).missedPeriodNo(1)
                .proposedDate(LocalDate.of(2028, 1, 8)).proposedPeriodNo(7)
                .status("PROPOSED").build();
        when(makeup.findById("makeup-1")).thenReturn(Optional.of(proposal));
        when(makeup.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(makeup.findAll()).thenReturn(java.util.List.of(proposal));
        when(liveSlots.findByDayOfWeekAndPeriodNo("SAT", 7))
                .thenReturn(java.util.List.of());
        when(structure.getClass("class-1")).thenReturn(
                SchoolClass.builder().id("class-1").code("10A1").build());
        when(structure.subjectName("math")).thenReturn("Toán");

        LessonProgressService service = new LessonProgressService(progress, makeup,
                schedules, liveSlots, structure, assignments, planning, events, planSources);
        TimetableMakeupProposal saved = service.reviewMakeup(
                "makeup-1", "APPROVED", null, "admin");

        assertEquals("APPROVED", saved.getStatus());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
        verify(events).publish(eq("academic.timetable.makeup_approved"), eq("admin"),
                eq("timetable_makeup"), eq("makeup-1"), payload.capture());
        assertEquals("class-1", payload.getValue().get("classId"));
        assertEquals(java.util.List.of("teacher-1"), payload.getValue().get("teacherIds"));
    }

    @Test
    void schedulePublicationRepositoryUsesDatabaseWriteLock() throws Exception {
        var method = TimetableScheduleRepository.class
                .getDeclaredMethod("findByIdForUpdate", String.class);
        var lock = method.getAnnotation(org.springframework.data.jpa.repository.Lock.class);
        assertEquals(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE, lock.value());
    }
}
