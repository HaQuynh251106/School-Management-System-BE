package com.sse.app.academic.exam;

import com.sse.app.academic.exam.ExamDtos.ExamPeriodRequest;
import com.sse.app.academic.exam.ExamDtos.TeacherUnavailabilityRequest;
import com.sse.app.academic.planning.ExamAssessmentSourceService;
import com.sse.app.academic.planning.ExamAssessmentSourceService.SourceReadiness;
import com.sse.app.academic.structure.AcademicYear;
import com.sse.app.academic.structure.Room;
import com.sse.app.academic.structure.Semester;
import com.sse.app.academic.structure.StructureService;
import com.sse.app.academic.teaching.TeachingAssignmentRepository;
import com.sse.app.academic.timetable.TimetableService;
import com.sse.app.common.ApiException;
import com.sse.app.event.DomainEventPublisher;
import com.sse.app.identity.User;
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

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ExamScheduleServiceTest {
    @Mock ExamPeriodRepository periods;
    @Mock ExamScheduleVersionRepository versions;
    @Mock ExamSessionRepository sessions;
    @Mock ExamRoomAssignmentRepository roomAssignments;
    @Mock ExamRoomStudentRepository roomStudents;
    @Mock ExamTeacherUnavailabilityRepository unavailability;
    @Mock StructureService structure;
    @Mock UserRepository users;
    @Mock TeachingAssignmentRepository teachingAssignments;
    @Mock TimetableService timetable;
    @Mock DomainEventPublisher events;
    @Mock ExamAssessmentSourceService sources;

    private ExamScheduleService service;
    private AcademicYear year;
    private Semester semester;

    @BeforeEach
    void setUp() {
        service = new ExamScheduleService(periods, versions, sessions, roomAssignments,
                roomStudents, unavailability, structure, users, teachingAssignments,
                timetable, events, sources);
        year = AcademicYear.builder().id("ay-1").name("2027-2028")
                .startDate(LocalDate.of(2027, 9, 1)).endDate(LocalDate.of(2028, 6, 30)).build();
        semester = Semester.builder().id("sem-1").academicYearId("ay-1")
                .name("Học kỳ 1").startDate(LocalDate.of(2027, 9, 1))
                .endDate(LocalDate.of(2028, 1, 31)).build();
        lenient().when(structure.getYear("ay-1")).thenReturn(year);
        lenient().when(structure.getSemester("sem-1")).thenReturn(semester);
        lenient().when(structure.listHolidays("ay-1")).thenReturn(List.of());
    }

    @Test
    void rejectsPeriodBeforeGd3AssessmentSourceIsReady() {
        when(sources.readiness(anyString(), anyString(), anyString(), anyList()))
                .thenReturn(readiness(false, 0, 0, 0, List.of("Thiếu kế hoạch cuối kỳ")));

        ApiException error = assertThrows(ApiException.class,
                () -> service.createPeriod(request(LocalDate.of(2028, 1, 15),
                        LocalDate.of(2028, 1, 20)), "admin"));

        assertTrue(error.getMessage().contains("GĐ3") || error.getMessage().contains("GÄ3"));
        verify(periods, never()).save(any());
    }

    @Test
    void rejectsPeriodThatHasFewerDaysThanGd3Requires() {
        when(sources.readiness(anyString(), anyString(), anyString(), anyList()))
                .thenReturn(readiness(true, 6, 6, 3, List.of()));

        ApiException error = assertThrows(ApiException.class,
                () -> service.createPeriod(request(LocalDate.of(2028, 1, 17),
                        LocalDate.of(2028, 1, 17)), "admin"));

        assertTrue(error.getMessage().contains("3"));
        verify(periods, never()).save(any());
    }

    @Test
    void storesTeacherUnavailabilityAsAnExactDateRange() {
        ExamPeriod period = ExamPeriod.builder().id("period-1").status("DRAFT")
                .startDate(LocalDate.of(2028, 1, 15)).endDate(LocalDate.of(2028, 1, 25)).build();
        ExamScheduleVersion draft = ExamScheduleVersion.builder().id("version-1")
                .examPeriodId("period-1").status("DRAFT").build();
        User teacher = User.builder().id("teacher-1").role("TEACHER")
                .status("ACTIVE").fullName("Giáo viên 1").build();
        when(periods.findById("period-1")).thenReturn(Optional.of(period));
        when(versions.findByExamPeriodIdAndStatus("period-1", "DRAFT"))
                .thenReturn(Optional.of(draft));
        when(versions.findByExamPeriodIdOrderByVersionNoDesc("period-1"))
                .thenReturn(List.of(draft));
        when(versions.findById("version-1")).thenReturn(Optional.of(draft));
        when(users.findById("teacher-1")).thenReturn(Optional.of(teacher));
        when(unavailability.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        var result = service.addUnavailability("period-1",
                new TeacherUnavailabilityRequest("teacher-1",
                        LocalDate.of(2028, 1, 18), LocalDate.of(2028, 1, 20),
                        LocalTime.of(7, 0), LocalTime.of(11, 30),
                        "LEAVE", "Nghỉ phép"), "admin");

        assertEquals(LocalDate.of(2028, 1, 18), result.unavailableDate());
        assertEquals(LocalDate.of(2028, 1, 20), result.endDate());
        verify(versions).save(draft);
    }

    @Test
    void rejectsTeacherLeaveOutsideTheExamPeriod() {
        ExamPeriod period = ExamPeriod.builder().id("period-1").status("DRAFT")
                .startDate(LocalDate.of(2028, 1, 15)).endDate(LocalDate.of(2028, 1, 25)).build();
        ExamScheduleVersion draft = ExamScheduleVersion.builder().id("version-1")
                .examPeriodId("period-1").status("DRAFT").build();
        User teacher = User.builder().id("teacher-1").role("TEACHER")
                .status("ACTIVE").fullName("Giáo viên 1").build();
        when(periods.findById("period-1")).thenReturn(Optional.of(period));
        when(versions.findByExamPeriodIdAndStatus("period-1", "DRAFT"))
                .thenReturn(Optional.of(draft));
        when(users.findById("teacher-1")).thenReturn(Optional.of(teacher));

        assertThrows(ApiException.class, () -> service.addUnavailability("period-1",
                new TeacherUnavailabilityRequest("teacher-1",
                        LocalDate.of(2028, 1, 24), LocalDate.of(2028, 1, 27),
                        null, null, "LEAVE", "Nghỉ phép"), "admin"));
        verify(unavailability, never()).save(any());
    }

    @Test
    void publishedAndClosedPeriodsCanBeRecalledForEditing() {
        assertTrue(ExamScheduleService.isRecallablePeriodStatus("PUBLISHED"));
        assertTrue(ExamScheduleService.isRecallablePeriodStatus("CLOSED"));
        assertFalse(ExamScheduleService.isRecallablePeriodStatus("DRAFT"));
        assertFalse(ExamScheduleService.isRecallablePeriodStatus("CANCELLED"));
    }

    private ExamPeriodRequest request(LocalDate start, LocalDate end) {
        return new ExamPeriodRequest("CK1-2728", "Cuối kỳ 1", "ay-1", "sem-1",
                "FINAL", List.of("K10", "K11", "K12"), false, start, end);
    }

    private SourceReadiness readiness(boolean ready, int sourceCount,
                                      int subjectCount, int requiredDays,
                                      List<String> issues) {
        return new SourceReadiness(ready, sourceCount, subjectCount, requiredDays,
                LocalDate.of(2028, 1, 15), LocalDate.of(2028, 1, 20),
                List.of(LocalDate.of(2028, 1, 15)), List.of(), issues);
    }
}
