package com.sse.app.academic.timetable;

import com.sse.app.academic.structure.StructureService;
import com.sse.app.academic.timetable.TimetableDtos.TimetableVersion;
import com.sse.app.academic.timetable.TimetableDtos.TimetableVersionSlot;
import com.sse.app.academic.timetable.TimetableDtos.TimetablePublishResult;
import com.sse.app.academic.timetable.TimetableDtos.TimetablePublicationStatus;
import com.sse.app.audit.AuditService;
import com.sse.app.common.ApiException;
import com.sse.app.common.Ids;
import com.sse.app.identity.User;
import com.sse.app.identity.UserService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Vòng đời phiên bản TKB: snapshot nháp, kiểm tra, phát hành và khôi phục. */
@Service
public class TimetableVersionService {
    private final JdbcTemplate jdbc;
    private final StructureService structure;
    private final TimetableBusinessRuleService businessRules;
    private final TimetablePublicationNotificationService publicationNotifications;
    private final UserService users;
    private final AuditService audit;

    public TimetableVersionService(JdbcTemplate jdbc, StructureService structure,
                                   TimetableBusinessRuleService businessRules,
                                   TimetablePublicationNotificationService publicationNotifications,
                                   UserService users, AuditService audit) {
        this.jdbc = jdbc;
        this.structure = structure;
        this.businessRules = businessRules;
        this.publicationNotifications = publicationNotifications;
        this.users = users;
        this.audit = audit;
    }

    public List<TimetableVersion> list(String semesterId) {
        structure.assertSemesterExists(semesterId);
        return jdbc.query("""
                select * from timetable_plans where semester_id=?
                order by version_no desc, created_at desc
                """, (rs, row) -> version(rs), semesterId);
    }

    public List<TimetableVersionSlot> slots(String planId) {
        require(planId);
        return jdbc.query("""
                select * from timetable_plan_slots where plan_id=?
                order by class_code, case day_of_week
                  when 'MON' then 1 when 'TUE' then 2 when 'WED' then 3
                  when 'THU' then 4 when 'FRI' then 5 when 'SAT' then 6 else 7 end,
                  period_no
                """, (rs, row) -> slot(rs), planId);
    }

    /** Chụp lịch làm việc hiện tại thành một bản nháp bất biến. */
    @Transactional
    public TimetableVersion snapshot(String semesterId, String name, String actorId) {
        structure.assertSemesterWritable(semesterId);
        int slotCount = count("select count(*) from timetable_slots where semester_id=?", semesterId);
        if (slotCount == 0) throw ApiException.badRequest("Học kỳ chưa có tiết học để tạo phiên bản");
        int versionNo = count("select coalesce(max(version_no),0)+1 from timetable_plans where semester_id=?", semesterId);
        String id = Ids.gen("ttp");
        Instant now = Instant.now();
        jdbc.update("""
                insert into timetable_plans
                (id,semester_id,name,status,version_no,option_no,quality_score,progress_percent,
                 total_assignments,total_periods,scheduled_periods,unscheduled_periods,conflict_summary,
                 configuration_json,created_by,created_at,updated_at)
                values (?,?,?,'DRAFT',?,1,0,100,0,?,?,0,null,'{}',?,?,?)
                """, id, semesterId, cleanName(name), versionNo, slotCount, slotCount, actorId, sqlTime(now), sqlTime(now));
        List<TimetableVersionSlot> current = jdbc.query("""
                select t.id,null as plan_id,t.class_id,c.code as class_code,c.study_shift,
                       t.subject_id,t.subject_name,t.teacher_id,t.teacher_name,t.room_code,
                       t.day_of_week,t.period_no,t.start_time,t.end_time,t.locked
                from timetable_slots t join classes c on c.id=t.class_id
                where t.semester_id=?
                """, (rs, row) -> slot(rs), semesterId);
        current.forEach(item -> insertPlanSlot(id, item));
        Validation validation = validate(id);
        jdbc.update("""
                update timetable_plans set status=?,quality_score=?,conflict_summary=?,updated_at=? where id=?
                """, validation.valid() ? "VALIDATED" : "DRAFT", validation.valid() ? 100 : 0,
                validation.summary(), sqlTime(Instant.now()), id);
        return require(id);
    }

    /** Tạo bản nháp mới từ một phiên bản lịch sử; không ghi đè lịch đã phát hành. */
    @Transactional
    public TimetableVersion restore(String sourcePlanId, String name, String actorId) {
        TimetableVersion source = require(sourcePlanId);
        structure.assertSemesterWritable(source.semesterId());
        int versionNo = count("select coalesce(max(version_no),0)+1 from timetable_plans where semester_id=?",
                source.semesterId());
        String id = Ids.gen("ttp");
        Instant now = Instant.now();
        List<TimetableVersionSlot> sourceSlots = slots(sourcePlanId);
        jdbc.update("""
                insert into timetable_plans
                (id,semester_id,name,status,version_no,option_no,quality_score,progress_percent,
                 total_assignments,total_periods,scheduled_periods,unscheduled_periods,conflict_summary,
                 configuration_json,source_plan_id,created_by,created_at,updated_at)
                values (?,?,?,'DRAFT',?,1,0,100,0,?,?,0,null,'{}',?,?,?,?)
                """, id, source.semesterId(), cleanName(name), versionNo, sourceSlots.size(), sourceSlots.size(),
                sourcePlanId, actorId, sqlTime(now), sqlTime(now));
        sourceSlots.forEach(item -> insertPlanSlot(id, item));
        Validation validation = validate(id);
        jdbc.update("update timetable_plans set status=?,quality_score=?,conflict_summary=?,updated_at=? where id=?",
                validation.valid() ? "VALIDATED" : "DRAFT", validation.valid() ? 100 : 0,
                validation.summary(), sqlTime(Instant.now()), id);
        return require(id);
    }

    /** Phát hành nguyên tử: chỉ thay lịch trực tiếp khi toàn bộ phiên bản hợp lệ. */
    @Transactional
    public TimetablePublishResult publish(String planId, String versionName, String reason, String actorId) {
        TimetableVersion plan = require(planId);
        if ("PUBLISHED".equals(plan.status())) {
            return new TimetablePublishResult(plan, publicationNotifications.requireStatusByPlan(planId));
        }
        if (!List.of("DRAFT", "VALIDATED").contains(plan.status())) {
            throw ApiException.conflict("Chỉ có thể phát hành phiên bản nháp hoặc đã kiểm tra");
        }
        structure.assertSemesterWritable(plan.semesterId());
        Validation validation = validateForPublication(planId);
        if (!validation.valid()) throw ApiException.conflict(validation.summary());
        List<TimetableVersionSlot> versionSlots = slots(planId);
        if (versionSlots.isEmpty()) throw ApiException.badRequest("Phiên bản không có tiết học");

        String cleanedVersionName = cleanName(versionName);
        jdbc.update("update timetable_plans set name=?,updated_at=? where id=?",
                cleanedVersionName, sqlTime(Instant.now()), planId);
        jdbc.update("update timetable_plans set status='SUPERSEDED',updated_at=? where semester_id=? and status='PUBLISHED'",
                sqlTime(Instant.now()), plan.semesterId());
        jdbc.update("delete from timetable_slots where semester_id=?", plan.semesterId());
        for (TimetableVersionSlot item : versionSlots) {
            jdbc.update("""
                    insert into timetable_slots
                    (id,class_id,subject_id,subject_name,teacher_id,teacher_name,room_code,day_of_week,
                     period_no,start_time,end_time,semester_id,published_plan_id,locked)
                    values (?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                    """, Ids.gen("tt"), item.classId(), item.subjectId(), item.subjectName(), item.teacherId(),
                    item.teacherName(), item.roomCode(), item.dayOfWeek(), item.periodNo(), item.startTime(),
                    item.endTime(), plan.semesterId(), planId, Boolean.TRUE.equals(item.locked()));
        }
        Instant now = Instant.now();
        jdbc.update("""
                update timetable_plans set status='PUBLISHED',quality_score=100,conflict_summary=null,
                    published_by=?,published_at=?,updated_at=? where id=?
                """, actorId, sqlTime(now), sqlTime(now), planId);
        TimetablePublicationStatus publication = publicationNotifications.enqueue(planId, reason, actorId);
        User actor = users.getById(actorId);
        audit.record(actorId, actor.getFullName(), actor.getRole(),
                publication.eventType().equals("FIRST_PUBLICATION")
                        ? "TIMETABLE_PUBLISHED" : "TIMETABLE_REPLACED",
                "academic", "timetable_plan", planId,
                "Phiên bản " + cleanedVersionName + "; học kỳ " + plan.semesterId()
                        + "; phiên bản cũ " + (publication.previousPlanId() == null ? "không có" : publication.previousPlanId())
                        + "; lý do: " + reason.trim() + "; " + publication.totalRecipientCount() + " lượt nhận");
        return new TimetablePublishResult(require(planId), publication);
    }

    @Transactional
    public void deleteDraft(String planId) {
        TimetableVersion plan = require(planId);
        if (!List.of("DRAFT", "VALIDATED").contains(plan.status())) {
            throw ApiException.conflict("Không thể xóa phiên bản đã phát hành hoặc thuộc lịch sử");
        }
        jdbc.update("delete from timetable_plans where id=?", planId);
    }

    private Validation validate(String planId) {
        TimetableVersion plan = require(planId);
        TimetableRulePolicy.Validation validation = businessRules.validate(slots(planId).stream()
                .map(item -> new TimetableRulePolicy.SlotView(item.id(), item.classId(), item.subjectId(),
                        item.teacherId(), item.roomCode(), item.dayOfWeek(), item.periodNo(),
                        item.startTime(), item.endTime(), plan.semesterId()))
                .toList());
        return new Validation(validation.valid(), validation.summary());
    }

    private Validation validateForPublication(String planId) {
        Validation base = validate(planId);
        if (!base.valid()) return base;
        TimetableVersion plan = require(planId);
        List<TimetableVersionSlot> planSlots = slots(planId);
        List<String> messages = new ArrayList<>();
        Set<String> classIds = new HashSet<>();
        planSlots.forEach(slot -> classIds.add(slot.classId()));
        for (String classId : classIds) {
            int expected = count("""
                    select coalesce(sum(r.weekly_periods),0)
                    from curriculum_requirements r join classes c on c.grade_level=r.grade_level
                    where r.semester_id=? and c.id=?
                    """, plan.semesterId(), classId);
            if (expected == 0) continue; // Dữ liệu lịch sử trước khi có định mức chương trình.
            String classCode = planSlots.stream().filter(slot -> classId.equals(slot.classId()))
                    .map(TimetableVersionSlot::classCode).findFirst().orElse(classId);
            if (expected != TimetableRulePolicy.PERIODS_PER_WEEK) {
                messages.add("Lớp " + classCode + " có định mức " + expected
                        + " tiết/tuần; yêu cầu đúng 25 tiết");
                continue;
            }
            List<TimetableVersionSlot> classSlots = planSlots.stream()
                    .filter(slot -> classId.equals(slot.classId())).toList();
            if (classSlots.size() != TimetableRulePolicy.PERIODS_PER_WEEK) {
                messages.add("Lớp " + classCode + " chưa đủ 25 tiết/tuần");
                continue;
            }
            for (String day : TimetableRulePolicy.OPERATING_DAYS) {
                Set<Integer> periods = new HashSet<>(classSlots.stream()
                        .filter(slot -> day.equals(slot.dayOfWeek()))
                        .map(TimetableVersionSlot::periodNo).toList());
                if (periods.size() != TimetableRulePolicy.PERIODS_PER_DAY
                        || !periods.containsAll(List.of(1, 2, 3, 4, 5))) {
                    messages.add("Lớp " + classCode + " chưa đủ 5 tiết liền mạch trong ngày " + day);
                }
            }
        }
        return new Validation(messages.isEmpty(), messages.isEmpty() ? null
                : "Thời khóa biểu chưa đủ điều kiện phát hành: " + String.join("; ", messages));
    }

    /** Lưu phương án tự động trực tiếp vào vùng bản nháp, không thay đổi lịch người dùng đang xem. */
    @Transactional
    public TimetableVersion draftFromSlots(String semesterId, String name,
                                           List<TimetableSlot> proposedSlots, String actorId) {
        return draftFromSlots(semesterId, name, proposedSlots, actorId, 100, "BALANCED");
    }

    @Transactional
    public TimetableVersion draftFromSlots(String semesterId, String name,
                                           List<TimetableSlot> proposedSlots, String actorId,
                                           int qualityScore, String strategy) {
        structure.assertSemesterWritable(semesterId);
        if (proposedSlots.isEmpty()) throw ApiException.badRequest("Phương án không có tiết học");
        TimetableRulePolicy.Validation validation = businessRules.validate(proposedSlots.stream()
                .map(item -> new TimetableRulePolicy.SlotView(item.getId(), item.getClassId(), item.getSubjectId(),
                        item.getTeacherId(), item.getRoomCode(), item.getDayOfWeek(), item.getPeriodNo(),
                        item.getStartTime(), item.getEndTime(), semesterId)).toList());
        if (!validation.valid()) throw ApiException.conflict(validation.summary());
        int versionNo = count("select coalesce(max(version_no),0)+1 from timetable_plans where semester_id=?", semesterId);
        String id = Ids.gen("ttp");
        Instant now = Instant.now();
        jdbc.update("""
                insert into timetable_plans
                (id,semester_id,name,status,version_no,option_no,quality_score,progress_percent,
                 total_assignments,total_periods,scheduled_periods,unscheduled_periods,conflict_summary,
                 configuration_json,created_by,created_at,updated_at)
                values (?,?,?,'VALIDATED',?,1,?,100,0,?,?,0,null,?,?,?,?)
                """, id, semesterId, cleanName(name) + " · v" + versionNo, versionNo,
                Math.max(0, Math.min(100, qualityScore)), proposedSlots.size(), proposedSlots.size(),
                "{\"strategy\":\"" + safeStrategy(strategy) + "\"}",
                actorId, sqlTime(now), sqlTime(now));
        for (TimetableSlot slot : proposedSlots) {
            var schoolClass = structure.getClass(slot.getClassId());
            insertPlanSlot(id, new TimetableVersionSlot(slot.getId(), id, slot.getClassId(),
                    schoolClass.getCode(), schoolClass.getStudyShift(), slot.getSubjectId(), slot.getSubjectName(),
                    slot.getTeacherId(), slot.getTeacherName(), slot.getRoomCode(), slot.getDayOfWeek(),
                    slot.getPeriodNo(), slot.getStartTime(), slot.getEndTime(), slot.isLocked()));
        }
        return require(id);
    }

    private String safeStrategy(String value) {
        String normalized = value == null ? "BALANCED" : value.trim().toUpperCase();
        return List.of("BALANCED", "TEACHER_COMFORT", "EARLY_WEEK").contains(normalized)
                ? normalized : "BALANCED";
    }

    private TimetableVersion require(String id) {
        List<TimetableVersion> rows = jdbc.query("select * from timetable_plans where id=?",
                (rs, row) -> version(rs), id);
        if (rows.isEmpty()) throw ApiException.notFound("Phiên bản thời khóa biểu");
        return rows.get(0);
    }

    private void insertPlanSlot(String planId, TimetableVersionSlot item) {
        jdbc.update("""
                insert into timetable_plan_slots
                (id,plan_id,class_id,class_code,study_shift,subject_id,subject_name,teacher_id,teacher_name,
                 room_code,day_of_week,period_no,start_time,end_time,locked,created_at)
                values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, Ids.gen("ttps"), planId, item.classId(), item.classCode(), item.studyShift(), item.subjectId(),
                item.subjectName(), item.teacherId(), item.teacherName(), item.roomCode(), item.dayOfWeek(),
                item.periodNo(), item.startTime(), item.endTime(), Boolean.TRUE.equals(item.locked()), sqlTime(Instant.now()));
    }

    private int count(String sql, Object... args) {
        Integer value = jdbc.queryForObject(sql, Integer.class, args);
        return value == null ? 0 : value;
    }

    private String cleanName(String name) {
        String value = name == null ? "" : name.trim();
        if (value.isEmpty()) throw ApiException.badRequest("Tên phiên bản không được để trống");
        return value.length() > 255 ? value.substring(0, 255) : value;
    }

    private TimetableVersion version(ResultSet rs) throws SQLException {
        return new TimetableVersion(rs.getString("id"), rs.getString("semester_id"), rs.getString("name"),
                rs.getString("status"), rs.getObject("version_no", Integer.class),
                rs.getObject("quality_score", Integer.class), rs.getObject("total_periods", Integer.class),
                rs.getObject("scheduled_periods", Integer.class), rs.getObject("unscheduled_periods", Integer.class),
                rs.getString("conflict_summary"), rs.getString("source_plan_id"), rs.getString("created_by"),
                instant(rs, "created_at"), instant(rs, "updated_at"), rs.getString("published_by"),
                instant(rs, "published_at"));
    }

    private TimetableVersionSlot slot(ResultSet rs) throws SQLException {
        return new TimetableVersionSlot(rs.getString("id"), rs.getString("plan_id"), rs.getString("class_id"),
                rs.getString("class_code"), rs.getString("study_shift"), rs.getString("subject_id"),
                rs.getString("subject_name"), rs.getString("teacher_id"), rs.getString("teacher_name"),
                rs.getString("room_code"), rs.getString("day_of_week"), rs.getObject("period_no", Integer.class),
                rs.getString("start_time"), rs.getString("end_time"), rs.getObject("locked", Boolean.class));
    }

    private Instant instant(ResultSet rs, String column) throws SQLException {
        var timestamp = rs.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }

    private Timestamp sqlTime(Instant value) {
        return Timestamp.from(value);
    }

    private record Validation(boolean valid, String summary) {}
}
