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
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
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
            default -> new DashboardDtos.Response(List.of(), List.of());
        };
    }

    private DashboardDtos.Response academicStaff() {
        String academicYearId = activeAcademicYearId();
        double classes = academicYearId.isBlank() ? 0 : number("""
                select count(*) from classes
                where academic_year_id=? and (status is null or status='ACTIVE')
                """, academicYearId);
        int unassignedStudents = integer("""
                select count(*) from users where role='STUDENT' and status='ACTIVE'
                and (class_id is null or trim(class_id) = '')
                """);
        int classesWithoutHomeroom = academicYearId.isBlank() ? 0 : integer("""
                select count(*) from classes where status='ACTIVE'
                and academic_year_id=?
                and (homeroom_teacher_id is null or trim(homeroom_teacher_id) = '')
                """, academicYearId);
        String semesterId = activeSemesterId();
        int slots = semesterId.isBlank() ? 0
                : integer("select count(*) from timetable_slots where semester_id = ?", semesterId);
        int requiredSlots = semesterId.isBlank() ? 0
                : integer("select coalesce(sum(weekly_periods),0) from teaching_assignments where semester_id = ? and status='ACTIVE'", semesterId);
        double coverage = requiredSlots == 0 ? 0 : Math.min(100, 100.0 * slots / requiredSlots);
        double upcomingExams = academicYearId.isBlank() ? 0 : number("""
                select count(*) from exam_periods where academic_year_id=?
                and status in ('DRAFT','CONFIRMED') and end_date >= current_date
                """, academicYearId);
        int draftExams = academicYearId.isBlank() ? 0 : integer("""
                select count(*) from exam_periods where academic_year_id=?
                and status='DRAFT' and end_date >= current_date
                """, academicYearId);
        List<DashboardDtos.Metric> metrics = List.of(
                metric("classes", "Lớp đang vận hành", classes, "NUMBER",
                        classesWithoutHomeroom == 0 ? "Tất cả lớp đã có chủ nhiệm" : classesWithoutHomeroom + " lớp thiếu chủ nhiệm", classesWithoutHomeroom > 0 ? "orange" : "blue"),
                metric("calendar", "Tiến độ thời khóa biểu", coverage, "PERCENT",
                        slots + "/" + requiredSlots + " tiết đã xếp", coverage < 100 ? "orange" : "green"),
                metric("students", "Học sinh chưa có lớp", unassignedStudents, "NUMBER",
                        "Hồ sơ cần hoàn tất phân lớp", unassignedStudents > 0 ? "orange" : "green"),
                metric("alerts", "Kỳ thi cần theo dõi", upcomingExams, "NUMBER", "Kỳ thi nháp hoặc đã xác nhận", upcomingExams > 0 ? "orange" : "green")
        );
        List<DashboardDtos.Chart> charts = List.of(
                chart("Quy mô theo khối", "Số học sinh của năm học hiện hành", "COLUMN", " học sinh",
                        academicYearId.isBlank() ? List.of() : rows("""
                                select c.grade_level label, count(u.id) metric_value
                                from classes c left join users u on u.class_id=c.id
                                  and u.role='STUDENT' and u.status='ACTIVE'
                                where c.academic_year_id=? and (c.status is null or c.status='ACTIVE')
                                group by c.grade_level order by c.grade_level
                                """, academicYearId)),
                chart("Tiến độ thời khóa biểu", "Số tiết đã xếp theo khối trong học kỳ hiện hành", "BAR", " tiết",
                        semesterId.isBlank() || academicYearId.isBlank() ? List.of() : rows("""
                                select c.grade_level label, count(t.id) metric_value
                                from classes c left join timetable_slots t
                                  on t.class_id=c.id and t.semester_id=?
                                where c.academic_year_id=? and (c.status is null or c.status='ACTIVE')
                                group by c.grade_level order by c.grade_level
                                """, semesterId, academicYearId))
        );
        List<DashboardDtos.WorkItem> workItems = new ArrayList<>();
        addWorkItem(workItems, unassignedStudents, "unassigned-students", "Học sinh chưa được phân lớp",
                "Hoàn tất phân lớp để sẵn sàng xếp thời khóa biểu.", "học sinh", "CRITICAL", "E1");
        addWorkItem(workItems, classesWithoutHomeroom, "missing-homeroom", "Lớp chưa có giáo viên chủ nhiệm",
                "Phân công chủ nhiệm trước khi vận hành lớp.", "lớp", "WARNING", "E1");
        addWorkItem(workItems, Math.max(0, requiredSlots - slots), "timetable-incomplete", "Thời khóa biểu chưa hoàn tất",
                "Kiểm tra các tiết chưa có khung giờ phù hợp.", "tiết", "WARNING", "E2");
        addWorkItem(workItems, draftExams, "draft-exams", "Kỳ thi đang ở bản nháp",
                "Hoàn thiện lịch, phòng và phân công trước khi công bố.", "kỳ thi", "INFO", "E3");
        addHealthyWorkItem(workItems, "E2");
        return new DashboardDtos.Response(metrics, charts,
                roleOverview("ACADEMIC_STAFF", academicCalendarItems(), workItems));
    }

    private DashboardDtos.Response accountant() {
        String academicYearId = activeAcademicYearId();
        double total = academicYearId.isBlank() ? 0 : number("""
                select coalesce(sum(i.total_amount),0) from invoices i
                join fee_periods fp on fp.id=i.fee_period_id where fp.academic_year_id=?
                """, academicYearId);
        double collected = academicYearId.isBlank() ? 0 : number("""
                select coalesce(sum(i.paid_amount),0) from invoices i
                join fee_periods fp on fp.id=i.fee_period_id where fp.academic_year_id=?
                """, academicYearId);
        double debt = Math.max(0, total - collected);
        double overdue = academicYearId.isBlank() ? 0 : number("""
                select count(*) from invoices i join fee_periods fp on fp.id=i.fee_period_id
                where fp.academic_year_id=? and i.status='OVERDUE'
                """, academicYearId);
        int unpaidInvoices = academicYearId.isBlank() ? 0 : integer("""
                select count(*) from invoices i join fee_periods fp on fp.id=i.fee_period_id
                where fp.academic_year_id=? and i.status in ('PENDING','PARTIAL','OVERDUE')
                """, academicYearId);
        int draftFeePeriods = academicYearId.isBlank() ? 0 : integer("""
                select count(*) from fee_periods where academic_year_id=? and status='DRAFT'
                """, academicYearId);
        int pendingPayments = academicYearId.isBlank() ? 0 : integer("""
                select count(*) from payments p join invoices i on i.id=p.invoice_id
                join fee_periods fp on fp.id=i.fee_period_id
                where fp.academic_year_id=? and p.status in ('PENDING','PROCESSING')
                """, academicYearId);
        List<DashboardDtos.Metric> metrics = List.of(
                metric("invoices", "Tổng phải thu", total, "CURRENCY", "Giá trị hóa đơn đã phát hành", "blue"),
                metric("payments", "Đã thu", collected, "CURRENCY", "Tổng tiền đã ghi nhận", "green"),
                metric("alerts", "Còn phải thu", debt, "CURRENCY", "Công nợ toàn trường", debt > 0 ? "orange" : "green"),
                metric("overdue", "Hóa đơn quá hạn", overdue, "NUMBER", "Cần phối hợp nhắc phụ huynh", overdue > 0 ? "red" : "green")
        );
        List<DashboardDtos.Chart> charts = List.of(
                chart("Trạng thái hóa đơn", "Hóa đơn trong năm học hiện hành", "BAR", " hóa đơn",
                        academicYearId.isBlank() ? List.of() : rows("""
                                select case i.status when 'PAID' then 'Đã thanh toán' when 'PARTIAL' then 'Một phần'
                                       when 'OVERDUE' then 'Quá hạn' else 'Chưa thanh toán' end label,
                                       count(*) metric_value from invoices i
                                join fee_periods fp on fp.id=i.fee_period_id
                                where fp.academic_year_id=? group by i.status order by metric_value desc
                                """, academicYearId)),
                chart("Công nợ theo lớp", "Các lớp còn phải thu nhiều nhất", "COLUMN", " đ",
                        academicYearId.isBlank() ? List.of() : rows("""
                                select coalesce(i.class_code,'Chưa có lớp') label,
                                       coalesce(sum(i.total_amount-i.paid_amount),0) metric_value
                                from invoices i join fee_periods fp on fp.id=i.fee_period_id
                                where fp.academic_year_id=?
                                group by coalesce(i.class_code,'Chưa có lớp')
                                order by metric_value desc limit 8
                                """, academicYearId))
        );
        List<DashboardDtos.WorkItem> workItems = new ArrayList<>();
        addWorkItem(workItems, (int) overdue, "overdue-invoices", "Hóa đơn đã quá hạn",
                "Lọc theo lớp và phối hợp GVCN nhắc phụ huynh.", "hóa đơn", "CRITICAL", "F1");
        addWorkItem(workItems, unpaidInvoices, "open-invoices", "Công nợ chưa hoàn tất",
                "Theo dõi các hóa đơn chưa thanh toán đủ.", "hóa đơn", "WARNING", "F1");
        addWorkItem(workItems, draftFeePeriods, "draft-fees", "Đợt thu chưa phát hành",
                "Kiểm tra nội dung, thời hạn và đối tượng áp dụng.", "đợt thu", "INFO", "F1");
        addWorkItem(workItems, pendingPayments, "pending-payments", "Giao dịch chờ đối soát",
                "Xác minh giao dịch chưa có kết quả cuối cùng.", "giao dịch", "WARNING", "F1");
        addHealthyWorkItem(workItems, "F1");
        return new DashboardDtos.Response(metrics, charts,
                roleOverview("ACCOUNTANT", accountantCalendarItems(), workItems));
    }

    private DashboardDtos.Response admin() {
        String activeAcademicYearId = activeAcademicYearId();
        int activeStudents = integer("select count(*) from users where role = 'STUDENT' and status = 'ACTIVE'");
        int activeTeachers = integer("select count(*) from users where role = 'TEACHER' and status = 'ACTIVE'");
        int activeParents = integer("select count(*) from users where role = 'PARENT' and status = 'ACTIVE'");
        int unassignedStudents = integer("""
                select count(*) from users where role = 'STUDENT' and status = 'ACTIVE'
                and (class_id is null or trim(class_id) = '')
                """);
        int inactiveAccounts = integer("select count(*) from users where status <> 'ACTIVE'");
        int classes = activeAcademicYearId.isBlank() ? 0 : integer("""
                select count(*) from classes where academic_year_id=? and status='ACTIVE'
                """, activeAcademicYearId);
        int classesWithoutHomeroom = activeAcademicYearId.isBlank() ? 0 : integer("""
                select count(*) from classes where status = 'ACTIVE'
                and academic_year_id=?
                and (homeroom_teacher_id is null or trim(homeroom_teacher_id) = '')
                """, activeAcademicYearId);
        double attendance = number("""
                select coalesce(100.0 * sum(case when status = 'PRESENT' then 1 else 0 end) / nullif(count(*), 0), 0)
                from attendance_records where date = current_date
                """);
        int attendanceRecords = integer("select count(*) from attendance_records where date = current_date");
        int present = integer("select count(*) from attendance_records where date = current_date and status = 'PRESENT'");
        int excused = integer("select count(*) from attendance_records where date = current_date and status = 'ABSENT_EXCUSED'");
        int unexcused = integer("select count(*) from attendance_records where date = current_date and status = 'ABSENT_UNEXCUSED'");
        int late = integer("select count(*) from attendance_records where date = current_date and status = 'LATE'");

        String academicYear = text("""
                select code from academic_years
                order by case when status = 'ACTIVE' then 0 else 1 end, start_date desc limit 1
                """);
        String academicYearStatus = text("""
                select status from academic_years
                order by case when status = 'ACTIVE' then 0 else 1 end, start_date desc limit 1
                """);
        String semester = text("""
                select name from semesters
                order by case when status = 'ACTIVE' then 0 else 1 end, start_date desc limit 1
                """);
        String semesterStatus = text("""
                select status from semesters
                order by case when status = 'ACTIVE' then 0 else 1 end, start_date desc limit 1
                """);
        String activeSemesterId = text("""
                select id from semesters
                order by case when status = 'ACTIVE' then 0 else 1 end, start_date desc limit 1
                """);
        int scheduledPeriods = activeSemesterId.isBlank() ? 0
                : integer("select count(*) from timetable_slots where semester_id = ?", activeSemesterId);
        int requiredPeriods = activeSemesterId.isBlank() ? 0
                : integer("select coalesce(sum(weekly_periods), 0) from teaching_assignments where semester_id = ? and status = 'ACTIVE'", activeSemesterId);
        double timetableCoverage = requiredPeriods == 0 ? 0 : Math.min(100, 100.0 * scheduledPeriods / requiredPeriods);

        int upcomingExams = activeAcademicYearId.isBlank() ? 0 : integer("""
                select count(*) from exam_periods
                where academic_year_id=?
                and status in ('DRAFT','CONFIRMED') and end_date >= current_date
                """, activeAcademicYearId);
        int draftExams = activeAcademicYearId.isBlank() ? 0 : integer("""
                select count(*) from exam_periods
                where academic_year_id=? and status = 'DRAFT' and end_date >= current_date
                """, activeAcademicYearId);
        double totalReceivables = activeAcademicYearId.isBlank() ? 0 : number("""
                select coalesce(sum(i.total_amount),0) from invoices i
                join fee_periods fp on fp.id=i.fee_period_id where fp.academic_year_id=?
                """, activeAcademicYearId);
        double collectedAmount = activeAcademicYearId.isBlank() ? 0 : number("""
                select coalesce(sum(i.paid_amount),0) from invoices i
                join fee_periods fp on fp.id=i.fee_period_id where fp.academic_year_id=?
                """, activeAcademicYearId);
        double outstandingAmount = Math.max(0, totalReceivables - collectedAmount);
        int overdueInvoices = activeAcademicYearId.isBlank() ? 0 : integer("""
                select count(*) from invoices i join fee_periods fp on fp.id=i.fee_period_id
                where fp.academic_year_id=? and i.status='OVERDUE'
                """, activeAcademicYearId);

        List<DashboardDtos.WorkItem> workItems = new ArrayList<>();
        addWorkItem(workItems, unassignedStudents, "unassigned-students", "Học sinh chưa được xếp lớp",
                "Hoàn thiện lớp học trước khi phát hành thời khóa biểu.", "học sinh", "CRITICAL", "A1S");
        addWorkItem(workItems, classesWithoutHomeroom, "missing-homeroom", "Lớp chưa có giáo viên chủ nhiệm",
                "Yêu cầu Giáo vụ hoàn tất phân công chủ nhiệm.", "lớp", "WARNING", "A8");
        addWorkItem(workItems, inactiveAccounts, "inactive-accounts", "Tài khoản chưa hoạt động",
                "Rà soát tài khoản bị khóa hoặc chưa sẵn sàng đăng nhập.", "tài khoản", "WARNING", "A1O");
        int missingPeriods = Math.max(0, requiredPeriods - scheduledPeriods);
        addWorkItem(workItems, missingPeriods, "timetable-incomplete", "Thời khóa biểu chưa hoàn tất",
                "Theo dõi Giáo vụ xử lý các tiết chưa được xếp.", "tiết", "WARNING", "A8");
        addWorkItem(workItems, draftExams, "draft-exams", "Kỳ thi chưa được phát hành",
                "Kiểm tra tiến độ chuẩn bị kỳ thi với bộ phận Giáo vụ.", "kỳ thi", "INFO", "A8");
        if (workItems.isEmpty()) {
            workItems.add(new DashboardDtos.WorkItem("healthy", "Không có việc tồn đọng quan trọng",
                    "Các bộ phận đang vận hành trong ngưỡng kiểm soát.", 0, "", "SUCCESS", "A8"));
        }
        List<DashboardDtos.CalendarItem> calendarItems = adminCalendarItems();

        int openTasks = workItems.stream()
                .filter(item -> !"SUCCESS".equals(item.severity()))
                .mapToInt(item -> (int) item.value())
                .sum();

        List<DashboardDtos.Metric> metrics = List.of(
                metric("students", "Học sinh đang học", activeStudents, "NUMBER",
                        unassignedStudents == 0 ? "Tất cả đã được xếp lớp" : unassignedStudents + " học sinh chưa có lớp", unassignedStudents > 0 ? "orange" : "blue"),
                metric("teachers", "Giáo viên hoạt động", activeTeachers, "NUMBER",
                        classesWithoutHomeroom == 0 ? classes + " lớp đã có chủ nhiệm" : classesWithoutHomeroom + " lớp thiếu chủ nhiệm", classesWithoutHomeroom > 0 ? "orange" : "violet"),
                metric("attendance", "Có mặt hôm nay", attendance,
                        attendanceRecords == 0 ? "PERCENT_OR_EMPTY" : "PERCENT",
                        attendanceRecords == 0 ? "Chưa phát sinh dữ liệu điểm danh" : present + "/" + attendanceRecords + " lượt ghi nhận có mặt", "green"),
                metric("tasks", "Việc cần xử lý", openTasks, "NUMBER",
                        openTasks == 0 ? "Không có tồn đọng quan trọng" : workItems.size() + " nhóm việc đang mở",
                        openTasks > 0 ? "orange" : "green")
        );
        List<DashboardDtos.Chart> charts = List.of(
                chart("Học sinh theo khối", "Quy mô trong năm học hiện hành", "COLUMN", " học sinh",
                        activeAcademicYearId.isBlank() ? List.of() : rows("""
                                select c.grade_level label, count(u.id) metric_value
                                from classes c left join users u on u.class_id=c.id
                                  and u.role='STUDENT' and u.status='ACTIVE'
                                where c.academic_year_id=? and c.status='ACTIVE'
                                group by c.grade_level order by c.grade_level
                                """, activeAcademicYearId)),
                chart("Trạng thái tài khoản", "Mức độ sẵn sàng truy cập hệ thống", "BAR", " tài khoản", rows("""
                        select case status when 'ACTIVE' then 'Đang hoạt động'
                               when 'LOCKED' then 'Đã khóa' when 'INACTIVE' then 'Ngừng hoạt động'
                               else status end label, count(*) metric_value
                        from users group by status order by metric_value desc
                        """))
        );
        DashboardDtos.AdminOverview overview = new DashboardDtos.AdminOverview(
                academicYear, academicYearStatus, semester, semesterStatus, Instant.now().toString(),
                activeStudents, activeTeachers, activeParents, unassignedStudents, inactiveAccounts,
                classes, classesWithoutHomeroom, attendanceRecords, present, excused, unexcused, late,
                scheduledPeriods, requiredPeriods, timetableCoverage, upcomingExams, draftExams,
                totalReceivables, collectedAmount, outstandingAmount, overdueInvoices,
                calendarItems, List.copyOf(workItems)
        );
        return new DashboardDtos.Response(metrics, charts, overview);
    }

    private DashboardDtos.Response teacher(String teacherId) {
        String semesterId = activeSemesterId();
        String academicYearId = activeAcademicYearId();
        double classes = semesterId.isBlank() ? 0 : number("""
                select count(distinct class_id) from teaching_assignments
                where teacher_id=? and semester_id=? and status='ACTIVE'
                """, teacherId, semesterId);
        int weeklySlots = semesterId.isBlank() ? 0 : integer("""
                select count(*) from timetable_slots where teacher_id=? and semester_id=?
                """, teacherId, semesterId);
        String[] todayAliases = dayAliases(LocalDate.now().getDayOfWeek());
        int todaySlots = semesterId.isBlank() ? 0 : integer("""
                select count(*) from timetable_slots where teacher_id = ?
                and semester_id=?
                and upper(day_of_week) in (?, ?)
                """, teacherId, semesterId, todayAliases[0], todayAliases[1]);
        int pendingGrading = academicYearId.isBlank() ? 0 : integer("""
                select count(*) from assignment_submissions s join assignments a on a.id=s.assignment_id
                join classes c on c.id=a.class_id
                where a.teacher_id=? and s.graded_at is null and s.submitted_at is not null
                and c.academic_year_id=?
                and s.status in ('SUBMITTED','LATE','RESUBMISSION_ALLOWED')
                """, teacherId, academicYearId);
        double unread = unread(teacherId);

        List<DashboardDtos.Metric> metrics = List.of(
                metric("classes", "Lớp đang phụ trách", classes, "NUMBER", "Các lớp được phân công bộ môn", "blue"),
                metric("calendar", "Tiết dạy hôm nay", todaySlots, "NUMBER", weeklySlots + " tiết trong cả tuần", "violet"),
                metric("assignments", "Bài nộp chờ chấm", pendingGrading, "NUMBER", "Bài làm chưa có kết quả", pendingGrading > 0 ? "orange" : "green"),
                metric("notifications", "Thông báo chưa đọc", unread, "NUMBER", "Thông tin cần xem", unread > 0 ? "orange" : "green")
        );
        List<DashboardDtos.Chart> charts = new ArrayList<>();
        List<DashboardDtos.Datum> homeroomGender = academicYearId.isBlank() ? List.of() : rows("""
                select case upper(coalesce(u.gender,''))
                         when 'MALE' then 'Nam' when 'M' then 'Nam'
                         when 'FEMALE' then 'Nữ' when 'F' then 'Nữ'
                         else 'Chưa cập nhật' end label, count(*) metric_value
                from classes c join users u on u.class_id=c.id
                where c.homeroom_teacher_id=? and c.academic_year_id=?
                  and c.status='ACTIVE' and u.role='STUDENT' and u.status='ACTIVE'
                group by 1 order by 1
                """, teacherId, academicYearId);
        if (!homeroomGender.isEmpty()) {
            charts.add(chart("Cơ cấu lớp chủ nhiệm", "Tỷ lệ học sinh nam và nữ trong lớp", "PIE", " học sinh", homeroomGender));
        }
        charts.add(
                chart("Nhịp dạy trong tuần", "Số tiết theo từng ngày", "COLUMN", " tiết", rows("""
                        select case day_of_week when 'MON' then 'T2' when 'MONDAY' then 'T2'
                               when 'TUE' then 'T3' when 'TUESDAY' then 'T3'
                               when 'WED' then 'T4' when 'WEDNESDAY' then 'T4'
                               when 'THU' then 'T5' when 'THURSDAY' then 'T5'
                               when 'FRI' then 'T6' when 'FRIDAY' then 'T6'
                               when 'SAT' then 'T7' when 'SATURDAY' then 'T7' else day_of_week end label,
                               count(*) metric_value
                        from timetable_slots where teacher_id = ? and semester_id=? group by day_of_week
                        order by min(case day_of_week when 'MON' then 1 when 'MONDAY' then 1
                                     when 'TUE' then 2 when 'TUESDAY' then 2 when 'WED' then 3 when 'WEDNESDAY' then 3
                                     when 'THU' then 4 when 'THURSDAY' then 4 when 'FRI' then 5 when 'FRIDAY' then 5
                                     when 'SAT' then 6 when 'SATURDAY' then 6 else 7 end)
                        """, teacherId, semesterId)));
        int unattendedSlots = semesterId.isBlank() ? 0 : integer("""
                select count(*) from timetable_slots t where t.teacher_id = ?
                and t.semester_id=?
                and upper(t.day_of_week) in (?, ?)
                and not exists (select 1 from attendance_records ar where ar.slot_id=t.id and ar.date=current_date)
                """, teacherId, semesterId, todayAliases[0], todayAliases[1]);
        int leaveRequests = integer("""
                select count(*) from leave_requests where homeroom_teacher_id=? and status='PENDING_HOMEROOM'
                """, teacherId);
        int incompleteGradebooks = activeSemesterId().isBlank() ? 0 : integer("""
                select count(*) from teaching_assignments ta
                where ta.teacher_id=? and ta.semester_id=? and ta.status='ACTIVE'
                  and not exists (select 1 from gradebook_locks gl
                                  where gl.semester_id=ta.semester_id and gl.class_id=ta.class_id
                                    and gl.subject_id=ta.subject_id and gl.locked=true)
                """, teacherId, activeSemesterId());
        int examDuties = integer("""
                select count(*) from exam_grading_assignments ega
                join exam_periods ep on ep.id=ega.exam_period_id
                where ega.teacher_id=? and ep.status in ('CONFIRMED','COMPLETED')
                  and ep.end_date >= current_date - 30
                """, teacherId) + integer("""
                select count(*) from exam_rooms er join exam_schedules es on es.id=er.schedule_id
                join exam_periods ep on ep.id=es.exam_period_id
                where (er.proctor_one_id=? or er.proctor_two_id=?) and ep.status='CONFIRMED'
                  and ep.end_date >= current_date - 7
                """, teacherId, teacherId);
        List<DashboardDtos.WorkItem> workItems = new ArrayList<>();
        addWorkItem(workItems, unattendedSlots, "attendance", "Tiết dạy chưa ghi nhận điểm danh",
                "Mở sổ điểm danh và hoàn tất theo từng tiết.", "tiết", "CRITICAL", "B3");
        addWorkItem(workItems, pendingGrading, "grading", "Bài làm đang chờ chấm",
                "Ưu tiên bài gần hạn trả kết quả cho học sinh.", "bài", "WARNING", "B5");
        addWorkItem(workItems, leaveRequests, "leave-requests", "Đơn xin nghỉ chờ duyệt",
                "Xem xác nhận của phụ huynh và phản hồi học sinh.", "đơn", "INFO", "B9");
        addWorkItem(workItems, incompleteGradebooks, "gradebooks", "Sổ điểm chưa hoàn tất",
                "Kiểm tra đủ đầu điểm và xác nhận hoàn tất theo lớp, môn.", "sổ điểm", "WARNING", "B4");
        addWorkItem(workItems, examDuties, "exam-duties", "Nhiệm vụ khảo thí đang mở",
                "Theo dõi lịch coi thi, chấm thi và thời hạn nhập điểm.", "nhiệm vụ", "INFO", "B12");
        addWorkItem(workItems, (int) unread, "unread", "Thông báo chưa đọc",
                "Kiểm tra các thông tin điều hành mới nhất.", "thông báo", "INFO", "B7");
        addHealthyWorkItem(workItems, "B2");
        return new DashboardDtos.Response(metrics, charts,
                roleOverview("TEACHER", teacherCalendarItems(teacherId), workItems),
                teacherOverview(teacherId, todayAliases));
    }

    private DashboardDtos.TeacherOverview teacherOverview(String teacherId, String[] todayAliases) {
        String academicYearId = activeAcademicYearId();
        String semesterId = activeSemesterId();
        List<String> homeroomClassCodes = academicYearId.isBlank() ? List.of() : jdbc.queryForList("""
                select code from classes
                where homeroom_teacher_id=? and academic_year_id=?
                  and (status is null or status='ACTIVE')
                order by code
                """, String.class, teacherId, academicYearId);
        List<DashboardDtos.TeacherLesson> todayLessons = semesterId.isBlank() ? List.of() : jdbc.query("""
                select t.id, t.period_no, t.start_time, t.end_time, t.class_id,
                       coalesce(c.code, t.class_id) class_code, t.subject_id, t.subject_name,
                       t.room_code,
                       case when exists (
                           select 1 from attendance_records ar
                           where ar.slot_id=t.id and ar.date=current_date
                       ) then true else false end attendance_recorded
                from timetable_slots t
                left join classes c on c.id=t.class_id
                where t.teacher_id=? and t.semester_id=? and upper(t.day_of_week) in (?, ?)
                order by t.start_time, t.period_no, class_code
                """, (rs, rowNumber) -> {
            String startTime = safe(rs.getString("start_time"));
            String endTime = safe(rs.getString("end_time"));
            return new DashboardDtos.TeacherLesson(
                    rs.getString("id"), rs.getInt("period_no"), startTime, endTime,
                    rs.getString("class_id"), rs.getString("class_code"),
                    rs.getString("subject_id"), rs.getString("subject_name"),
                    safe(rs.getString("room_code")), lessonStatus(startTime, endTime),
                    rs.getBoolean("attendance_recorded")
            );
        }, teacherId, semesterId, todayAliases[0], todayAliases[1]);

        LocalDate attendanceFrom = LocalDate.now().minusDays(30);
        List<DashboardDtos.AttentionStudent> attentionStudents = jdbc.query("""
                select u.id student_id, u.student_code, u.full_name student_name,
                       c.id class_id, c.code class_code,
                       (select count(*) from attendance_records ar
                        where ar.student_id=u.id and ar.date>=?
                          and ar.status in ('ABSENT_UNEXCUSED','LATE')) attendance_alerts,
                       (select count(*) from assignments a
                        where a.teacher_id=? and a.class_id=c.id and a.status='PUBLISHED'
                          and a.deadline<current_timestamp
                          and not exists (select 1 from assignment_submissions sub
                                          where sub.assignment_id=a.id and sub.student_id=u.id
                                            and sub.submitted_at is not null)) missing_assignments,
                       (select avg(g.score) from grades g
                        where g.student_id=u.id and (?='' or g.semester_id=?)
                          and (c.homeroom_teacher_id=? or exists (
                              select 1 from teaching_assignments ta
                              where ta.teacher_id=? and ta.class_id=c.id and ta.subject_id=g.subject_id
                                and (?='' or ta.semester_id=?)
                                and (ta.status is null or ta.status='ACTIVE')
                          ))) subject_average
                from users u join classes c on c.id=u.class_id
                where u.role='STUDENT' and u.status='ACTIVE'
                  and (?='' or c.academic_year_id=?)
                  and (c.homeroom_teacher_id=? or exists (
                      select 1 from teaching_assignments ta
                      where ta.teacher_id=? and ta.class_id=c.id
                        and (?='' or ta.semester_id=?)
                        and (ta.status is null or ta.status='ACTIVE')
                  ))
                order by c.code, u.full_name
                """, (rs, rowNumber) -> attentionStudent(rs),
                attendanceFrom, teacherId,
                semesterId, semesterId, teacherId, teacherId, semesterId, semesterId,
                academicYearId, academicYearId, teacherId, teacherId, semesterId, semesterId).stream()
                .filter(item -> item.attendanceAlerts() > 0 || item.missingAssignments() > 0
                        || (item.subjectAverage() != null && item.subjectAverage() < 5.0))
                .sorted(Comparator
                        .comparing((DashboardDtos.AttentionStudent item) -> severityRank(item.severity()))
                        .thenComparing(DashboardDtos.AttentionStudent::classCode)
                        .thenComparing(DashboardDtos.AttentionStudent::studentName))
                .limit(8)
                .toList();
        return new DashboardDtos.TeacherOverview(
                !homeroomClassCodes.isEmpty(), homeroomClassCodes, todayLessons, attentionStudents
        );
    }

    private DashboardDtos.AttentionStudent attentionStudent(ResultSet rs) throws SQLException {
        int attendanceAlerts = rs.getInt("attendance_alerts");
        int missingAssignments = rs.getInt("missing_assignments");
        Double average = nullableDouble(rs, "subject_average");
        List<String> reasons = new ArrayList<>();
        if (attendanceAlerts > 0) reasons.add(attendanceAlerts + " lượt vắng không phép/đi muộn trong 30 ngày");
        if (missingAssignments > 0) reasons.add(missingAssignments + " bài quá hạn chưa nộp");
        if (average != null && average < 5.0) reasons.add("điểm trung bình " + String.format("%.1f", average));
        String severity = attendanceAlerts >= 3 || missingAssignments >= 2 || (average != null && average < 4.0)
                ? "CRITICAL" : "WARNING";
        return new DashboardDtos.AttentionStudent(
                rs.getString("student_id"), safe(rs.getString("student_code")), rs.getString("student_name"),
                rs.getString("class_id"), rs.getString("class_code"), attendanceAlerts, missingAssignments,
                average, severity, String.join(" · ", reasons)
        );
    }

    private int severityRank(String severity) {
        return "CRITICAL".equals(severity) ? 0 : 1;
    }

    private String lessonStatus(String startTime, String endTime) {
        LocalTime now = LocalTime.now();
        LocalTime start = parseTime(startTime);
        LocalTime end = parseTime(endTime);
        if (start == null || end == null) return "UPCOMING";
        if (now.isBefore(start)) return "UPCOMING";
        if (now.isAfter(end)) return "COMPLETED";
        return "IN_PROGRESS";
    }

    private LocalTime parseTime(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return LocalTime.parse(value.length() >= 5 ? value.substring(0, 5) : value);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private Double nullableDouble(ResultSet rs, String column) throws SQLException {
        double value = rs.getDouble(column);
        return rs.wasNull() ? null : value;
    }

    private String safe(String value) {
        return value == null ? "" : value;
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
        return new DashboardDtos.Response(metrics, charts);
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
                select count(*) from invoices i where i.status in ('PENDING', 'PARTIAL', 'OVERDUE')
                  and (i.parent_id = ? or i.student_id in (select student_id from parent_student where parent_id = ?))
                """, parentId, parentId) : number("""
                select count(*) from invoices i where i.status in ('PENDING', 'PARTIAL', 'OVERDUE')
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

    private int integer(String sql, Object... args) {
        return (int) Math.round(number(sql, args));
    }

    private String text(String sql, Object... args) {
        List<String> values = jdbc.query(sql, (rs, rowNumber) -> rs.getString(1), args);
        return values.isEmpty() || values.get(0) == null ? "" : values.get(0);
    }

    private void addWorkItem(List<DashboardDtos.WorkItem> items, int value, String key, String title,
                             String detail, String unit, String severity, String pageCode) {
        if (value > 0) items.add(new DashboardDtos.WorkItem(key, title, detail, value, unit, severity, pageCode));
    }

    private void addHealthyWorkItem(List<DashboardDtos.WorkItem> items, String pageCode) {
        if (items.isEmpty()) {
            items.add(new DashboardDtos.WorkItem("healthy", "Không có công việc tồn đọng",
                    "Các nghiệp vụ trong phạm vi phụ trách đang ở trạng thái ổn định.",
                    0, "", "SUCCESS", pageCode));
        }
    }

    private DashboardDtos.RoleOverview roleOverview(String role,
                                                     List<DashboardDtos.CalendarItem> calendarItems,
                                                     List<DashboardDtos.WorkItem> workItems) {
        return new DashboardDtos.RoleOverview(
                role,
                text("""
                        select code from academic_years
                        order by case when status='ACTIVE' then 0 else 1 end, start_date desc limit 1
                        """),
                text("""
                        select status from academic_years
                        order by case when status='ACTIVE' then 0 else 1 end, start_date desc limit 1
                        """),
                text("""
                        select name from semesters
                        order by case when status='ACTIVE' then 0 else 1 end, start_date desc limit 1
                        """),
                text("""
                        select status from semesters
                        order by case when status='ACTIVE' then 0 else 1 end, start_date desc limit 1
                        """),
                Instant.now().toString(), List.copyOf(calendarItems), List.copyOf(workItems)
        );
    }

    private String activeSemesterId() {
        return text("""
                select id from semesters
                order by case when status='ACTIVE' then 0 else 1 end, start_date desc limit 1
                """);
    }

    private String activeAcademicYearId() {
        return text("""
                select id from academic_years
                order by case when status='ACTIVE' then 0 else 1 end, start_date desc limit 1
                """);
    }

    private List<DashboardDtos.CalendarItem> academicCalendarItems() {
        LocalDate from = LocalDate.now().withDayOfMonth(1);
        LocalDate to = from.plusMonths(4).minusDays(1);
        List<DashboardDtos.CalendarItem> items = new ArrayList<>();
        items.addAll(calendarRows("""
                select id, start_date event_date, name title, code detail
                from exam_periods where start_date between ? and ? and status in ('DRAFT','CONFIRMED')
                """, "EXAM", "E3", from, to));
        items.addAll(calendarRows("""
                select id, start_date event_date, name title, status detail
                from semesters where start_date between ? and ?
                """, "SEMESTER", "E1", from, to));
        items.addAll(calendarRows("""
                select id, date event_date, name title, description detail
                from school_holidays where date between ? and ?
                """, "HOLIDAY", "E1", from, to));
        return sortedCalendarItems(items, 30);
    }

    private List<DashboardDtos.CalendarItem> accountantCalendarItems() {
        LocalDate from = LocalDate.now().withDayOfMonth(1);
        LocalDate to = from.plusMonths(4).minusDays(1);
        return sortedCalendarItems(calendarRows("""
                select id, due_date event_date, name title, code detail
                from fee_periods where due_date between ? and ? and status <> 'DRAFT'
                """, "FEE", "F1", from, to), 30);
    }

    private List<DashboardDtos.CalendarItem> teacherCalendarItems(String teacherId) {
        LocalDate today = LocalDate.now();
        LocalDate to = today.plusDays(35);
        String semesterId = activeSemesterId();
        List<DashboardDtos.CalendarItem> items = new ArrayList<>();
        if (!semesterId.isBlank()) jdbc.query("""
                select t.id, t.day_of_week, t.subject_name, c.code class_code,
                       t.start_time, t.end_time, t.room_code
                from timetable_slots t left join classes c on c.id=t.class_id
                where t.teacher_id=? and t.semester_id=?
                """, rs -> {
            DayOfWeek day = parseDayOfWeek(rs.getString("day_of_week"));
            if (day == null) return;
            LocalDate date = today.with(TemporalAdjusters.nextOrSame(day));
            while (!date.isAfter(to)) {
                String classCode = rs.getString("class_code") == null ? "Lớp học" : rs.getString("class_code");
                String room = rs.getString("room_code") == null ? "Chưa có phòng" : "Phòng " + rs.getString("room_code");
                String time = rs.getString("start_time") == null ? "" : rs.getString("start_time");
                if (rs.getString("end_time") != null) time += "–" + rs.getString("end_time");
                items.add(new DashboardDtos.CalendarItem(
                        "lesson-" + rs.getString("id") + "-" + date,
                        date.toString(), rs.getString("subject_name") + " · " + classCode,
                        "LESSON", time + (time.isBlank() ? "" : " · ") + room, "B2"
                ));
                date = date.plusWeeks(1);
            }
        }, teacherId, semesterId);
        items.addAll(calendarRows("""
                select id, cast(deadline as date) event_date, title,
                       coalesce(subject_name, 'Bài tập') detail
                from assignments where teacher_id=? and deadline between ? and ?
                and status='PUBLISHED'
                """, "ASSIGNMENT", "B5", teacherId, today, to));
        return sortedCalendarItems(items, 45);
    }

    private List<DashboardDtos.CalendarItem> sortedCalendarItems(List<DashboardDtos.CalendarItem> items, int limit) {
        return items.stream()
                .sorted(Comparator.comparing(DashboardDtos.CalendarItem::date)
                        .thenComparing(DashboardDtos.CalendarItem::title))
                .limit(limit)
                .toList();
    }

    private String[] dayAliases(DayOfWeek day) {
        return switch (day) {
            case MONDAY -> new String[]{"MON", "MONDAY"};
            case TUESDAY -> new String[]{"TUE", "TUESDAY"};
            case WEDNESDAY -> new String[]{"WED", "WEDNESDAY"};
            case THURSDAY -> new String[]{"THU", "THURSDAY"};
            case FRIDAY -> new String[]{"FRI", "FRIDAY"};
            case SATURDAY -> new String[]{"SAT", "SATURDAY"};
            case SUNDAY -> new String[]{"SUN", "SUNDAY"};
        };
    }

    private DayOfWeek parseDayOfWeek(String value) {
        if (value == null) return null;
        String normalized = value.trim().toUpperCase();
        return switch (normalized) {
            case "MON", "MONDAY" -> DayOfWeek.MONDAY;
            case "TUE", "TUESDAY" -> DayOfWeek.TUESDAY;
            case "WED", "WEDNESDAY" -> DayOfWeek.WEDNESDAY;
            case "THU", "THURSDAY" -> DayOfWeek.THURSDAY;
            case "FRI", "FRIDAY" -> DayOfWeek.FRIDAY;
            case "SAT", "SATURDAY" -> DayOfWeek.SATURDAY;
            case "SUN", "SUNDAY" -> DayOfWeek.SUNDAY;
            default -> null;
        };
    }

    private List<DashboardDtos.CalendarItem> adminCalendarItems() {
        LocalDate from = LocalDate.now().withDayOfMonth(1);
        LocalDate to = from.plusMonths(4).minusDays(1);
        List<DashboardDtos.CalendarItem> items = new ArrayList<>();
        items.addAll(calendarRows("""
                select id, start_date event_date, name title, code detail
                from exam_periods where start_date between ? and ? and status in ('DRAFT','CONFIRMED')
                """, "EXAM", "A8", from, to));
        items.addAll(calendarRows("""
                select id, due_date event_date, name title, code detail
                from fee_periods where due_date between ? and ? and status <> 'DRAFT'
                """, "FEE", "A8", from, to));
        items.addAll(calendarRows("""
                select id, start_date event_date, name title, status detail
                from semesters where start_date between ? and ?
                """, "SEMESTER", "A8", from, to));
        items.addAll(calendarRows("""
                select id, date event_date, name title, description detail
                from school_holidays where date between ? and ?
                """, "HOLIDAY", "A9", from, to));
        return items.stream()
                .sorted(Comparator.comparing(DashboardDtos.CalendarItem::date)
                        .thenComparing(DashboardDtos.CalendarItem::title))
                .limit(20)
                .toList();
    }

    private List<DashboardDtos.CalendarItem> calendarRows(String sql, String type, String pageCode, Object... args) {
        return jdbc.query(sql, (rs, rowNumber) -> new DashboardDtos.CalendarItem(
                type.toLowerCase() + "-" + rs.getString("id"),
                rs.getDate("event_date").toLocalDate().toString(),
                rs.getString("title"), type,
                rs.getString("detail") == null ? "" : rs.getString("detail"),
                pageCode
        ), args);
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
