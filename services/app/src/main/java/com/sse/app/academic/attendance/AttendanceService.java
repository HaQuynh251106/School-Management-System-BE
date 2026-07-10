package com.sse.app.academic.attendance;

import com.sse.app.academic.attendance.AttendanceDtos.*;
import com.sse.app.academic.teaching.TeachingAssignmentService;
import com.sse.app.academic.timetable.TimetableService;
import com.sse.app.academic.timetable.TimetableSlot;
import com.sse.app.common.Ids;
import com.sse.app.event.DomainEventPublisher;
import com.sse.app.identity.UserService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** B3: Sổ điểm danh + D2: cảnh báo vắng tức thời (flowchart 2.5). */
@Service
public class AttendanceService {

    private final AttendanceRepository records;
    private final TimetableService timetable;
    private final TeachingAssignmentService teachingAssignments;
    private final UserService users;
    private final DomainEventPublisher events;

    public AttendanceService(AttendanceRepository records, TimetableService timetable,
                             TeachingAssignmentService teachingAssignments,
                             UserService users, DomainEventPublisher events) {
        this.records = records;
        this.timetable = timetable;
        this.teachingAssignments = teachingAssignments;
        this.users = users;
        this.events = events;
    }

    public List<AttendanceRecord> list(String studentId, String classId, String slotId, LocalDate date) {
        List<AttendanceRecord> base;
        if (studentId != null)      base = records.findByStudentId(studentId);
        else if (classId != null && date != null) base = records.findByClassIdAndDate(classId, date);
        else if (classId != null)   base = records.findByClassId(classId);
        else if (slotId != null && date != null)  base = records.findBySlotIdAndDate(slotId, date);
        else base = records.findAll();
        return base.stream()
                .filter(r -> slotId == null || slotId.equals(r.getSlotId()))
                .filter(r -> date == null || date.equals(r.getDate()))
                .filter(r -> classId == null || classId.equals(r.getClassId()))
                .toList();
    }

    @Transactional
    public List<AttendanceRecord> bulkMark(BulkAttendanceRequest req) {
        return bulkMark(req, null, false);
    }

    @Transactional
    public List<AttendanceRecord> bulkMark(BulkAttendanceRequest req, String actorId, boolean enforceTeacherAssignment) {
        TimetableSlot slot = timetable.findSlot(req.slotId());
        if (enforceTeacherAssignment && !canTeacherMark(actorId, slot)) {
            throw com.sse.app.common.ApiException.forbidden("Teacher can only mark attendance for assigned class/subject");
        }
        String subjectName = req.subjectName() != null ? req.subjectName()
                : (slot != null ? slot.getSubjectName() : null);
        Integer periodNo = req.periodNo() != null ? req.periodNo()
                : (slot != null ? slot.getPeriodNo() : null);
        String classId = req.classId() != null ? req.classId()
                : (slot != null ? slot.getClassId() : null);

        List<AttendanceRecord> saved = new ArrayList<>();
        for (Mark m : req.marks()) {
            AttendanceRecord rec = records
                    .findBySlotIdAndDateAndStudentId(req.slotId(), req.date(), m.studentId())
                    .orElseGet(() -> AttendanceRecord.builder().id(Ids.gen("att")).build());
            rec.setStudentId(m.studentId());
            rec.setClassId(classId);
            rec.setSlotId(req.slotId());
            rec.setDate(req.date());
            rec.setStatus(m.status());
            rec.setNote(m.note());
            rec.setSubjectName(subjectName);
            rec.setPeriodNo(periodNo);
            saved.add(records.save(rec));

            if (isAbsentOrLate(m.status())) {
                alertParents(rec);
            }
        }
        return saved;
    }

    private boolean canTeacherMark(String teacherId, TimetableSlot slot) {
        if (teacherId == null || slot == null) return false;
        if (teacherId.equals(slot.getTeacherId())) return true;
        return teachingAssignments.teacherAssigned(
                teacherId, slot.getClassId(), slot.getSubjectId(), slot.getSemesterId());
    }

    private void alertParents(AttendanceRecord rec) {
        String studentName = users.fullNameOf(rec.getStudentId());
        String title = "Cảnh báo chuyên cần";
        String body = String.format("%s %s môn %s ngày %s",
                studentName == null ? "Học sinh" : studentName,
                statusLabel(rec.getStatus()),
                rec.getSubjectName() == null ? "" : rec.getSubjectName(),
                rec.getDate());
        events.publish("academic.attendance.absent", rec.getStudentId(), "attendance", rec.getId(),
                Map.of("studentId", rec.getStudentId(),
                        "status", rec.getStatus(),
                        "date", String.valueOf(rec.getDate()),
                        "periodNo", rec.getPeriodNo() == null ? "" : rec.getPeriodNo(),
                        "message", body.trim()));
    }

    /** Seed raw (không bắn cảnh báo) — dùng bởi DataSeeder. */
    public void seed(List<AttendanceRecord> list) {
        records.saveAll(list);
    }

    /** A8: toàn bộ bản ghi điểm danh cho báo cáo chuyên cần. */
    public List<AttendanceRecord> allRecords() { return records.findAll(); }

    private boolean isAbsentOrLate(String status) {
        return status != null && (status.startsWith("ABSENT") || "LATE".equals(status));
    }

    private String statusLabel(String status) {
        return switch (status) {
            case "ABSENT_UNEXCUSED" -> "vắng không phép";
            case "ABSENT_EXCUSED" -> "vắng có phép";
            case "LATE" -> "đi muộn";
            default -> status;
        };
    }
}
