package com.sse.app.academic.timetable;

import com.sse.app.academic.structure.StructureService;
import com.sse.app.academic.timetable.WorkloadPlanningDtos.AssignmentVersionItemResponse;
import com.sse.app.academic.timetable.WorkloadPlanningDtos.AssignmentVersionResponse;
import com.sse.app.audit.AuditService;
import com.sse.app.common.ApiException;
import com.sse.app.common.Ids;
import com.sse.app.identity.User;
import com.sse.app.identity.UserService;
import com.sse.app.notification.NotificationService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Service
public class TeachingAssignmentVersionService {
    private final JdbcTemplate jdbc;
    private final TeachingAssignmentRepository assignments;
    private final StructureService structure;
    private final UserService users;
    private final AuditService audit;
    private final NotificationService notifications;

    public TeachingAssignmentVersionService(JdbcTemplate jdbc, TeachingAssignmentRepository assignments,
                                            StructureService structure, UserService users, AuditService audit,
                                            NotificationService notifications) {
        this.jdbc = jdbc;
        this.assignments = assignments;
        this.structure = structure;
        this.users = users;
        this.audit = audit;
        this.notifications = notifications;
    }

    public List<AssignmentVersionResponse> list(String semesterId) {
        structure.getSemester(semesterId);
        return jdbc.query("select * from teaching_assignment_plans where semester_id=? order by version_no desc",
                (rs, ignored) -> version(rs), semesterId);
    }

    public List<AssignmentVersionItemResponse> items(String planId) {
        require(planId);
        return jdbc.query("select * from teaching_assignment_plan_items where plan_id=? order by class_code,subject_name",
                (rs, ignored) -> item(rs), planId);
    }

    @Transactional
    public AssignmentVersionResponse publishCurrent(String semesterId, String name,
                                                    List<String> warnings, String actorId) {
        structure.assertSemesterWritable(semesterId);
        List<TeachingAssignment> current = assignments.findBySemesterId(semesterId);
        if (current.isEmpty()) throw ApiException.badRequest("Chưa có phân công để tạo phiên bản");
        Instant now = Instant.now();
        String id = Ids.gen("assignment-plan");
        int versionNo = nextVersion(semesterId);
        jdbc.update("update teaching_assignment_plans set status='SUPERSEDED',updated_at=? where semester_id=? and status='PUBLISHED'",
                Timestamp.from(now), semesterId);
        jdbc.update("insert into teaching_assignment_plans(id,semester_id,name,status,version_no,assignment_count,warning_summary,created_by,created_at,updated_at,published_by,published_at) values (?,?,?,?,?,?,?,?,?,?,?,?)",
                id, semesterId, name, "PUBLISHED", versionNo, current.size(), summary(warnings), actorId,
                Timestamp.from(now), Timestamp.from(now), actorId, Timestamp.from(now));
        current.forEach(row -> insertItem(id, row));
        record(actorId, "TEACHING_ASSIGNMENT_VERSION_PUBLISHED", id,
                "Phát hành phiên bản " + versionNo + " với " + current.size() + " phân công");
        notifyAssignedTeachers(current.stream().map(TeachingAssignment::getTeacherId).toList(), id, versionNo);
        return require(id);
    }

    @Transactional
    public AssignmentVersionResponse restore(String sourceId, String name, String actorId) {
        AssignmentVersionResponse source = require(sourceId);
        structure.assertSemesterWritable(source.semesterId());
        List<AssignmentVersionItemResponse> sourceItems = items(sourceId);
        if (sourceItems.isEmpty()) throw ApiException.badRequest("Phiên bản nguồn không có phân công");
        Instant now = Instant.now();
        String id = Ids.gen("assignment-plan");
        int versionNo = nextVersion(source.semesterId());
        jdbc.update("insert into teaching_assignment_plans(id,semester_id,name,status,version_no,assignment_count,warning_summary,source_plan_id,created_by,created_at,updated_at) values (?,?,?,?,?,?,?,?,?,?,?)",
                id, source.semesterId(), name, "DRAFT", versionNo, sourceItems.size(),
                "Bản nháp khôi phục; chưa thay đổi phân công hiện hành", sourceId, actorId,
                Timestamp.from(now), Timestamp.from(now));
        sourceItems.forEach(row -> jdbc.update("insert into teaching_assignment_plan_items(id,plan_id,class_id,class_code,subject_id,subject_name,teacher_id,teacher_name,weekly_periods) values (?,?,?,?,?,?,?,?,?)",
                Ids.gen("assignment-plan-item"), id, row.classId(), row.classCode(), row.subjectId(),
                row.subjectName(), row.teacherId(), row.teacherName(), row.weeklyPeriods()));
        record(actorId, "TEACHING_ASSIGNMENT_VERSION_RESTORE_DRAFTED", id,
                "Tạo bản nháp khôi phục từ phiên bản " + source.versionNo());
        return require(id);
    }

    @Transactional
    public AssignmentVersionResponse publish(String id, String actorId) {
        AssignmentVersionResponse plan = require(id);
        if (!"DRAFT".equals(plan.status())) throw ApiException.conflict("Chỉ bản nháp mới có thể phát hành");
        structure.assertSemesterWritable(plan.semesterId());
        Integer publishedTimetableCount = jdbc.queryForObject(
                "select count(*) from timetable_plans where semester_id=? and status='PUBLISHED'", Integer.class,
                plan.semesterId());
        if (publishedTimetableCount != null && publishedTimetableCount > 0) {
            throw ApiException.conflict("Học kỳ đã có thời khóa biểu chính thức. Hãy tạo quy trình thay đổi phân công và phát hành lại lịch thay vì khôi phục trực tiếp.");
        }
        List<AssignmentVersionItemResponse> planItems = items(id);
        if (planItems.isEmpty()) throw ApiException.badRequest("Bản nháp không có phân công");
        Instant now = Instant.now();
        assignments.deleteBySemesterId(plan.semesterId());
        assignments.flush();
        assignments.saveAll(planItems.stream().map(row -> TeachingAssignment.builder()
                .id(Ids.gen("ta")).classId(row.classId()).classCode(row.classCode())
                .subjectId(row.subjectId()).subjectName(row.subjectName())
                .teacherId(row.teacherId()).teacherName(row.teacherName())
                .semesterId(plan.semesterId()).weeklyPeriods(row.weeklyPeriods())
                .assignedAt(now).assignedBy(actorId).updatedAt(now).build()).toList());
        jdbc.update("update teaching_assignment_plans set status='SUPERSEDED',updated_at=? where semester_id=? and status='PUBLISHED'",
                Timestamp.from(now), plan.semesterId());
        jdbc.update("update teaching_assignment_plans set status='PUBLISHED',published_by=?,published_at=?,updated_at=? where id=?",
                actorId, Timestamp.from(now), Timestamp.from(now), id);
        record(actorId, "TEACHING_ASSIGNMENT_VERSION_PUBLISHED", id,
                "Phát hành bản khôi phục phiên bản " + plan.versionNo());
        notifyAssignedTeachers(planItems.stream().map(AssignmentVersionItemResponse::teacherId).toList(),
                id, plan.versionNo());
        return require(id);
    }

    private void notifyAssignedTeachers(List<String> teacherIds, String planId, int versionNo) {
        Set<String> recipients = new LinkedHashSet<>(teacherIds);
        recipients.removeIf(id -> id == null || id.isBlank());
        notifications.notifyUsers(recipients.stream().toList(), "TEACHING_ASSIGNMENT", "IMPORTANT",
                "Phân công giảng dạy đã được phát hành",
                "Phân công giảng dạy phiên bản " + versionNo
                        + " đã được phát hành. Vui lòng kiểm tra lớp, môn và thời lượng được phân công.",
                "TEACHING_ASSIGNMENT_PLAN", planId);
    }

    private int nextVersion(String semesterId) {
        Integer value = jdbc.queryForObject("select coalesce(max(version_no),0)+1 from teaching_assignment_plans where semester_id=?",
                Integer.class, semesterId);
        return value == null ? 1 : value;
    }

    private AssignmentVersionResponse require(String id) {
        List<AssignmentVersionResponse> rows = jdbc.query("select * from teaching_assignment_plans where id=?",
                (rs, ignored) -> version(rs), id);
        if (rows.isEmpty()) throw ApiException.notFound("Phiên bản phân công");
        return rows.get(0);
    }

    private void insertItem(String planId, TeachingAssignment row) {
        jdbc.update("insert into teaching_assignment_plan_items(id,plan_id,class_id,class_code,subject_id,subject_name,teacher_id,teacher_name,weekly_periods) values (?,?,?,?,?,?,?,?,?)",
                Ids.gen("assignment-plan-item"), planId, row.getClassId(), row.getClassCode(),
                row.getSubjectId(), row.getSubjectName(), row.getTeacherId(), row.getTeacherName(),
                row.getWeeklyPeriods());
    }

    private void record(String actorId, String action, String entityId, String detail) {
        User actor = users.getById(actorId);
        audit.record(actorId, actor.getFullName(), actor.getRole(), action, "academic",
                "teaching_assignment_plan", entityId, detail);
    }

    private static String summary(List<String> warnings) {
        if (warnings == null || warnings.isEmpty()) return null;
        String value = String.join("; ", warnings);
        return value.length() <= 2000 ? value : value.substring(0, 2000);
    }

    private static AssignmentVersionResponse version(ResultSet rs) throws SQLException {
        return new AssignmentVersionResponse(rs.getString("id"), rs.getString("semester_id"),
                rs.getString("name"), rs.getString("status"), rs.getInt("version_no"),
                rs.getInt("assignment_count"), rs.getString("warning_summary"),
                rs.getString("source_plan_id"), rs.getString("created_by"),
                instant(rs, "created_at"), instant(rs, "updated_at"), rs.getString("published_by"),
                instant(rs, "published_at"));
    }

    private static AssignmentVersionItemResponse item(ResultSet rs) throws SQLException {
        return new AssignmentVersionItemResponse(rs.getString("id"), rs.getString("plan_id"),
                rs.getString("class_id"), rs.getString("class_code"), rs.getString("subject_id"),
                rs.getString("subject_name"), rs.getString("teacher_id"), rs.getString("teacher_name"),
                rs.getInt("weekly_periods"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }
}
