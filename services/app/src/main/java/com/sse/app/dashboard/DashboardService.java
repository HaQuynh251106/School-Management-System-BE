package com.sse.app.dashboard;

import com.sse.app.academic.grade.GradeCalculationService;
import com.sse.app.identity.UserDto;
import com.sse.app.identity.UserService;
import com.sse.app.security.CurrentUser;
import com.sse.app.security.CurrentUserHolder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;

@Service
public class DashboardService {
    private final JdbcTemplate jdbc;
    private final GradeCalculationService gradeCalculations;
    private final UserService users;

    public DashboardService(JdbcTemplate jdbc, GradeCalculationService gradeCalculations, UserService users) {
        this.jdbc = jdbc;
        this.gradeCalculations = gradeCalculations;
        this.users = users;
    }

    public DashboardDtos.Response getDashboard(String childId) {
        CurrentUser user = CurrentUserHolder.require();
        return switch (user.role()) {
            case "ADMIN" -> admin();
            case "ACADEMIC_STAFF" -> academicStaff();
            case "ACCOUNTANT" -> accountant();
            case "TEACHER" -> teacher(user.id());
            case "STUDENT" -> student(user.id());
            case "PARENT" -> parent(user.id(), childId);
            default -> response(user.role(), "USER", List.of(user.id()), List.of(), List.of(), List.of());
        };
    }

    private DashboardDtos.Response academicStaff() {
        double classes = number("select count(*) from classes");
        double students = number("select count(*) from users where role='STUDENT' and status='ACTIVE'");
        double slots = number("select count(*) from timetable_slots");
        double upcomingExams = number("select count(*) from exam_periods where status in ('DRAFT','CONFIRMED') and end_date >= current_date");
        List<DashboardDtos.Metric> metrics = List.of(
                metric("classes", "Lớp học", classes, "NUMBER", "Cơ cấu lớp đang được quản lý", "blue"),
                metric("users", "Học sinh", students, "NUMBER", "Hồ sơ học sinh đang hoạt động", "violet"),
                metric("calendar", "Tiết đã xếp", slots, "NUMBER", "Thời khóa biểu hiện có", "green"),
                metric("alerts", "Kỳ thi cần theo dõi", upcomingExams, "NUMBER", "Kỳ thi nháp hoặc đã xác nhận", upcomingExams > 0 ? "orange" : "green")
        );
        List<DashboardDtos.Chart> charts = List.of(
                chart("Quy mô theo khối", "Số học sinh trong từng khối", "COLUMN", " học sinh", rows("""
                        select coalesce(c.grade_level, 'Chưa xếp lớp') label, count(u.id) metric_value
                        from users u left join classes c on c.id=u.class_id
                        where u.role='STUDENT' and u.status='ACTIVE'
                        group by coalesce(c.grade_level, 'Chưa xếp lớp') order by label
                        """)),
                chart("Tiến độ thời khóa biểu", "Số tiết đã xếp theo lớp", "BAR", " tiết", rows("""
                        select c.code label, count(t.id) metric_value from classes c
                        left join timetable_slots t on t.class_id=c.id
                        group by c.id,c.code order by c.code limit 12
                        """))
        );
        return response("ACADEMIC_STAFF", "SCHOOL", List.of(), metrics, charts, academicShortcuts());
    }

    private DashboardDtos.Response accountant() {
        double total = number("select coalesce(sum(total_amount),0) from invoices");
        double collected = number("select coalesce(sum(paid_amount),0) from invoices");
        double debt = Math.max(0, total - collected);
        double overdue = number("select count(*) from invoices where status='OVERDUE'");
        List<DashboardDtos.Metric> metrics = List.of(
                metric("invoices", "Tổng phải thu", total, "CURRENCY", "Giá trị hóa đơn đã phát hành", "blue"),
                metric("payments", "Đã thu", collected, "CURRENCY", "Tổng tiền đã ghi nhận", "green"),
                metric("alerts", "Còn phải thu", debt, "CURRENCY", "Công nợ toàn trường", debt > 0 ? "orange" : "green"),
                metric("overdue", "Hóa đơn quá hạn", overdue, "NUMBER", "Cần phối hợp nhắc phụ huynh", overdue > 0 ? "red" : "green")
        );
        List<DashboardDtos.Chart> charts = List.of(
                chart("Trạng thái hóa đơn", "Số lượng theo trạng thái", "BAR", " hóa đơn", rows("""
                        select case status when 'PAID' then 'Đã thanh toán' when 'PARTIAL' then 'Một phần'
                               when 'OVERDUE' then 'Quá hạn' else 'Chưa thanh toán' end label,
                               count(*) metric_value from invoices group by status order by metric_value desc
                        """)),
                chart("Công nợ theo lớp", "Số tiền còn phải thu", "COLUMN", " đ", rows("""
                        select coalesce(class_code,'Chưa có lớp') label,
                               coalesce(sum(total_amount-paid_amount),0) metric_value
                        from invoices group by coalesce(class_code,'Chưa có lớp')
                        order by metric_value desc limit 10
                        """))
        );
        return response("ACCOUNTANT", "SCHOOL", List.of(), metrics, charts, accountantShortcuts());
    }

    private DashboardDtos.Response admin() {
        double activeUsers = number("select count(*) from users where status = 'ACTIVE'");
        double classes = number("select count(*) from classes");
        double attendance = number("""
                select coalesce(100.0 * sum(case when status = 'PRESENT' then 1 else 0 end) / nullif(count(*), 0), 0)
                from attendance_records where date = current_date
                """);
        double attendanceRecords = number("select count(*) from attendance_records where date = current_date");
        double openDebts = number("select count(*) from invoices where status in ('UNPAID', 'PARTIAL', 'OVERDUE')");
        double attendanceAlerts = number("""
                select count(*) from attendance_records where date = current_date
                and status in ('ABSENT_EXCUSED', 'ABSENT_UNEXCUSED', 'LATE')
                """);
        double openAlerts = openDebts + attendanceAlerts;

        List<DashboardDtos.Metric> metrics = List.of(
                metric("users", "Tài khoản hoạt động", activeUsers, "NUMBER", "Người dùng đang có quyền truy cập", "blue"),
                metric("classes", "Lớp học", classes, "NUMBER", "Quy mô lớp trong hệ thống", "violet"),
                metric("attendance", "Chuyên cần hôm nay", attendance,
                        attendanceRecords == 0 ? "PERCENT_OR_EMPTY" : "PERCENT",
                        attendanceRecords == 0 ? "Hôm nay chưa có dữ liệu điểm danh" : "Tỷ lệ có mặt toàn trường", "green"),
                metric("alerts", "Cần xử lý", openAlerts, "NUMBER",
                        Math.round(attendanceAlerts) + " vắng/trễ · " + Math.round(openDebts) + " công nợ",
                        openAlerts > 0 ? "orange" : "green")
        );
        List<DashboardDtos.Chart> charts = List.of(
                chart("Cơ cấu người dùng", "Số tài khoản theo từng vai trò", "BAR", "", rows("""
                        select case role when 'ADMIN' then 'Quản trị' when 'TEACHER' then 'Giáo viên'
                               when 'STUDENT' then 'Học sinh' when 'PARENT' then 'Phụ huynh' else role end label,
                               count(*) metric_value
                        from users group by role order by metric_value desc
                        """)),
                chart("Quy mô lớp học", "Sĩ số các lớp đông nhất", "COLUMN", " HS", rows("""
                        select c.code label, count(u.id) metric_value
                        from classes c left join users u on u.class_id = c.id and u.role = 'STUDENT'
                        group by c.id, c.code order by metric_value desc, c.code limit 8
                        """))
        );
        return response("ADMIN", "SCHOOL", List.of(), metrics, charts, adminShortcuts());
    }

    private DashboardDtos.Response teacher(String teacherId) {
        double classes = number("select count(distinct class_id) from teaching_assignments where teacher_id = ?", teacherId);
        double weeklySlots = number("select count(*) from timetable_slots where teacher_id = ?", teacherId);
        double assignments = number("select count(*) from assignments where teacher_id = ? and status = 'PUBLISHED'", teacherId);
        double unread = unread(teacherId);

        List<DashboardDtos.Metric> metrics = List.of(
                metric("classes", "Lớp đang phụ trách", classes, "NUMBER", "Các lớp được phân công bộ môn", "blue"),
                metric("calendar", "Tiết dạy trong tuần", weeklySlots, "NUMBER", "Lịch dạy đã được xếp", "violet"),
                metric("assignments", "Bài tập đang giao", assignments, "NUMBER", "Bài tập đã công bố", "green"),
                metric("notifications", "Thông báo chưa đọc", unread, "NUMBER", "Thông tin cần xem", unread > 0 ? "orange" : "green")
        );
        List<DashboardDtos.Chart> charts = List.of(
                chart("Nhịp dạy trong tuần", "Số tiết theo từng ngày", "COLUMN", " tiết", rows("""
                        select case day_of_week when 'MON' then 'T2' when 'MONDAY' then 'T2'
                               when 'TUE' then 'T3' when 'TUESDAY' then 'T3'
                               when 'WED' then 'T4' when 'WEDNESDAY' then 'T4'
                               when 'THU' then 'T5' when 'THURSDAY' then 'T5'
                               when 'FRI' then 'T6' when 'FRIDAY' then 'T6'
                               when 'SAT' then 'T7' when 'SATURDAY' then 'T7' else day_of_week end label,
                               count(*) metric_value
                        from timetable_slots where teacher_id = ? group by day_of_week
                        order by min(case day_of_week when 'MON' then 1 when 'MONDAY' then 1
                                     when 'TUE' then 2 when 'TUESDAY' then 2 when 'WED' then 3 when 'WEDNESDAY' then 3
                                     when 'THU' then 4 when 'THURSDAY' then 4 when 'FRI' then 5 when 'FRIDAY' then 5
                                     when 'SAT' then 6 when 'SATURDAY' then 6 else 7 end)
                        """, teacherId)),
                chart("Bài tập theo lớp", "Số bài tập đã giao", "BAR", " bài", rows("""
                        select coalesce(c.code, a.class_id) label, count(*) metric_value
                        from assignments a left join classes c on c.id = a.class_id
                        where a.teacher_id = ? group by coalesce(c.code, a.class_id) order by metric_value desc limit 8
                        """, teacherId))
        );
        return response("TEACHER", "TEACHER", List.of(teacherId), metrics, charts, teacherShortcuts());
    }

    private DashboardDtos.Response student(String studentId) {
        double average = valueOrZero(gradeCalculations.overallAverage(List.of(studentId)));
        double attendance = number("""
                select coalesce(100.0 * sum(case when status = 'PRESENT' then 1 else 0 end) / nullif(count(*), 0), 0)
                from attendance_records where student_id = ?
                """, studentId);
        double pending = number("""
                select count(*) from assignments a join users u on u.id = ? and u.class_id = a.class_id
                where a.status = 'PUBLISHED' and (a.deadline is null or a.deadline >= current_timestamp)
                  and not exists (select 1 from assignment_submissions s where s.assignment_id = a.id and s.student_id = ?)
                """, studentId, studentId);
        double unread = unread(studentId);

        List<DashboardDtos.Metric> metrics = List.of(
                metric("grades", "Điểm trung bình", average, "DECIMAL_1", "Chỉ tính các môn đã đủ đầu điểm", "violet"),
                metric("attendance", "Tỷ lệ chuyên cần", attendance, "PERCENT", "Toàn bộ lịch sử điểm danh", "green"),
                metric("assignments", "Bài tập cần nộp", pending, "NUMBER", "Bài đang mở chưa nộp", pending > 0 ? "orange" : "green"),
                metric("notifications", "Thông báo chưa đọc", unread, "NUMBER", "Cập nhật từ giáo viên và nhà trường", unread > 0 ? "blue" : "green")
        );
        List<DashboardDtos.Chart> charts = List.of(
                chart("Kết quả theo môn", "Chỉ tính những môn đã đủ đầu điểm", "COLUMN", " điểm",
                        subjectAverageRows(List.of(studentId))),
                chart("Tình hình chuyên cần", "Số lượt theo trạng thái", "BAR", " lượt", rows("""
                        select case status when 'PRESENT' then 'Có mặt' when 'ABSENT_UNEXCUSED' then 'Vắng không phép'
                               when 'ABSENT_EXCUSED' then 'Vắng có phép' when 'LATE' then 'Đi trễ' else status end label,
                               count(*) metric_value
                        from attendance_records where student_id = ? group by status order by metric_value desc
                        """, studentId))
        );
        return response("STUDENT", "STUDENT", List.of(studentId), metrics, charts, studentShortcuts());
    }

    private DashboardDtos.Response parent(String parentId, String childId) {
        List<UserDto> allChildren = users.childrenOf(parentId);
        if (childId != null && !childId.isBlank()) users.assertParentOf(parentId, childId);
        List<UserDto> childRows = childId == null || childId.isBlank() ? allChildren
                : allChildren.stream().filter(child -> childId.equals(child.id())).toList();
        List<String> childIds = childRows.stream().map(UserDto::id).toList();
        double children = childRows.size();
        double average = valueOrZero(gradeCalculations.overallAverage(childIds));
        double unpaid = childId == null || childId.isBlank() ? number("""
                select count(*) from invoices i where i.status in ('UNPAID', 'PARTIAL', 'OVERDUE')
                  and (i.parent_id = ? or i.student_id in (select student_id from parent_student where parent_id = ?))
                """, parentId, parentId) : number("""
                select count(*) from invoices i where i.status in ('UNPAID', 'PARTIAL', 'OVERDUE')
                  and i.student_id = ?
                """, childId);
        double unread = unread(parentId);

        List<DashboardDtos.Metric> metrics = List.of(
                metric("children", "Học sinh liên kết", children, "NUMBER", "Hồ sơ con đang theo dõi", "blue"),
                metric("grades", "Điểm trung bình", average, "DECIMAL_1", "Các môn đã đủ đầu điểm của con", "violet"),
                metric("invoices", "Khoản thu cần xử lý", unpaid, "NUMBER", "Hóa đơn chưa hoàn tất", unpaid > 0 ? "orange" : "green"),
                metric("notifications", "Thông báo chưa đọc", unread, "NUMBER", "Cập nhật mới từ nhà trường", unread > 0 ? "blue" : "green")
        );
        List<DashboardDtos.Chart> charts = List.of(
                chart("Kết quả của con", "Trung bình các môn đã đủ đầu điểm", "COLUMN", " điểm",
                        childRows.stream().map(child -> new DashboardDtos.Datum(child.fullName(),
                                valueOrZero(gradeCalculations.overallAverage(List.of(child.id()))))).toList()),
                chart("Tình trạng khoản thu", "Số hóa đơn theo trạng thái", "BAR", " hóa đơn",
                        childId == null || childId.isBlank() ? rows("""
                        select case i.status when 'PAID' then 'Đã thanh toán' when 'PARTIAL' then 'Thanh toán một phần'
                               when 'OVERDUE' then 'Quá hạn' else 'Chưa thanh toán' end label, count(*) metric_value
                        from invoices i where i.parent_id = ? or i.student_id in
                          (select student_id from parent_student where parent_id = ?)
                        group by i.status order by metric_value desc
                        """, parentId, parentId) : rows("""
                        select case i.status when 'PAID' then 'Đã thanh toán' when 'PARTIAL' then 'Thanh toán một phần'
                               when 'OVERDUE' then 'Quá hạn' else 'Chưa thanh toán' end label, count(*) metric_value
                        from invoices i where i.student_id = ? group by i.status order by metric_value desc
                        """, childId))
        );
        return response("PARENT", "STUDENT", childIds, metrics, charts, parentShortcuts(childId));
    }

    private DashboardDtos.Response response(String role, String objectType, List<String> objectIds,
                                             List<DashboardDtos.Metric> metrics,
                                             List<DashboardDtos.Chart> charts,
                                             List<DashboardDtos.Shortcut> shortcuts) {
        return new DashboardDtos.Response(
                Instant.now(),
                new DashboardDtos.Scope(role, objectType, objectIds),
                metrics,
                charts,
                shortcuts,
                List.of()
        );
    }

    private List<DashboardDtos.Shortcut> adminShortcuts() {
        return List.of(
                shortcut("active-students", "Học sinh đang hoạt động", "users", Map.of("role", "STUDENT", "status", "ACTIVE")),
                shortcut("timetable-conflicts", "Xung đột thời khóa biểu", "timetable", Map.of("conflicts", "true")),
                shortcut("overdue-invoices", "Hóa đơn quá hạn", "finance", Map.of("status", "OVERDUE")),
                shortcut("open-debt", "Công nợ cần xử lý", "finance", Map.of("status", "OPEN"))
        );
    }

    private List<DashboardDtos.Shortcut> academicShortcuts() {
        return List.of(
                shortcut("classes", "Danh sách lớp", "classes", Map.of()),
                shortcut("timetable", "Thời khóa biểu", "timetable", Map.of()),
                shortcut("exams", "Kỳ thi sắp tới", "exams", Map.of("status", "UPCOMING"))
        );
    }

    private List<DashboardDtos.Shortcut> accountantShortcuts() {
        return List.of(
                shortcut("overdue-invoices", "Hóa đơn quá hạn", "finance", Map.of("status", "OVERDUE")),
                shortcut("open-debt", "Công nợ cần xử lý", "finance", Map.of("status", "OPEN")),
                shortcut("reconciliation", "Đối soát chờ xử lý", "reconciliation", Map.of("status", "PENDING"))
        );
    }

    private List<DashboardDtos.Shortcut> teacherShortcuts() {
        return List.of(
                shortcut("today-periods", "Tiết dạy hôm nay", "timetable", Map.of("range", "TODAY")),
                shortcut("attendance-pending", "Điểm danh cần hoàn tất", "attendance", Map.of("status", "PENDING")),
                shortcut("ungraded-assignments", "Bài nộp chưa chấm", "assignments", Map.of("grading", "PENDING")),
                shortcut("invigilation", "Lịch coi thi sắp tới", "exams", Map.of("task", "INVIGILATION"))
        );
    }

    private List<DashboardDtos.Shortcut> studentShortcuts() {
        return List.of(
                shortcut("nearest-class", "Tiết học gần nhất", "timetable", Map.of("range", "NEXT")),
                shortcut("upcoming-assignment", "Bài tập sắp đến hạn", "assignments", Map.of("status", "OPEN")),
                shortcut("unread-notifications", "Thông báo chưa đọc", "notifications", Map.of("read", "false")),
                shortcut("absences", "Lịch sử vắng học", "attendance", Map.of("status", "ABSENT"))
        );
    }

    private List<DashboardDtos.Shortcut> parentShortcuts(String childId) {
        Map<String, String> childFilter = childId == null || childId.isBlank()
                ? Map.of()
                : Map.of("childId", childId);
        return List.of(
                shortcut("nearest-exam", "Kỳ thi gần nhất", "exams", childFilter),
                shortcut("attendance", "Chuyên cần của con", "attendance", childFilter),
                shortcut("open-invoices", "Khoản thu chưa hoàn tất", "finance",
                        childId == null || childId.isBlank()
                                ? Map.of("status", "OPEN")
                                : Map.of("status", "OPEN", "childId", childId))
        );
    }

    private DashboardDtos.Shortcut shortcut(String key, String label, String target, Map<String, String> filters) {
        return new DashboardDtos.Shortcut(key, label, target, filters);
    }

    private double unread(String userId) {
        return number("select count(*) from notifications where recipient_id = ? and read = false", userId);
    }

    private DashboardDtos.Metric metric(String key, String label, double value, String format, String hint, String tone) {
        return new DashboardDtos.Metric(key, label, value, format, hint, tone);
    }

    private DashboardDtos.Chart chart(String title, String subtitle, String type, String suffix, List<DashboardDtos.Datum> data) {
        double max = data.stream().mapToDouble(DashboardDtos.Datum::value).max().orElse(0);
        return new DashboardDtos.Chart(title, subtitle, type, suffix, max, data);
    }

    private double number(String sql, Object... args) {
        Number value = jdbc.queryForObject(sql, Number.class, args);
        return value == null ? 0 : value.doubleValue();
    }

    private double valueOrZero(Double value) {
        return value == null ? 0 : value;
    }

    private List<DashboardDtos.Datum> subjectAverageRows(Collection<String> studentIds) {
        return gradeCalculations.subjectAverages(studentIds).entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(8)
                .map(entry -> new DashboardDtos.Datum(entry.getKey(), entry.getValue()))
                .toList();
    }

    private List<DashboardDtos.Datum> rows(String sql, Object... args) {
        return jdbc.query(sql, this::datum, args);
    }

    private DashboardDtos.Datum datum(ResultSet rs, int rowNumber) throws SQLException {
        Number value = (Number) rs.getObject("metric_value");
        return new DashboardDtos.Datum(rs.getString("label"), value == null ? 0 : value.doubleValue());
    }
}
