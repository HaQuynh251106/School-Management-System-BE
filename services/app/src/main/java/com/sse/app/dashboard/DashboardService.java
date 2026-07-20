package com.sse.app.dashboard;

import com.sse.app.security.CurrentUser;
import com.sse.app.security.CurrentUserHolder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

@Service
public class DashboardService {
    private final JdbcTemplate jdbc;

    public DashboardService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public DashboardDtos.Response getDashboard() {
        CurrentUser user = CurrentUserHolder.require();
        return switch (user.role()) {
            case "ADMIN" -> admin();
            case "TEACHER" -> teacher(user.id());
            case "STUDENT" -> student(user.id());
            case "PARENT" -> parent(user.id());
            default -> new DashboardDtos.Response(List.of(), List.of());
        };
    }

    private DashboardDtos.Response admin() {
        double activeUsers = number("select count(*) from users where status = 'ACTIVE'");
        double classes = number("select count(*) from classes");
        double attendance = number("""
                select coalesce(100.0 * sum(case when status = 'PRESENT' then 1 else 0 end) / nullif(count(*), 0), 0)
                from attendance_records where date = current_date
                """);
        double openAlerts = number("""
                select (select count(*) from invoices where status in ('PENDING', 'PARTIAL', 'OVERDUE'))
                     + (select count(*) from attendance_records where date = current_date and status in ('ABSENT', 'LATE'))
                """);

        List<DashboardDtos.Metric> metrics = List.of(
                metric("users", "Tài khoản hoạt động", activeUsers, "NUMBER", "Người dùng đang có quyền truy cập", "blue"),
                metric("classes", "Lớp học", classes, "NUMBER", "Quy mô lớp trong hệ thống", "violet"),
                metric("attendance", "Chuyên cần hôm nay", attendance, "PERCENT", "Tỷ lệ có mặt toàn trường", "green"),
                metric("alerts", "Cần xử lý", openAlerts, "NUMBER", "Vắng, trễ và công nợ đang mở", openAlerts > 0 ? "orange" : "green")
        );
        List<DashboardDtos.Chart> charts = List.of(
                chart("Cơ cấu người dùng", "Số tài khoản theo từng vai trò", "BAR", "", rows("""
                        select case role when 'ADMIN' then 'Quản trị' when 'TEACHER' then 'Giáo viên'
                               when 'STUDENT' then 'Học sinh' when 'PARENT' then 'Phụ huynh' else role end label,
                               count(*) value
                        from users group by role order by value desc
                        """)),
                chart("Quy mô lớp học", "Sĩ số các lớp đông nhất", "COLUMN", " HS", rows("""
                        select code label, student_count value from classes order by student_count desc, code limit 8
                        """))
        );
        return new DashboardDtos.Response(metrics, charts);
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
                        select case day_of_week when 'MONDAY' then 'T2' when 'TUESDAY' then 'T3'
                               when 'WEDNESDAY' then 'T4' when 'THURSDAY' then 'T5'
                               when 'FRIDAY' then 'T6' when 'SATURDAY' then 'T7' else day_of_week end label,
                               count(*) value
                        from timetable_slots where teacher_id = ? group by day_of_week
                        order by min(case day_of_week when 'MONDAY' then 1 when 'TUESDAY' then 2 when 'WEDNESDAY' then 3
                                     when 'THURSDAY' then 4 when 'FRIDAY' then 5 when 'SATURDAY' then 6 else 7 end)
                        """, teacherId)),
                chart("Bài tập theo lớp", "Số bài tập đã giao", "BAR", " bài", rows("""
                        select coalesce(c.code, a.class_id) label, count(*) value
                        from assignments a left join classes c on c.id = a.class_id
                        where a.teacher_id = ? group by coalesce(c.code, a.class_id) order by value desc limit 8
                        """, teacherId))
        );
        return new DashboardDtos.Response(metrics, charts);
    }

    private DashboardDtos.Response student(String studentId) {
        double average = number("select coalesce(avg(score), 0) from grades where student_id = ?", studentId);
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
                metric("grades", "Điểm trung bình", average, "DECIMAL_1", "Trung bình các đầu điểm hiện có", "violet"),
                metric("attendance", "Tỷ lệ chuyên cần", attendance, "PERCENT", "Toàn bộ lịch sử điểm danh", "green"),
                metric("assignments", "Bài tập cần nộp", pending, "NUMBER", "Bài đang mở chưa nộp", pending > 0 ? "orange" : "green"),
                metric("notifications", "Thông báo chưa đọc", unread, "NUMBER", "Cập nhật từ giáo viên và nhà trường", unread > 0 ? "blue" : "green")
        );
        List<DashboardDtos.Chart> charts = List.of(
                chart("Kết quả theo môn", "Điểm trung bình từ dữ liệu đã nhập", "COLUMN", " điểm", rows("""
                        select subject_name label, avg(score) value from grades where student_id = ?
                        group by subject_name order by value desc limit 8
                        """, studentId)),
                chart("Tình hình chuyên cần", "Số lượt theo trạng thái", "BAR", " lượt", rows("""
                        select case status when 'PRESENT' then 'Có mặt' when 'ABSENT' then 'Vắng'
                               when 'LATE' then 'Đi trễ' when 'EXCUSED' then 'Có phép' else status end label,
                               count(*) value
                        from attendance_records where student_id = ? group by status order by value desc
                        """, studentId))
        );
        return new DashboardDtos.Response(metrics, charts);
    }

    private DashboardDtos.Response parent(String parentId) {
        double children = number("select count(*) from parent_student where parent_id = ?", parentId);
        double average = number("""
                select coalesce(avg(g.score), 0) from grades g
                where g.student_id in (select student_id from parent_student where parent_id = ?)
                """, parentId);
        double unpaid = number("""
                select count(*) from invoices i where i.status in ('PENDING', 'PARTIAL', 'OVERDUE')
                  and (i.parent_id = ? or i.student_id in (select student_id from parent_student where parent_id = ?))
                """, parentId, parentId);
        double unread = unread(parentId);

        List<DashboardDtos.Metric> metrics = List.of(
                metric("children", "Học sinh liên kết", children, "NUMBER", "Hồ sơ con đang theo dõi", "blue"),
                metric("grades", "Điểm trung bình", average, "DECIMAL_1", "Trung bình các đầu điểm của con", "violet"),
                metric("invoices", "Khoản thu cần xử lý", unpaid, "NUMBER", "Hóa đơn chưa hoàn tất", unpaid > 0 ? "orange" : "green"),
                metric("notifications", "Thông báo chưa đọc", unread, "NUMBER", "Cập nhật mới từ nhà trường", unread > 0 ? "blue" : "green")
        );
        List<DashboardDtos.Chart> charts = List.of(
                chart("Kết quả của con", "Điểm trung bình theo học sinh", "COLUMN", " điểm", rows("""
                        select u.full_name label, avg(g.score) value from parent_student ps
                        join users u on u.id = ps.student_id left join grades g on g.student_id = ps.student_id
                        where ps.parent_id = ? group by u.id, u.full_name order by u.full_name
                        """, parentId)),
                chart("Tình trạng khoản thu", "Số hóa đơn theo trạng thái", "BAR", " hóa đơn", rows("""
                        select case i.status when 'PAID' then 'Đã thanh toán' when 'PARTIAL' then 'Thanh toán một phần'
                               when 'OVERDUE' then 'Quá hạn' else 'Chưa thanh toán' end label, count(*) value
                        from invoices i where i.parent_id = ? or i.student_id in
                          (select student_id from parent_student where parent_id = ?)
                        group by i.status order by value desc
                        """, parentId, parentId))
        );
        return new DashboardDtos.Response(metrics, charts);
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

    private List<DashboardDtos.Datum> rows(String sql, Object... args) {
        return jdbc.query(sql, this::datum, args);
    }

    private DashboardDtos.Datum datum(ResultSet rs, int rowNumber) throws SQLException {
        Number value = (Number) rs.getObject("value");
        return new DashboardDtos.Datum(rs.getString("label"), value == null ? 0 : value.doubleValue());
    }
}
