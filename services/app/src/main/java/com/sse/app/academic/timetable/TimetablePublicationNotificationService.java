package com.sse.app.academic.timetable;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sse.app.academic.timetable.TimetableDtos.TimetableChange;
import com.sse.app.academic.timetable.TimetableDtos.TimetablePublicationPreview;
import com.sse.app.academic.timetable.TimetableDtos.TimetablePublicationStatus;
import com.sse.app.academic.timetable.TimetableDtos.TimetableVersionSlot;
import com.sse.app.audit.AuditService;
import com.sse.app.common.ApiException;
import com.sse.app.common.Ids;
import com.sse.app.identity.User;
import com.sse.app.identity.UserService;
import com.sse.app.notification.NotificationService;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Chuẩn bị phạm vi người nhận và ghi outbox thông báo khi lịch được phát hành. */
@Service
public class TimetablePublicationNotificationService {
    private static final String STATUS_SELECT = """
            select e.*,
              (select count(*) from timetable_publication_recipients r
               join notification_delivery_logs l on l.notification_id=r.notification_id
               where r.event_id=e.id and l.channel<>'IN_APP' and l.status in ('PENDING','PROCESSING','RETRYING')) as channel_pending_count,
              (select count(*) from timetable_publication_recipients r
               join notification_delivery_logs l on l.notification_id=r.notification_id
               where r.event_id=e.id and l.channel<>'IN_APP' and l.status='DELIVERED') as channel_delivered_count,
              (select count(*) from timetable_publication_recipients r
               join notification_delivery_logs l on l.notification_id=r.notification_id
               where r.event_id=e.id and l.channel<>'IN_APP' and l.status in ('FAILED','SKIPPED')) as channel_failed_count
            from timetable_publication_events e
            """;
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final TimetablePublicationDiffService diffService;
    private final ApplicationEventPublisher events;
    private final UserService users;
    private final AuditService audit;
    private final NotificationService notifications;

    public TimetablePublicationNotificationService(JdbcTemplate jdbc, ObjectMapper objectMapper,
                                                   TimetablePublicationDiffService diffService,
                                                   ApplicationEventPublisher events,
                                                   UserService users, AuditService audit,
                                                   NotificationService notifications) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.diffService = diffService;
        this.events = events;
        this.users = users;
        this.audit = audit;
        this.notifications = notifications;
    }

    public TimetablePublicationPreview preview(String planId) {
        PlanRow plan = plan(planId);
        PlanRow previous = currentlyPublished(plan.semesterId(), plan.id());
        List<TimetableVersionSlot> currentSlots = slots(plan.id());
        List<TimetableVersionSlot> previousSlots = previous == null ? List.of() : slots(previous.id());
        boolean first = previous == null;
        List<TimetableChange> changes = first ? currentSlots.stream()
                .map(this::firstPublicationChange).toList() : diffService.compare(previousSlots, currentSlots);
        RecipientScope scope = recipients(plan.semesterId(), currentSlots, changes, first);
        return new TimetablePublicationPreview(plan.id(), plan.name(), previous == null ? null : previous.id(),
                previous == null ? null : previous.name(), plan.semesterId(), first, changes.size(),
                scope.classIds().size(), scope.teacherCount(), scope.studentCount(), scope.parentCount(),
                scope.rows().size(), changes);
    }

    /** Được gọi trong cùng transaction phát hành; sự kiện chỉ được xử lý sau commit. */
    public TimetablePublicationStatus enqueue(String planId, String reason, String actorId) {
        List<TimetablePublicationStatus> existing = jdbc.query(
                STATUS_SELECT + " where e.plan_id=?", this::status, planId);
        if (!existing.isEmpty()) return existing.get(0);

        PlanRow plan = plan(planId);
        PlanRow previous = previouslySuperseded(plan.semesterId(), plan.id());
        List<TimetableVersionSlot> currentSlots = slots(plan.id());
        List<TimetableVersionSlot> previousSlots = previous == null ? List.of() : slots(previous.id());
        boolean first = previous == null;
        List<TimetableChange> changes = first ? currentSlots.stream()
                .map(this::firstPublicationChange).toList() : diffService.compare(previousSlots, currentSlots);
        RecipientScope scope = recipients(plan.semesterId(), currentSlots, changes, first);
        String eventId = Ids.gen("ttpub");
        Instant now = Instant.now();
        jdbc.update("""
                insert into timetable_publication_events
                (id,plan_id,semester_id,previous_plan_id,event_type,status,reason,diff_json,
                 change_count,affected_class_count,teacher_recipient_count,student_recipient_count,
                 parent_recipient_count,total_recipient_count,delivered_recipient_count,
                 failed_recipient_count,attempts,created_by,created_at,updated_at,next_attempt_at)
                values (?,?,?,?,?,'PENDING',?,?,?,?,?,?,?,?,0,0,0,?,?,?,?)
                """, eventId, plan.id(), plan.semesterId(), previous == null ? null : previous.id(),
                first ? "FIRST_PUBLICATION" : "REPLACEMENT", cleanReason(reason), json(changes),
                changes.size(), scope.classIds().size(), scope.teacherCount(), scope.studentCount(),
                scope.parentCount(), scope.rows().size(), actorId, time(now), time(now), time(now));
        for (Recipient recipient : scope.rows()) {
            Message message = message(plan, first, changes, recipient);
            jdbc.update("""
                    insert into timetable_publication_recipients
                    (id,event_id,recipient_id,recipient_role,context_key,student_id,class_id,
                     status,title,body,action_url,attempts,created_at,updated_at)
                    values (?,?,?,?,?,?,?,'PENDING',?,?,?,?,?,?)
                    """, Ids.gen("ttpubr"), eventId, recipient.userId(), recipient.role(), recipient.contextKey(),
                    recipient.studentId(), recipient.classId(), message.title(), message.body(),
                    message.actionUrl(), 0, time(now), time(now));
        }
        events.publishEvent(new PublicationQueued(eventId));
        return requireStatusByPlan(planId);
    }

    public TimetablePublicationStatus requireStatusByPlan(String planId) {
        List<TimetablePublicationStatus> rows = jdbc.query(
                STATUS_SELECT + " where e.plan_id=?", this::status, planId);
        if (rows.isEmpty()) throw ApiException.notFound("Kết quả gửi thông báo thời khóa biểu");
        return rows.get(0);
    }

    @Transactional
    public TimetablePublicationStatus retry(String planId, String reason, String actorId) {
        TimetablePublicationStatus current = requireStatusByPlan(planId);
        if (current.failedRecipientCount() == 0 && current.channelFailedCount() == 0
                && !"FAILED".equals(current.status())) {
            throw ApiException.conflict("Không có người nhận thất bại cần gửi lại");
        }
        Instant now = Instant.now();
        if (current.failedRecipientCount() > 0 || "FAILED".equals(current.status())) {
            jdbc.update("""
                    update timetable_publication_recipients set status='PENDING',attempts=0,last_error=null,updated_at=?
                    where event_id=? and status='FAILED'
                    """, time(now), current.id());
            jdbc.update("""
                    update timetable_publication_events set status='RETRYING',attempts=0,last_error=null,
                        next_attempt_at=?,updated_at=? where id=?
                    """, time(now), time(now), current.id());
            events.publishEvent(new PublicationQueued(current.id()));
        }
        List<String> notificationIds = jdbc.queryForList("""
                select notification_id from timetable_publication_recipients
                where event_id=? and notification_id is not null
                """, String.class, current.id());
        int channelRetries = notifications.retryFailedDeliveriesForNotifications(notificationIds, reason);
        User actor = users.getById(actorId);
        audit.record(actorId, actor.getFullName(), actor.getRole(), "TIMETABLE_NOTIFICATION_RETRY",
                "academic", "timetable_publication_event", current.id(),
                "Gửi lại " + current.failedRecipientCount() + " lượt in-app và " + channelRetries
                        + " lượt email/push thất bại; lý do: " + cleanReason(reason));
        return requireStatusByPlan(planId);
    }

    private RecipientScope recipients(String semesterId, List<TimetableVersionSlot> currentSlots,
                                      List<TimetableChange> changes, boolean first) {
        Set<String> classIds = new LinkedHashSet<>();
        Set<String> teacherIds = new LinkedHashSet<>();
        if (first) {
            currentSlots.forEach(slot -> {
                add(classIds, slot.classId());
                add(teacherIds, slot.teacherId());
            });
        } else {
            changes.forEach(change -> {
                add(classIds, change.classId());
                add(teacherIds, change.previousTeacherId());
                add(teacherIds, change.newTeacherId());
            });
        }
        List<Recipient> rows = new ArrayList<>();
        if (!teacherIds.isEmpty()) {
            String marks = marks(teacherIds.size());
            rows.addAll(jdbc.query("select id,full_name from users where role='TEACHER' and status='ACTIVE' and id in ("
                            + marks + ") order by full_name", (rs, ignored) -> new Recipient(rs.getString("id"),
                            "TEACHER", "teacher:" + rs.getString("id"), null, null,
                            rs.getString("full_name"), null), teacherIds.toArray()));
        }
        if (!classIds.isEmpty()) {
            String academicYearId = jdbc.queryForObject(
                    "select academic_year_id from semesters where id=?", String.class, semesterId);
            String marks = marks(classIds.size());
            List<Recipient> students = jdbc.query("""
                    select distinct u.id,u.full_name,ce.class_id,c.code as class_code
                    from class_enrollments ce
                    join users u on u.id=ce.student_id and u.role='STUDENT' and u.status='ACTIVE'
                    join classes c on c.id=ce.class_id
                    where ce.academic_year_id=? and ce.status='ACTIVE' and ce.class_id in (""" + marks + ")"
                    + " order by c.code,u.full_name", (rs, ignored) -> new Recipient(rs.getString("id"),
                    "STUDENT", "student:" + rs.getString("id"), rs.getString("id"), rs.getString("class_id"),
                    rs.getString("full_name"), rs.getString("class_code")), concat(academicYearId, classIds));
            rows.addAll(students);
            if (!students.isEmpty()) {
                Set<String> studentIds = students.stream().map(Recipient::studentId)
                        .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
                Map<String, Recipient> studentById = new LinkedHashMap<>();
                students.forEach(student -> studentById.put(student.studentId(), student));
                rows.addAll(jdbc.query("""
                        select distinct p.id as parent_id,p.full_name as parent_name,ps.student_id
                        from parent_student ps
                        join users p on p.id=ps.parent_id and p.role='PARENT' and p.status='ACTIVE'
                        where ps.student_id in (""" + marks(studentIds.size()) + ") order by p.full_name,ps.student_id",
                        (rs, ignored) -> {
                            Recipient student = studentById.get(rs.getString("student_id"));
                            return new Recipient(rs.getString("parent_id"), "PARENT",
                                    "student:" + student.studentId(), student.studentId(), student.classId(),
                                    student.displayName(), student.classCode());
                        }, studentIds.toArray()));
            }
        }
        rows = rows.stream().filter(row -> row.userId() != null && !row.userId().isBlank())
                .collect(java.util.stream.Collectors.toMap(
                        row -> row.userId() + "|" + row.contextKey(), row -> row, (left, right) -> left,
                        LinkedHashMap::new)).values().stream().toList();
        int teacherCount = (int) rows.stream().filter(row -> "TEACHER".equals(row.role())).count();
        int studentCount = (int) rows.stream().filter(row -> "STUDENT".equals(row.role())).count();
        int parentCount = (int) rows.stream().filter(row -> "PARENT".equals(row.role())).count();
        return new RecipientScope(rows, classIds, teacherCount, studentCount, parentCount);
    }

    private Message message(PlanRow plan, boolean first, List<TimetableChange> changes, Recipient recipient) {
        List<TimetableChange> relevant = changes.stream().filter(change -> switch (recipient.role()) {
            case "TEACHER" -> recipient.userId().equals(change.previousTeacherId())
                    || recipient.userId().equals(change.newTeacherId());
            default -> recipient.classId() != null && recipient.classId().equals(change.classId());
        }).toList();
        String title = first ? "Thời khóa biểu chính thức đã được phát hành"
                : "Thời khóa biểu chính thức vừa được cập nhật";
        StringBuilder body = new StringBuilder();
        if ("PARENT".equals(recipient.role())) {
            body.append("Lịch học của ").append(recipient.displayName()).append(" (lớp ")
                    .append(recipient.classCode()).append(") ");
        } else if ("STUDENT".equals(recipient.role())) {
            body.append("Thời khóa biểu lớp ").append(recipient.classCode()).append(' ');
        } else {
            body.append("Lịch dạy của thầy/cô ");
        }
        body.append(first ? "đã được phát hành chính thức." : "đã được thay thế bằng phiên bản mới.");
        if (!first && !relevant.isEmpty()) {
            body.append(" Thay đổi: ");
            body.append(relevant.stream().limit(4).map(TimetableChange::summary)
                    .collect(java.util.stream.Collectors.joining("; ")));
            if (relevant.size() > 4) body.append("; và ").append(relevant.size() - 4).append(" thay đổi khác");
            body.append('.');
        }
        body.append(" Phiên bản: ").append(plan.name()).append('.');
        String semester = encode(plan.semesterId());
        String actionUrl = switch (recipient.role()) {
            case "TEACHER" -> "#/giao-vien/thoi-khoa-bieu?semester=" + semester;
            case "PARENT" -> "#/phu-huynh/hoc-tap-cua-con?tab=timetable&child="
                    + encode(recipient.studentId()) + "&semester=" + semester;
            default -> "#/hoc-sinh/theo-doi-hoc-tap?tab=tkb&semester=" + semester;
        };
        return new Message(title, abbreviate(body.toString(), 1900), actionUrl);
    }

    private TimetableChange firstPublicationChange(TimetableVersionSlot slot) {
        return new TimetableChange("ADDED", slot.classId(), slot.classCode(), slot.subjectId(), slot.subjectName(),
                null, null, slot.teacherId(), slot.teacherName(), null, slot.roomCode(), null, null,
                slot.dayOfWeek(), slot.periodNo(), slot.classCode() + " · " + slot.subjectName()
                + ": lịch chính thức " + slot.dayOfWeek() + ", tiết " + slot.periodNo());
    }

    private PlanRow plan(String id) {
        List<PlanRow> rows = jdbc.query("select id,name,semester_id from timetable_plans where id=?",
                (rs, ignored) -> new PlanRow(rs.getString("id"), rs.getString("name"), rs.getString("semester_id")), id);
        if (rows.isEmpty()) throw ApiException.notFound("Phiên bản thời khóa biểu");
        return rows.get(0);
    }

    private PlanRow currentlyPublished(String semesterId, String excludedPlanId) {
        return onePlan("select id,name,semester_id from timetable_plans where semester_id=? and status='PUBLISHED' and id<>? order by published_at desc",
                semesterId, excludedPlanId);
    }

    private PlanRow previouslySuperseded(String semesterId, String currentPlanId) {
        return onePlan("select id,name,semester_id from timetable_plans where semester_id=? and status='SUPERSEDED' and id<>? order by published_at desc",
                semesterId, currentPlanId);
    }

    private PlanRow onePlan(String sql, Object... args) {
        List<PlanRow> rows = jdbc.query(sql, (rs, ignored) -> new PlanRow(rs.getString("id"),
                rs.getString("name"), rs.getString("semester_id")), args);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private List<TimetableVersionSlot> slots(String planId) {
        return jdbc.query("select * from timetable_plan_slots where plan_id=? order by class_code,day_of_week,period_no",
                (rs, ignored) -> new TimetableVersionSlot(rs.getString("id"), rs.getString("plan_id"),
                        rs.getString("class_id"), rs.getString("class_code"), rs.getString("study_shift"),
                        rs.getString("subject_id"), rs.getString("subject_name"), rs.getString("teacher_id"),
                        rs.getString("teacher_name"), rs.getString("room_code"), rs.getString("day_of_week"),
                        rs.getObject("period_no", Integer.class), rs.getString("start_time"),
                        rs.getString("end_time"), rs.getObject("locked", Boolean.class)), planId);
    }

    private TimetablePublicationStatus status(ResultSet rs, int ignored) throws SQLException {
        return new TimetablePublicationStatus(rs.getString("id"), rs.getString("plan_id"),
                rs.getString("previous_plan_id"), rs.getString("semester_id"), rs.getString("event_type"),
                rs.getString("status"), rs.getString("reason"), rs.getInt("change_count"),
                rs.getInt("affected_class_count"), rs.getInt("teacher_recipient_count"),
                rs.getInt("student_recipient_count"), rs.getInt("parent_recipient_count"),
                rs.getInt("total_recipient_count"), rs.getInt("delivered_recipient_count"),
                rs.getInt("failed_recipient_count"), rs.getInt("channel_pending_count"),
                rs.getInt("channel_delivered_count"), rs.getInt("channel_failed_count"),
                rs.getInt("attempts"), rs.getString("last_error"),
                instant(rs, "created_at"), instant(rs, "processed_at"), instant(rs, "updated_at"));
    }

    private String json(List<TimetableChange> changes) {
        try { return objectMapper.writeValueAsString(changes); }
        catch (JsonProcessingException exception) { throw new IllegalStateException("Không thể lưu thay đổi lịch", exception); }
    }

    private String cleanReason(String value) {
        String reason = value == null ? "" : value.trim();
        if (reason.isBlank()) throw ApiException.badRequest("Vui lòng nhập lý do phát hành hoặc thay thế lịch");
        return abbreviate(reason, 1000);
    }

    private Object[] concat(String first, Set<String> rest) {
        List<Object> values = new ArrayList<>();
        values.add(first);
        values.addAll(rest);
        return values.toArray();
    }

    private String marks(int size) { return String.join(",", java.util.Collections.nCopies(size, "?")); }
    private void add(Set<String> target, String value) { if (value != null && !value.isBlank()) target.add(value); }
    private String encode(String value) { return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8); }
    private String abbreviate(String value, int max) { return value.length() <= max ? value : value.substring(0, max - 1) + "…"; }
    private Timestamp time(Instant value) { return value == null ? null : Timestamp.from(value); }
    private Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    public record PublicationQueued(String eventId) {}
    private record PlanRow(String id, String name, String semesterId) {}
    private record Recipient(String userId, String role, String contextKey, String studentId, String classId,
                             String displayName, String classCode) {}
    private record RecipientScope(List<Recipient> rows, Set<String> classIds,
                                  int teacherCount, int studentCount, int parentCount) {}
    private record Message(String title, String body, String actionUrl) {}
}
