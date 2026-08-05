package com.sse.app.academic.attendance;

import com.sse.app.academic.teaching.TeachingAssignmentService;
import com.sse.app.academic.timetable.TimetableService;
import com.sse.app.audit.AuditService;
import com.sse.app.event.DomainEventPublisher;
import com.sse.app.identity.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AttendanceServiceTest {
    @Mock private AttendanceRepository records;
    @Mock private TimetableService timetable;
    @Mock private TeachingAssignmentService teachingAssignments;
    @Mock private UserService users;
    @Mock private DomainEventPublisher events;
    @Mock private AttendanceExcuseRequestRepository excuseRequests;
    @Mock private AuditService audit;

    private AttendanceService service;

    @BeforeEach
    void setUp() {
        service = new AttendanceService(records, timetable, teachingAssignments,
                users, events, excuseRequests, audit);
    }

    @Test
    void summaryCountsLateMinutesAndRepeatedViolations() {
        LocalDate from = LocalDate.of(2026, 7, 1);
        LocalDate to = LocalDate.of(2026, 7, 31);
        when(records.findByStudentIdAndDateBetweenOrderByDateDesc(
                "student-1", from, to)).thenReturn(List.of(
                record("PRESENT", null),
                record("LATE", 10),
                record("LATE", 15),
                record("ABSENT_UNEXCUSED", null)));

        var result = service.summary("student-1", from, to);

        assertEquals(25, result.totalLateMinutes());
        assertEquals(2, result.late());
        assertTrue(result.repeatedViolation());
        assertEquals(75.0, result.attendanceRate());
    }

    private AttendanceRecord record(String status, Integer lateMinutes) {
        return AttendanceRecord.builder().studentId("student-1")
                .date(LocalDate.of(2026, 7, 1))
                .status(status).lateMinutes(lateMinutes).build();
    }
}
