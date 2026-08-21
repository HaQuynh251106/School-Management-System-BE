package com.sse.app.dashboard;

import com.sse.app.academic.assignment.AssignmentService;
import com.sse.app.academic.attendance.AttendanceService;
import com.sse.app.academic.grade.GradeService;
import com.sse.app.academic.structure.StructureService;
import com.sse.app.academic.structure.AcademicYear;
import com.sse.app.academic.structure.SchoolClass;
import com.sse.app.academic.teaching.TeachingAssignmentRepository;
import com.sse.app.academic.timetable.TimetableService;
import com.sse.app.finance.FinanceService;
import com.sse.app.identity.ParentStudentRepository;
import com.sse.app.identity.User;
import com.sse.app.identity.UserRepository;
import com.sse.app.notification.NotificationService;
import com.sse.app.security.CurrentUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
class DashboardServiceTest {
    @Mock private UserRepository users;
    @Mock private ParentStudentRepository parentStudents;
    @Mock private StructureService structure;
    @Mock private TimetableService timetable;
    @Mock private TeachingAssignmentRepository teachingAssignments;
    @Mock private AttendanceService attendance;
    @Mock private GradeService grades;
    @Mock private AssignmentService assignments;
    @Mock private NotificationService notifications;
    @Mock private FinanceService finance;

    private DashboardService service;

    @BeforeEach
    void setUp() {
        service = new DashboardService(users, parentStudents, structure, timetable,
                teachingAssignments, attendance, grades, assignments, notifications, finance);
        lenient().when(structure.listClasses(null, null)).thenReturn(List.of());
        when(timetable.allSlots()).thenReturn(List.of());
        when(teachingAssignments.findAll()).thenReturn(List.of());
        when(attendance.list(null, null, null, null)).thenReturn(List.of());
        when(grades.allGrades()).thenReturn(List.of());
        when(assignments.list(null, null, null, false)).thenReturn(List.of());
        when(notifications.failedNotifications()).thenReturn(List.of());
        when(finance.dashboardInvoices(any())).thenReturn(List.of());
    }

    @Test
    void adminMetricsAndRoleDistributionExcludeDeletedFinanceAdmin() {
        when(users.findAll()).thenReturn(List.of(
                User.builder().id("admin-main").role("ADMIN").status("ACTIVE").build(),
                User.builder().id("admin-finance").role("ADMIN").status("DELETED").build(),
                User.builder().id("teacher-1").role("TEACHER").status("ACTIVE").build()
        ));

        var dashboard = service.forCurrentUser(new CurrentUser("admin-main", "admin", "ADMIN"));

        var activeUsers = dashboard.metrics().stream()
                .filter(metric -> "users".equals(metric.key()))
                .findFirst().orElseThrow();
        assertEquals(2, activeUsers.value());

        var roleChart = dashboard.charts().stream()
                .filter(chart -> "Phân bổ tài khoản".equals(chart.title()))
                .findFirst().orElseThrow();
        var admin = roleChart.data().stream()
                .filter(row -> "Quản trị".equals(row.label()))
                .findFirst().orElseThrow();
        assertEquals(1, admin.value());
    }

    @Test
    void adminClassMetricUsesOnlyTheActiveAcademicYear() {
        when(users.findAll()).thenReturn(List.of(
                User.builder().id("admin-main").role("ADMIN").status("ACTIVE").build()));
        when(structure.listYears()).thenReturn(List.of(
                AcademicYear.builder().id("year-old").status("CLOSED").build(),
                AcademicYear.builder().id("year-active").status("ACTIVE").build()));
        when(structure.listClasses("year-active", null)).thenReturn(List.of(
                SchoolClass.builder().id("10a1").status("ACTIVE").build(),
                SchoolClass.builder().id("10a2").status("ACTIVE").build(),
                SchoolClass.builder().id("10a3").status("INACTIVE").build()));

        var dashboard = service.forCurrentUser(new CurrentUser("admin-main", "admin", "ADMIN"));

        var classes = dashboard.metrics().stream()
                .filter(metric -> "classes".equals(metric.key()))
                .findFirst().orElseThrow();
        assertEquals(3, classes.value());
        assertEquals("2 đang hoạt động · 1 dự kiến", classes.hint());
    }
}
