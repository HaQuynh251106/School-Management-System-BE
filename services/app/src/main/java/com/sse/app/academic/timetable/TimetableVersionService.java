package com.sse.app.academic.timetable;

import com.sse.app.academic.structure.StructureService;
import com.sse.app.academic.timetable.TimetableDtos.TimetableVersion;
import com.sse.app.academic.timetable.TimetableDtos.TimetableVersionSlot;
import com.sse.app.common.ApiException;
import com.sse.app.common.Ids;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.context.ApplicationEventPublisher;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;

/** Vòng đời phiên bản TKB: snapshot nháp, kiểm tra, phát hành và khôi phục. */
@Service
public class TimetableVersionService {
    private final JdbcTemplate jdbc;
    private final StructureService structure;
    private final ApplicationEventPublisher events;

    public TimetableVersionService(JdbcTemplate jdbc, StructureService structure,
                                   ApplicationEventPublisher events) {
        this.jdbc = jdbc;
        this.structure = structure;
        this.events = events;
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
        return snapshot(semesterId, name, actorId, List.of());
    }

    @Transactional
    public TimetableVersion snapshot(String semesterId, String name, String actorId,
                                     List<String> sourceEducationPlanIds) {
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
                 configuration_json,source_education_plan_ids,created_by,created_at,updated_at)
                values (?,?,?,'DRAFT',?,1,0,100,0,?,?,0,null,'{}',?,?,?,?)
                """, id, semesterId, cleanName(name), versionNo, slotCount, slotCount,
                csv(sourceEducationPlanIds), actorId, sqlTime(now), sqlTime(now));
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
                 configuration_json,source_plan_id,source_education_plan_ids,created_by,created_at,updated_at)
                values (?,?,?,'DRAFT',?,1,0,100,0,?,?,0,null,'{}',?,?,?,?,?)
                """, id, source.semesterId(), cleanName(name), versionNo, sourceSlots.size(), sourceSlots.size(),
                sourcePlanId, csv(source.sourceEducationPlanIds()), actorId, sqlTime(now), sqlTime(now));
        sourceSlots.forEach(item -> insertPlanSlot(id, item));
        Validation validation = validate(id);
        jdbc.update("update timetable_plans set status=?,quality_score=?,conflict_summary=?,updated_at=? where id=?",
                validation.valid() ? "VALIDATED" : "DRAFT", validation.valid() ? 100 : 0,
                validation.summary(), sqlTime(Instant.now()), id);
        return require(id);
    }

    /** Phát hành nguyên tử: chỉ thay lịch trực tiếp khi toàn bộ phiên bản hợp lệ. */
    @Transactional
    public TimetableVersion publish(String planId, String actorId) {
        // Khóa bản nháp trong suốt giao dịch để hai lần bấm phát hành đồng thời
        // không cùng thay thế lịch đang áp dụng.
        TimetableVersion plan = requireForUpdate(planId);
        if (!List.of("DRAFT", "VALIDATED").contains(plan.status())) {
            throw ApiException.conflict("Chỉ có thể phát hành phiên bản nháp hoặc đã kiểm tra");
        }
        structure.assertSemesterWritable(plan.semesterId());
        Validation validation = validate(planId);
        if (!validation.valid()) throw ApiException.conflict(validation.summary());
        List<TimetableVersionSlot> versionSlots = slots(planId);
        if (versionSlots.isEmpty()) throw ApiException.badRequest("Phiên bản không có tiết học");

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
        TimetableVersion published = require(planId);
        events.publishEvent(new TimetablePublishedEvent(
                published.id(), published.semesterId(), published.versionNo()));
        return published;
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
        int conflicts = count("""
                select count(*) from (
                  select class_id,day_of_week,start_time,end_time from timetable_plan_slots where plan_id=?
                  group by class_id,day_of_week,start_time,end_time having count(*)>1
                  union all
                  select teacher_id,day_of_week,start_time,end_time from timetable_plan_slots where plan_id=?
                  group by teacher_id,day_of_week,start_time,end_time having count(*)>1
                  union all
                  select room_code,day_of_week,start_time,end_time from timetable_plan_slots
                  where plan_id=? and room_code is not null and room_code<>''
                  group by room_code,day_of_week,start_time,end_time having count(*)>1
                ) conflicts
                """, planId, planId, planId);
        return new Validation(conflicts == 0,
                conflicts == 0 ? null : "Phiên bản còn " + conflicts + " xung đột lớp, giáo viên hoặc phòng");
    }

    private TimetableVersion require(String id) {
        List<TimetableVersion> rows = jdbc.query("select * from timetable_plans where id=?",
                (rs, row) -> version(rs), id);
        if (rows.isEmpty()) throw ApiException.notFound("Phiên bản thời khóa biểu");
        return rows.get(0);
    }

    private TimetableVersion requireForUpdate(String id) {
        List<TimetableVersion> rows = jdbc.query("select * from timetable_plans where id=? for update",
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
                rs.getString("conflict_summary"), rs.getString("source_plan_id"),
                parseCsv(rs.getString("source_education_plan_ids")), rs.getString("created_by"),
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

    private static String csv(List<String> values) {
        return values == null || values.isEmpty() ? null : String.join(",", values);
    }

    private static List<String> parseCsv(String value) {
        return value == null || value.isBlank() ? List.of()
                : Arrays.stream(value.split(",")).map(String::trim).filter(item -> !item.isBlank()).toList();
    }

    private record Validation(boolean valid, String summary) {}
}
