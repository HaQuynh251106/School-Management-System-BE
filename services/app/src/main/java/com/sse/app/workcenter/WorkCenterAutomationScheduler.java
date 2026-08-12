package com.sse.app.workcenter;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.*;

import static com.sse.app.workcenter.WorkCenterDtos.AutoTaskCommand;

/** Đồng bộ công việc từ dữ liệu nghiệp vụ thật. Mỗi quy tắc độc lập để một module lỗi không chặn module khác. */
@Component @RequiredArgsConstructor @Slf4j
public class WorkCenterAutomationScheduler {
    private final JdbcTemplate jdbc;
    private final WorkCenterService workCenter;

    @EventListener(ApplicationReadyEvent.class)
    public void reconcileOnStartup() {
        reconcile();
    }

    @Scheduled(cron = "0 */15 * * * *")
    public void reconcile() {
        safely(this::pendingPlacement);
        safely(this::missingHomeroom);
        safely(this::timetableProgress);
        safely(this::examPreparation);
        safely(this::financeDebt);
        safely(this::draftFeePeriods);
        safely(this::teacherGrading);
    }

    private void pendingPlacement() {
        String yearId = activeYearId();
        long count = number("""
                select count(*) from users
                where role='STUDENT' and status='ACTIVE'
                  and coalesce(student_status, 'PENDING_PLACEMENT')='PENDING_PLACEMENT'
                """);
        String key = "ACADEMIC:PENDING_PLACEMENT:" + yearId;
        syncSingle("ACADEMIC_PENDING_PLACEMENT", key, count > 0,
                command(key, "ACADEMIC_PENDING_PLACEMENT", yearId,
                        "Phân lớp cho " + count + " học sinh mới", "Hồ sơ đã sẵn sàng nhưng chưa thuộc lớp trong năm học hiện hành.",
                        "ACADEMIC", "HIGH", "ACADEMIC_STAFF", null, LocalDate.now().plusDays(3)));
    }

    private void missingHomeroom() {
        String yearId = activeYearId();
        long count = number("""
                select count(*) from classes where academic_year_id=?
                  and (homeroom_teacher_id is null or trim(homeroom_teacher_id)='')
                """, yearId);
        String key = "ACADEMIC:MISSING_HOMEROOM:" + yearId;
        syncSingle("ACADEMIC_MISSING_HOMEROOM", key, count > 0,
                command(key, "ACADEMIC_MISSING_HOMEROOM", yearId,
                        count + " lớp chưa có giáo viên chủ nhiệm", "Hoàn tất phân công GVCN trước khi vận hành năm học.",
                        "ACADEMIC", "HIGH", "ACADEMIC_STAFF", null, LocalDate.now().plusDays(5)));
    }

    private void timetableProgress() {
        String semesterId = activeSemesterId();
        long expected = number("select coalesce(sum(weekly_periods),0) from teaching_assignments where semester_id=? and status='ACTIVE'", semesterId);
        long actual = number("select count(*) from timetable_slots where semester_id=?", semesterId);
        String key = "TIMETABLE:INCOMPLETE:" + semesterId;
        syncSingle("TIMETABLE_INCOMPLETE", key, actual < expected,
                command(key, "TIMETABLE_INCOMPLETE", semesterId,
                        "Hoàn thiện thời khóa biểu học kỳ", "Đã xếp " + actual + "/" + expected + " tiết; cần kiểm tra cảnh báo và phát hành phiên bản chính thức.",
                        "TIMETABLE", "URGENT", "ACADEMIC_STAFF", null, LocalDate.now().plusDays(3)));
    }

    private void examPreparation() {
        String semesterId = activeSemesterId();
        long count = number("select count(*) from exam_periods where semester_id=? and status in ('DRAFT','CONFIRMED')", semesterId);
        String key = "EXAM:PREPARATION:" + semesterId;
        syncSingle("EXAM_PREPARATION", key, count > 0,
                command(key, "EXAM_PREPARATION", semesterId,
                        "Hoàn thiện " + count + " kỳ thi đang chuẩn bị", "Kiểm tra lịch, phòng, thí sinh, giám thị và giáo viên chấm thi.",
                        "EXAM", "HIGH", "ACADEMIC_STAFF", null, LocalDate.now().plusDays(5)));
    }

    private void financeDebt() {
        long count = number("select count(*) from invoices where status in ('PENDING','PARTIAL','OVERDUE') and due_date < current_date");
        String key = "FINANCE:OVERDUE_INVOICES";
        syncSingle("FINANCE_OVERDUE", key, count > 0,
                command(key, "FINANCE_OVERDUE", "OVERDUE",
                        "Đối soát " + count + " hóa đơn quá hạn", "Lọc theo khối/lớp, kiểm tra giao dịch và phối hợp GVCN nhắc phụ huynh.",
                        "FINANCE", "URGENT", "ACCOUNTANT", null, LocalDate.now().plusDays(2)));
    }

    private void draftFeePeriods() {
        long count = number("select count(*) from fee_periods where status='DRAFT'");
        String key = "FINANCE:DRAFT_FEE_PERIODS";
        syncSingle("FINANCE_DRAFT_FEE", key, count > 0,
                command(key, "FINANCE_DRAFT_FEE", "DRAFT",
                        "Rà soát " + count + " đợt thu bản nháp", "Kiểm tra phạm vi, hạn nộp và phát hành các đợt thu hợp lệ.",
                        "FINANCE", "NORMAL", "ACCOUNTANT", null, LocalDate.now().plusDays(5)));
    }

    private void teacherGrading() {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select a.teacher_id, count(*) pending
                from assignment_submissions s join assignments a on a.id=s.assignment_id
                where s.submitted_at is not null and s.graded_at is null
                group by a.teacher_id
                """);
        Set<String> active = new LinkedHashSet<>();
        for (Map<String, Object> row : rows) {
            String teacherId = Objects.toString(row.get("teacher_id"), "");
            long count = ((Number) row.get("pending")).longValue();
            String key = "TEACHER:GRADING:" + teacherId;
            active.add(key);
            workCenter.upsertAutoTask(command(key, "TEACHER_GRADING", teacherId,
                    "Chấm " + count + " bài học sinh đã nộp", "Hoàn tất chấm bài và phản hồi trước hạn.",
                    "TEACHING", count >= 20 ? "HIGH" : "NORMAL", "TEACHER", teacherId, LocalDate.now().plusDays(3)));
        }
        workCenter.closeAutoTasksNotIn("TEACHER_GRADING", active);
    }

    private AutoTaskCommand command(String key, String type, String sourceId, String title, String description,
                                    String module, String priority, String role, String assignee, LocalDate due) {
        return new AutoTaskCommand(key, type, sourceId, title, description, module, priority, role, assignee, due, false);
    }

    private void syncSingle(String sourceType, String key, boolean active, AutoTaskCommand command) {
        if (active) workCenter.upsertAutoTask(command);
        workCenter.closeAutoTasksNotIn(sourceType, active ? Set.of(key) : Set.of());
    }

    private String activeYearId() {
        List<String> ids = jdbc.queryForList("select id from academic_years where status='ACTIVE' order by start_date desc limit 1", String.class);
        return ids.isEmpty() ? "NO_ACTIVE_YEAR" : ids.get(0);
    }

    private String activeSemesterId() {
        List<String> ids = jdbc.queryForList("select id from semesters where status='ACTIVE' order by start_date desc limit 1", String.class);
        return ids.isEmpty() ? "NO_ACTIVE_SEMESTER" : ids.get(0);
    }

    private long number(String sql, Object... args) {
        Long value = jdbc.queryForObject(sql, Long.class, args);
        return value == null ? 0 : value;
    }

    private void safely(Runnable rule) {
        try {
            rule.run();
        } catch (Exception exception) {
            log.warn("Không thể đồng bộ một quy tắc công việc tự động; các quy tắc còn lại vẫn tiếp tục", exception);
        }
    }
}
