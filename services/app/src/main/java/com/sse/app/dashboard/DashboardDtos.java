package com.sse.app.dashboard;

import java.util.List;

public final class DashboardDtos {
    private DashboardDtos() {}

    public record Metric(String key, String label, double value, String format, String hint, String tone) {}

    public record Datum(String label, double value) {}

    public record Chart(String title, String subtitle, String type, String suffix, double max, List<Datum> data) {}

    public record WorkItem(
            String key,
            String title,
            String detail,
            double value,
            String unit,
            String severity,
            String pageCode
    ) {}

    public record CalendarItem(
            String id,
            String date,
            String title,
            String type,
            String detail,
            String pageCode
    ) {}

    public record AdminOverview(
            String academicYear,
            String academicYearStatus,
            String semester,
            String semesterStatus,
            String updatedAt,
            int activeStudents,
            int activeTeachers,
            int activeParents,
            int unassignedStudents,
            int inactiveAccounts,
            int activeClasses,
            int classesWithoutHomeroom,
            int attendanceRecorded,
            int present,
            int excusedAbsences,
            int unexcusedAbsences,
            int late,
            int scheduledPeriods,
            int requiredPeriods,
            double timetableCoverage,
            int upcomingExams,
            int draftExams,
            double totalReceivables,
            double collectedAmount,
            double outstandingAmount,
            int overdueInvoices,
            List<CalendarItem> calendarItems,
            List<WorkItem> workItems
    ) {}

    public record RoleOverview(
            String role,
            String academicYear,
            String academicYearStatus,
            String semester,
            String semesterStatus,
            String updatedAt,
            List<CalendarItem> calendarItems,
            List<WorkItem> workItems
    ) {}

    public record TeacherLesson(
            String slotId,
            int periodNo,
            String startTime,
            String endTime,
            String classId,
            String classCode,
            String subjectId,
            String subjectName,
            String roomCode,
            String status,
            boolean attendanceRecorded
    ) {}

    public record AttentionStudent(
            String studentId,
            String studentCode,
            String studentName,
            String classId,
            String classCode,
            int attendanceAlerts,
            int missingAssignments,
            Double subjectAverage,
            String severity,
            String reason
    ) {}

    public record TeacherOverview(
            boolean homeroomTeacher,
            List<String> homeroomClassCodes,
            List<TeacherLesson> todayLessons,
            List<AttentionStudent> attentionStudents
    ) {}

    public record Response(
            List<Metric> metrics,
            List<Chart> charts,
            AdminOverview adminOverview,
            RoleOverview roleOverview,
            TeacherOverview teacherOverview
    ) {
        public Response(List<Metric> metrics, List<Chart> charts) {
            this(metrics, charts, null, null, null);
        }

        public Response(List<Metric> metrics, List<Chart> charts, AdminOverview adminOverview) {
            this(metrics, charts, adminOverview, null, null);
        }

        public Response(List<Metric> metrics, List<Chart> charts, RoleOverview roleOverview) {
            this(metrics, charts, null, roleOverview, null);
        }

        public Response(List<Metric> metrics, List<Chart> charts, RoleOverview roleOverview,
                        TeacherOverview teacherOverview) {
            this(metrics, charts, null, roleOverview, teacherOverview);
        }
    }
}
