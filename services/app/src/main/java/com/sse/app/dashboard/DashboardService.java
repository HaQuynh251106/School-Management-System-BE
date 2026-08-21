package com.sse.app.dashboard;

import com.sse.app.academic.assignment.Assignment;
import com.sse.app.academic.assignment.AssignmentService;
import com.sse.app.academic.assignment.AssignmentSubmission;
import com.sse.app.academic.attendance.AttendanceRecord;
import com.sse.app.academic.attendance.AttendanceService;
import com.sse.app.academic.grade.Grade;
import com.sse.app.academic.grade.GradeService;
import com.sse.app.academic.structure.SchoolClass;
import com.sse.app.academic.structure.StructureService;
import com.sse.app.academic.timetable.TimetableService;
import com.sse.app.academic.timetable.TimetableSlot;
import com.sse.app.academic.teaching.TeacherClassSubject;
import com.sse.app.academic.teaching.TeachingAssignmentRepository;
import com.sse.app.dashboard.DashboardDtos.*;
import com.sse.app.finance.FinanceService;
import com.sse.app.identity.ParentStudentRepository;
import com.sse.app.identity.User;
import com.sse.app.identity.UserRepository;
import com.sse.app.notification.NotificationService;
import com.sse.app.security.CurrentUser;
import org.springframework.stereotype.Service;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Tổng hợp theo đúng scope của người đang đăng nhập, không trả dữ liệu mẫu cho dashboard. */
@Service
public class DashboardService {
    private final UserRepository users;
    private final ParentStudentRepository parentStudents;
    private final StructureService structure;
    private final TimetableService timetable;
    private final TeachingAssignmentRepository teachingAssignments;
    private final AttendanceService attendance;
    private final GradeService grades;
    private final AssignmentService assignments;
    private final NotificationService notifications;
    private final FinanceService finance;

    public DashboardService(UserRepository users, ParentStudentRepository parentStudents,
                            StructureService structure, TimetableService timetable,
                            TeachingAssignmentRepository teachingAssignments,
                            AttendanceService attendance, GradeService grades,
                            AssignmentService assignments, NotificationService notifications,
                            FinanceService finance) {
        this.users = users;
        this.parentStudents = parentStudents;
        this.structure = structure;
        this.timetable = timetable;
        this.teachingAssignments = teachingAssignments;
        this.attendance = attendance;
        this.grades = grades;
        this.assignments = assignments;
        this.notifications = notifications;
        this.finance = finance;
    }

    public DashboardResponse forCurrentUser(CurrentUser me) {
        DashboardResponse response = switch (me.role()) {
            case "ADMIN" -> admin();
            case "TEACHER" -> teacher(me.id());
            case "STUDENT" -> student(me.id());
            case "PARENT" -> parent(me.id());
            default -> new DashboardResponse(List.of(), List.of());
        };
        return new DashboardResponse(response.metrics(), response.charts(), shortcuts(me));
    }

    private List<DashboardShortcut> shortcuts(CurrentUser me) {
        List<DashboardShortcut> rows = new ArrayList<>();
        if (me.isAdmin()) {
            List<SchoolClass> classes = structure.listClasses(null, null);
            Set<String> assignedClassIds = teachingAssignments.findAll().stream()
                    .filter(item -> "ACTIVE".equals(item.getStatus()))
                    .map(TeacherClassSubject::getClassId)
                    .collect(Collectors.toSet());
            Set<String> studentsWithGrades = grades.allGrades().stream()
                    .map(Grade::getStudentId)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toSet());
            rows.add(shortcut("homeroom", "Lớp chưa có GVCN", classes.stream().filter(item -> item.getHomeroomTeacherId() == null).count(), "A2", "homeroom=missing", "red"));
            rows.add(shortcut("assignment", "Lớp chưa có phân công giáo viên", classes.stream().filter(item -> !assignedClassIds.contains(item.getId())).count(), "A3", "assignment=missing", "orange"));
            rows.add(shortcut("timetable", "Xung đột thời khóa biểu", timetableConflictCount(timetable.allSlots()), "A3", "conflict=true", "red"));
            rows.add(shortcut("grades", "Học sinh chưa có điểm", users.findAll().stream().filter(item -> "STUDENT".equals(item.getRole())).filter(item -> !studentsWithGrades.contains(item.getId())).count(), "A8", "grades=missing", "orange"));
            rows.add(shortcut("assignments", "Bài tập chưa chấm", ungradedSubmissions(null), "A8", "assignments=ungraded", "orange"));
            rows.add(shortcut("invoices", "Hóa đơn quá hạn", finance.dashboardInvoices(null).stream().filter(item -> "OVERDUE".equals(item.status())).count(), "A7", "status=OVERDUE", "red"));
            rows.add(shortcut("notifications", "Thông báo gửi thất bại", notifications.failedNotifications().size(), "A9", "status=FAILED", "red"));
        } else if (me.isTeacher()) {
            List<TimetableSlot> todaySlots = timetable.list(null, me.id(), null, null).stream().filter(slot -> dayCode(LocalDate.now().getDayOfWeek()).equals(slot.getDayOfWeek())).toList();
            long markedSlots = attendance.allRecords().stream().filter(row -> LocalDate.now().equals(row.getDate())).map(AttendanceRecord::getSlotId).distinct().filter(id -> todaySlots.stream().anyMatch(slot -> slot.getId().equals(id))).count();
            long activeGradeBooks = teachingAssignments.findByTeacherIdAndStatus(me.id(), "ACTIVE").stream()
                    .map(scope -> scope.getClassId() + "|" + scope.getSubjectId() + "|" + scope.getSemesterId())
                    .distinct().count();
            rows.add(shortcut("attendance", "Tiết hôm nay chưa điểm danh", Math.max(0, todaySlots.size() - markedSlots), "B3", "date=today", "orange"));
            rows.add(shortcut("assignments", "Bài tập chưa chấm", ungradedSubmissions(me.id()), "B5", "status=SUBMITTED", "orange"));
            rows.add(shortcut("grades", "Sổ điểm đang phụ trách", activeGradeBooks, "B4", "scope=assigned", "blue"));
        } else if (me.isStudent()) {
            User student = users.findById(me.id()).orElseThrow();
            Map<String, AssignmentSubmission> submitted = assignments.submissionsByStudent(me.id()).stream().collect(Collectors.toMap(AssignmentSubmission::getAssignmentId, Function.identity(), (left, right) -> left));
            long overdue = assignments.list(student.getClassId(), null, "PUBLISHED", true).stream().filter(item -> !submitted.containsKey(item.getId()) && item.getDeadline() != null && item.getDeadline().isBefore(Instant.now())).count();
            rows.add(shortcut("assignments", "Bài tập quá hạn chưa nộp", overdue, "C4", "status=OVERDUE", "red"));
        } else if (me.isParent()) {
            Set<String> childIds = parentStudents.findByParentId(me.id()).stream().map(item -> item.getStudentId()).collect(Collectors.toSet());
            rows.add(shortcut("invoices", "Hóa đơn quá hạn", finance.dashboardInvoices(childIds).stream().filter(item -> "OVERDUE".equals(item.status())).count(), "D4", "status=OVERDUE", "red"));
            rows.add(shortcut("attendance", "Cảnh báo chuyên cần", attendance.allRecords().stream().filter(item -> childIds.contains(item.getStudentId())).filter(item -> "LATE".equals(item.getStatus()) || "ABSENT_UNEXCUSED".equals(item.getStatus())).count(), "D2", "attendance=alerts", "orange"));
        }
        return rows;
    }

    private long ungradedSubmissions(String teacherId) {
        return assignments.list(null, teacherId, null, false).stream().flatMap(item -> assignments.submissionsOf(item.getId()).stream()).filter(item -> "SUBMITTED".equals(item.getStatus()) || "LATE".equals(item.getStatus())).count();
    }

    private long timetableConflictCount(List<TimetableSlot> slots) {
        Map<String, Long> room = slots.stream().filter(slot -> slot.getRoomCode() != null).collect(Collectors.groupingBy(slot -> slot.getSemesterId() + "|" + slot.getDayOfWeek() + "|" + slot.getPeriodNo() + "|" + slot.getRoomCode(), Collectors.counting()));
        Map<String, Long> teacher = slots.stream().filter(slot -> slot.getTeacherId() != null).collect(Collectors.groupingBy(slot -> slot.getSemesterId() + "|" + slot.getDayOfWeek() + "|" + slot.getPeriodNo() + "|" + slot.getTeacherId(), Collectors.counting()));
        return java.util.stream.Stream.concat(room.values().stream(), teacher.values().stream()).filter(count -> count > 1).mapToLong(count -> count - 1).sum();
    }

    private DashboardShortcut shortcut(String key, String label, long count, String pageId, String filter, String tone) {
        return new DashboardShortcut(key, label, count, pageId, filter, tone);
    }

    private DashboardResponse admin() {
        List<User> allUsers = users.findAll();
        List<User> activeUserRows = allUsers.stream()
                .filter(user -> "ACTIVE".equals(user.getStatus()))
                .toList();
        List<SchoolClass> allClasses = structure.listClasses(null, null);
        List<AttendanceRecord> records = attendance.list(null, null, null, null);
        List<Grade> allGrades = grades.allGrades();
        List<TimetableSlot> slots = timetable.allSlots();
        List<FinanceService.InvoiceSummary> invoices = finance.dashboardInvoices(null);

        long activeUsers = activeUserRows.size();
        List<AttendanceRecord> today = records.stream().filter(r -> LocalDate.now().equals(r.getDate())).toList();
        double todayRate = attendanceRate(today);
        long pendingInvoices = invoices.stream().filter(i -> !"PAID".equals(i.status())).count();

        List<DashboardMetric> metrics = List.of(
                metric("users", "Tài khoản hoạt động", activeUsers, "NUMBER", "Tất cả vai trò", "blue"),
                metric("classes", "Lớp đang mở", allClasses.size(), "NUMBER", "Khối 10, 11 và 12", "green"),
                metric("attendance", "Chuyên cần hôm nay", todayRate, "PERCENT", today.isEmpty() ? "Chưa điểm danh hôm nay" : "Từ sổ điểm danh", "orange"),
                metric("invoices", "Hóa đơn cần theo dõi", pendingInvoices, "NUMBER", "Chưa thanh toán đủ", "violet")
        );

        Map<String, Long> roleCounts = activeUserRows.stream().collect(Collectors.groupingBy(User::getRole,
                LinkedHashMap::new, Collectors.counting()));
        List<DashboardDatum> roles = List.of("ADMIN", "TEACHER", "STUDENT", "PARENT").stream()
                .map(role -> datum(roleLabel(role), roleCounts.getOrDefault(role, 0L))).toList();

        Map<LocalDate, List<AttendanceRecord>> byDate = records.stream()
                .filter(r -> r.getDate() != null && !r.getDate().isBefore(LocalDate.now().minusDays(6)))
                .collect(Collectors.groupingBy(AttendanceRecord::getDate));
        List<DashboardDatum> attendanceByDay = new ArrayList<>();
        for (int day = 6; day >= 0; day--) {
            LocalDate date = LocalDate.now().minusDays(day);
            attendanceByDay.add(datum(dayLabel(date), attendanceRate(byDate.getOrDefault(date, List.of()))));
        }

        List<DashboardDatum> gradeBands = List.of(
                datum("Dưới 5", allGrades.stream().filter(g -> score(g) < 5).count()),
                datum("5 - 6.4", allGrades.stream().filter(g -> score(g) >= 5 && score(g) < 6.5).count()),
                datum("6.5 - 7.9", allGrades.stream().filter(g -> score(g) >= 6.5 && score(g) < 8).count()),
                datum("8 - 10", allGrades.stream().filter(g -> score(g) >= 8).count())
        );
        List<DashboardDatum> invoiceStatuses = List.of(
                datum("Chờ thanh toán", invoices.stream().filter(i -> "PENDING".equals(i.status())).count()),
                datum("Quá hạn", invoices.stream().filter(i -> "OVERDUE".equals(i.status())).count()),
                datum("Thanh toán một phần", invoices.stream().filter(i -> "PARTIAL".equals(i.status())).count()),
                datum("Đã thanh toán", invoices.stream().filter(i -> "PAID".equals(i.status())).count()),
                datum("Tiết TKB", slots.size())
        );

        return new DashboardResponse(metrics, List.of(
                chart("Phân bổ tài khoản", "Dữ liệu tài khoản đang hoạt động trong hệ thống", "BAR", "", max(roles), roles),
                chart("Chuyên cần toàn trường", "Tỷ lệ có mặt trong 7 ngày gần nhất", "COLUMN", "%", 100, attendanceByDay),
                chart("Phổ điểm", "Phân bố đầu điểm đã nhập", "BAR", " đầu điểm", max(gradeBands), gradeBands),
                chart("Tài chính và thời khóa biểu", "Hóa đơn theo trạng thái và số tiết đã xếp", "BAR", "", max(invoiceStatuses), invoiceStatuses)
        ));
    }

    private DashboardResponse teacher(String teacherId) {
        List<TeacherClassSubject> scopes = teachingAssignments.findByTeacherIdAndStatus(teacherId, "ACTIVE");
        LinkedHashSet<String> classIds = scopes.stream().map(TeacherClassSubject::getClassId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Set<String> subjectIds = scopes.stream().map(TeacherClassSubject::getSubjectId).collect(Collectors.toSet());
        Map<String, SchoolClass> classes = structure.listClasses(null, null).stream()
                .collect(Collectors.toMap(SchoolClass::getId, Function.identity()));
        Map<String, User> students = users.findAll().stream()
                .filter(u -> "STUDENT".equals(u.getRole()) && classIds.contains(u.getClassId()))
                .collect(Collectors.toMap(User::getId, Function.identity()));
        List<Grade> scopedGrades = grades.allGrades().stream()
                .filter(g -> students.containsKey(g.getStudentId()) && subjectIds.contains(g.getSubjectId()))
                .toList();
        List<Assignment> ownAssignments = assignments.list(null, teacherId, null, false);
        long ungraded = ownAssignments.stream()
                .flatMap(a -> assignments.submissionsOf(a.getId()).stream())
                .filter(s -> !"GRADED".equals(s.getStatus())).count();
        String today = dayCode(LocalDate.now().getDayOfWeek());
        long todaySlots = timetable.list(null, teacherId, null, today).size();

        List<DashboardMetric> metrics = List.of(
                metric("classes", "Lớp được phân công", classIds.size(), "NUMBER", "Theo phân công môn học", "blue"),
                metric("calendar", "Tiết hôm nay", todaySlots, "NUMBER", "Theo thời khóa biểu", "green"),
                metric("assignments", "Bài chưa chấm", ungraded, "NUMBER", "Bài nộp chưa có điểm", "orange"),
                metric("notifications", "Thông báo chưa đọc", notifications.unreadCount(teacherId), "NUMBER", "Hộp thư của bạn", "violet")
        );

        List<DashboardDatum> classLoad = classIds.stream().map(id -> {
            SchoolClass schoolClass = classes.get(id);
            long count = students.values().stream().filter(s -> id.equals(s.getClassId())).count();
            return datum(schoolClass == null ? id : schoolClass.getCode(), count);
        }).toList();

        List<DashboardDatum> submissions = ownAssignments.stream().limit(6).map(a -> {
            long classSize = students.values().stream().filter(s -> a.getClassId().equals(s.getClassId())).count();
            long submitted = assignments.submissionsOf(a.getId()).size();
            return datum(shortLabel(a.getTitle()), classSize == 0 ? 0 : round1(submitted * 100.0 / classSize));
        }).toList();

        List<DashboardDatum> classScores = classIds.stream().map(classId -> {
            double avg = average(scopedGrades.stream()
                    .filter(g -> classId.equals(students.get(g.getStudentId()).getClassId()))
                    .map(Grade::getScore).toList());
            SchoolClass schoolClass = classes.get(classId);
            return datum(schoolClass == null ? classId : schoolClass.getCode(), avg);
        }).toList();
        List<DashboardDatum> work = List.of(
                datum("Bài tập đã giao", ownAssignments.size()),
                datum("Bài đã nộp", ownAssignments.stream().mapToLong(a -> assignments.submissionsOf(a.getId()).size()).sum()),
                datum("Đầu điểm đã nhập", scopedGrades.size()),
                datum("Tiết đã xếp", timetable.list(null, teacherId, null, null).size())
        );

        return new DashboardResponse(metrics, List.of(
                chart("Lớp phụ trách", "Sĩ số thật theo từng lớp được phân công", "BAR", " học sinh", max(classLoad), classLoad),
                chart("Tiến độ nộp bài", "Tỷ lệ nộp của các bài tập đã giao", "BAR", "%", 100, submissions),
                chart("Điểm trung bình theo lớp", "Chỉ các môn giáo viên đang được phân công", "BAR", "/10", 10, classScores),
                chart("Khối lượng công việc", "Tổng hợp từ bài tập, điểm và thời khóa biểu", "COLUMN", "", max(work), work)
        ));
    }

    private DashboardResponse student(String studentId) {
        User student = users.findById(studentId).orElseThrow();
        List<Grade> studentGrades = grades.allGrades().stream().filter(g -> studentId.equals(g.getStudentId())).toList();
        List<AttendanceRecord> records = attendance.list(studentId, null, null, null);
        List<Assignment> classAssignments = student.getClassId() == null ? List.of()
                : assignments.list(student.getClassId(), null, "PUBLISHED", true);
        Map<String, AssignmentSubmission> submissions = assignments.submissionsByStudent(studentId).stream()
                .collect(Collectors.toMap(AssignmentSubmission::getAssignmentId, Function.identity(), (a, b) -> a));
        long upcoming = classAssignments.stream().filter(a -> !submissions.containsKey(a.getId())
                && a.getDeadline() != null && a.getDeadline().isAfter(Instant.now())
                && a.getDeadline().isBefore(Instant.now().plusSeconds(7 * 86_400L))).count();

        List<DashboardMetric> metrics = List.of(
                metric("grades", "Điểm trung bình", average(studentGrades.stream().map(Grade::getScore).toList()), "DECIMAL_1", "Từ các đầu điểm đã công bố", "green"),
                metric("attendance", "Chuyên cần", attendanceRate(records), "PERCENT", records.isEmpty() ? "Chưa có điểm danh" : "Từ sổ điểm danh", "blue"),
                metric("assignments", "Bài sắp đến hạn", upcoming, "NUMBER", "Trong 7 ngày tới", "orange"),
                metric("notifications", "Thông báo chưa đọc", notifications.unreadCount(studentId), "NUMBER", "Hộp thư của bạn", "violet")
        );

        Map<String, List<Grade>> gradesBySubject = studentGrades.stream()
                .collect(Collectors.groupingBy(g -> blank(g.getSubjectName(), "Môn học"), LinkedHashMap::new, Collectors.toList()));
        List<DashboardDatum> subjectScores = gradesBySubject.entrySet().stream()
                .map(e -> datum(e.getKey(), average(e.getValue().stream().map(Grade::getScore).toList()))).toList();

        Map<LocalDate, List<AttendanceRecord>> byDate = records.stream().filter(r -> r.getDate() != null)
                .collect(Collectors.groupingBy(AttendanceRecord::getDate));
        List<DashboardDatum> attendanceByDay = new ArrayList<>();
        for (int day = 6; day >= 0; day--) {
            LocalDate date = LocalDate.now().minusDays(day);
            attendanceByDay.add(datum(dayLabel(date), attendanceRate(byDate.getOrDefault(date, List.of()))));
        }
        List<DashboardDatum> assignmentStatus = List.of(
                datum("Đã nộp", classAssignments.stream().filter(a -> submissions.containsKey(a.getId())).count()),
                datum("Cần nộp", classAssignments.stream().filter(a -> !submissions.containsKey(a.getId())
                        && (a.getDeadline() == null || a.getDeadline().isAfter(Instant.now()))).count()),
                datum("Quá hạn", classAssignments.stream().filter(a -> !submissions.containsKey(a.getId())
                        && a.getDeadline() != null && a.getDeadline().isBefore(Instant.now())).count())
        );
        List<DashboardDatum> timetableByDay = timetable.list(student.getClassId(), null, null, null).stream()
                .collect(Collectors.groupingBy(s -> dayName(s.getDayOfWeek()), LinkedHashMap::new, Collectors.counting()))
                .entrySet().stream().map(e -> datum(e.getKey(), e.getValue())).toList();

        return new DashboardResponse(metrics, List.of(
                chart("Điểm theo môn", "Điểm trung bình của các đầu điểm đã nhập", "BAR", "/10", 10, subjectScores),
                chart("Chuyên cần cá nhân", "Tỷ lệ có mặt trong 7 ngày gần nhất", "COLUMN", "%", 100, attendanceByDay),
                chart("Bài tập cần theo dõi", "Trạng thái thực tế các bài tập trong lớp", "BAR", "", max(assignmentStatus), assignmentStatus),
                chart("Thời khóa biểu", "Số tiết theo ngày đã được xếp", "COLUMN", " tiết", max(timetableByDay), timetableByDay)
        ));
    }

    private DashboardResponse parent(String parentId) {
        List<String> childIds = parentStudents.findByParentId(parentId).stream()
                .map(r -> r.getStudentId()).distinct().toList();
        Map<String, User> children = users.findAllById(childIds).stream()
                .collect(Collectors.toMap(User::getId, Function.identity()));
        List<Grade> childGrades = grades.allGrades().stream().filter(g -> children.containsKey(g.getStudentId())).toList();
        List<AttendanceRecord> childAttendance = attendance.list(null, null, null, null).stream()
                .filter(r -> children.containsKey(r.getStudentId())).toList();
        List<FinanceService.InvoiceSummary> invoices = finance.dashboardInvoices(new HashSet<>(childIds));
        long attendanceAlerts = childAttendance.stream()
                .filter(r -> "LATE".equals(r.getStatus()) || "ABSENT_UNEXCUSED".equals(r.getStatus())).count();
        long unpaid = invoices.stream().filter(i -> !"PAID".equals(i.status())).count();

        List<DashboardMetric> metrics = List.of(
                metric("children", "Hồ sơ con", children.size(), "NUMBER", "Tài khoản con đã liên kết", "blue"),
                metric("alerts", "Cảnh báo chuyên cần", attendanceAlerts, "NUMBER", "Đi muộn hoặc vắng không phép", "red"),
                metric("invoices", "Hóa đơn mở", unpaid, "NUMBER", "Chưa thanh toán đủ", "orange"),
                metric("notifications", "Thông báo chưa đọc", notifications.unreadCount(parentId), "NUMBER", "Hộp thư của bạn", "violet")
        );

        List<DashboardDatum> childScores = children.values().stream().map(child -> datum(child.getFullName(), average(
                childGrades.stream().filter(g -> child.getId().equals(g.getStudentId())).map(Grade::getScore).toList()))).toList();
        List<DashboardDatum> childAttendanceRates = children.values().stream().map(child -> datum(child.getFullName(), attendanceRate(
                childAttendance.stream().filter(a -> child.getId().equals(a.getStudentId())).toList()))).toList();
        List<DashboardDatum> balances = children.values().stream().map(child -> {
            long outstanding = invoices.stream().filter(i -> child.getId().equals(i.studentId()))
                    .mapToLong(i -> i.totalAmount() - i.paidAmount()).sum();
            return datum(child.getFullName(), round1(outstanding / 1_000_000.0));
        }).toList();
        List<DashboardDatum> actions = List.of(
                datum("Đi muộn", childAttendance.stream().filter(a -> "LATE".equals(a.getStatus())).count()),
                datum("Vắng không phép", childAttendance.stream().filter(a -> "ABSENT_UNEXCUSED".equals(a.getStatus())).count()),
                datum("Hóa đơn mở", unpaid),
                datum("Thông báo", notifications.unreadCount(parentId))
        );

        return new DashboardResponse(metrics, List.of(
                chart("Kết quả học tập của con", "Điểm trung bình theo từng hồ sơ", "BAR", "/10", 10, childScores),
                chart("Chuyên cần của con", "Tỷ lệ có mặt theo từng học sinh", "COLUMN", "%", 100, childAttendanceRates),
                chart("Công nợ học phí", "Số tiền còn phải thanh toán theo triệu đồng", "BAR", " triệu", max(balances), balances),
                chart("Việc cần theo dõi", "Cảnh báo được tổng hợp từ dữ liệu thực", "BAR", "", max(actions), actions)
        ));
    }

    private DashboardMetric metric(String key, String label, double value, String format, String hint, String tone) {
        return new DashboardMetric(key, label, round1(value), format, hint, tone);
    }

    private DashboardChart chart(String title, String subtitle, String type, String suffix,
                                 double max, List<DashboardDatum> data) {
        return new DashboardChart(title, subtitle, type, suffix, Math.max(1, round1(max)), data);
    }

    private DashboardDatum datum(String label, long value) { return datum(label, (double) value); }
    private DashboardDatum datum(String label, double value) { return new DashboardDatum(label, round1(value)); }

    private double average(List<Double> values) {
        return values.stream().filter(Objects::nonNull).mapToDouble(Double::doubleValue).average().orElse(0);
    }

    private double attendanceRate(List<AttendanceRecord> values) {
        if (values.isEmpty()) return 0;
        long attended = values.stream().filter(r -> "PRESENT".equals(r.getStatus()) || "LATE".equals(r.getStatus())).count();
        return round1(attended * 100.0 / values.size());
    }

    private double score(Grade grade) { return grade.getScore() == null ? 0 : grade.getScore(); }
    private double max(List<DashboardDatum> data) { return data.stream().mapToDouble(DashboardDatum::value).max().orElse(1); }
    private double round1(double value) { return Math.round(value * 10.0) / 10.0; }
    private String blank(String value, String fallback) { return value == null || value.isBlank() ? fallback : value; }
    private String shortLabel(String value) { return value == null ? "Bài tập" : value.length() > 18 ? value.substring(0, 18) + "..." : value; }
    private String roleLabel(String role) { return switch (role) {
        case "ADMIN" -> "Quản trị"; case "TEACHER" -> "Giáo viên"; case "STUDENT" -> "Học sinh"; default -> "Phụ huynh";
    }; }
    private String dayCode(DayOfWeek day) { return switch (day) {
        case MONDAY -> "MON"; case TUESDAY -> "TUE"; case WEDNESDAY -> "WED"; case THURSDAY -> "THU";
        case FRIDAY -> "FRI"; case SATURDAY -> "SAT"; case SUNDAY -> "SUN";
    }; }
    private String dayLabel(LocalDate date) { return "T" + (date.getDayOfWeek().getValue() + 1); }
    private String dayName(String code) { return switch (code == null ? "" : code.toUpperCase()) {
        case "MON" -> "Thứ 2"; case "TUE" -> "Thứ 3"; case "WED" -> "Thứ 4"; case "THU" -> "Thứ 5";
        case "FRI" -> "Thứ 6"; case "SAT" -> "Thứ 7"; default -> "Chủ nhật";
    }; }
}
