package com.sse.app.academic.attendance;

import com.sse.app.academic.attendance.AttendanceDtos.*;
import com.sse.app.academic.teaching.TeachingAssignmentService;
import com.sse.app.academic.timetable.TimetableService;
import com.sse.app.academic.timetable.TimetableSlot;
import com.sse.app.audit.AuditService;
import com.sse.app.common.ApiException;
import com.sse.app.common.Ids;
import com.sse.app.event.DomainEventPublisher;
import com.sse.app.identity.UserService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** B3: Sổ điểm danh + D2: cảnh báo vắng tức thời (flowchart 2.5). */
@Service
public class AttendanceService {

    private final AttendanceRepository records;
    private final TimetableService timetable;
    private final TeachingAssignmentService teachingAssignments;
    private final UserService users;
    private final DomainEventPublisher events;
    private final AttendanceExcuseRequestRepository excuseRequests;
    private final AuditService audit;

    public AttendanceService(AttendanceRepository records, TimetableService timetable,
                             TeachingAssignmentService teachingAssignments,
                             UserService users, DomainEventPublisher events,
                             AttendanceExcuseRequestRepository excuseRequests,
                             AuditService audit) {
        this.records = records;
        this.timetable = timetable;
        this.teachingAssignments = teachingAssignments;
        this.users = users;
        this.events = events;
        this.excuseRequests = excuseRequests;
        this.audit = audit;
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

    public AttendanceRecord getRecord(String id) {
        return records.findById(id)
                .orElseThrow(() -> ApiException.notFound("Bản ghi điểm danh"));
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
            validateMark(m);
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
            rec.setLateMinutes("LATE".equals(m.status())
                    ? (m.lateMinutes() == null ? 1 : m.lateMinutes()) : null);
            saved.add(records.save(rec));

            if (isAbsentOrLate(m.status())) {
                alertParents(rec);
                alertRepeatedViolation(rec);
            }
        }
        return saved;
    }

    @Transactional
    public AttendanceExcuseRequest requestExcuse(
            String attendanceRecordId, String studentId, String requestedBy,
            String requesterRole, String reason) {
        AttendanceRecord record = records.findById(attendanceRecordId)
                .orElseThrow(() -> ApiException.notFound("Bản ghi điểm danh"));
        if (!studentId.equals(record.getStudentId())) {
            throw ApiException.forbidden("Bản ghi điểm danh không thuộc học sinh đã chọn");
        }
        if (!isAbsentOrLate(record.getStatus())) {
            throw ApiException.conflict("Chỉ có thể xin phép cho lượt vắng hoặc đi muộn");
        }
        String normalizedReason = requireText(reason, "lý do xin phép");
        if (excuseRequests.findByAttendanceRecordIdAndStatus(
                attendanceRecordId, "PENDING").isPresent()) {
            throw ApiException.conflict("Bản ghi đã có yêu cầu đang chờ duyệt");
        }
        AttendanceExcuseRequest row = excuseRequests.save(
                AttendanceExcuseRequest.builder()
                        .id(Ids.gen("aer"))
                        .attendanceRecordId(attendanceRecordId)
                        .studentId(studentId)
                        .requestedBy(requestedBy)
                        .requesterRole(requesterRole)
                        .reason(normalizedReason)
                        .status("PENDING")
                        .requestedAt(Instant.now())
                        .build());
        audit.record(requestedBy, users.fullNameOf(requestedBy), requesterRole,
                "REQUEST_ATTENDANCE_EXCUSE", "academic",
                "attendance_excuse_request", row.getId(),
                "attendance=" + attendanceRecordId + "; lý do=" + normalizedReason);
        return row;
    }

    public List<AttendanceExcuseRequest> excuseRequests(
            String studentId, String status) {
        if (status != null && !status.isBlank()) {
            List<AttendanceExcuseRequest> rows =
                    excuseRequests.findByStatusOrderByRequestedAtAsc(
                            status.trim().toUpperCase());
            return studentId == null || studentId.isBlank() ? rows
                    : rows.stream().filter(row -> studentId.equals(row.getStudentId())).toList();
        }
        if (studentId == null || studentId.isBlank()) {
            return excuseRequests.findAll().stream()
                    .sorted(java.util.Comparator.comparing(
                            AttendanceExcuseRequest::getRequestedAt,
                            java.util.Comparator.nullsLast(java.util.Comparator.reverseOrder())))
                    .toList();
        }
        return excuseRequests.findByStudentIdOrderByRequestedAtDesc(studentId);
    }

    public List<AttendanceExcuseRequest> excuseRequestsForTeacher(
            String teacherId, String status) {
        return excuseRequests(null, status).stream().filter(request -> {
            try {
                AttendanceRecord record = records.findById(request.getAttendanceRecordId())
                        .orElse(null);
                return record != null && canTeacherMark(
                        teacherId, timetable.findSlot(record.getSlotId()));
            } catch (RuntimeException ignored) {
                return false;
            }
        }).toList();
    }

    @Transactional
    public AttendanceExcuseRequest reviewExcuse(
            String requestId, String decision, String note,
            String reviewerId, String reviewerRole) {
        AttendanceExcuseRequest request = excuseRequests.findById(requestId)
                .orElseThrow(() -> ApiException.notFound("Yêu cầu xin phép"));
        if (!"PENDING".equals(request.getStatus())) {
            throw ApiException.conflict("Yêu cầu đã được xử lý");
        }
        String normalized = decision == null ? "" : decision.trim().toUpperCase();
        if (!Set.of("APPROVED", "REJECTED").contains(normalized)) {
            throw ApiException.badRequest("Quyết định chỉ nhận APPROVED hoặc REJECTED");
        }
        AttendanceRecord record = records.findById(request.getAttendanceRecordId())
                .orElseThrow(() -> ApiException.notFound("Bản ghi điểm danh"));
        if ("TEACHER".equals(reviewerRole)) {
            TimetableSlot slot = timetable.findSlot(record.getSlotId());
            if (!canTeacherMark(reviewerId, slot)) {
                throw ApiException.forbidden("Giáo viên không được phân công tiết học này");
            }
        }
        request.setStatus(normalized);
        request.setReviewedBy(reviewerId);
        request.setReviewNote(note == null || note.isBlank() ? null : note.trim());
        request.setReviewedAt(Instant.now());
        excuseRequests.save(request);
        if ("APPROVED".equals(normalized)) {
            record.setStatus("ABSENT_EXCUSED");
            record.setNote(appendNote(record.getNote(), "Đã duyệt đơn xin phép"));
            records.save(record);
        }
        audit.record(reviewerId, users.fullNameOf(reviewerId), reviewerRole,
                "REVIEW_ATTENDANCE_EXCUSE", "academic",
                "attendance_excuse_request", request.getId(),
                "quyết định=" + normalized
                        + (request.getReviewNote() == null ? ""
                        : "; ghi chú=" + request.getReviewNote()));
        events.publish("academic.attendance.excuse_reviewed", reviewerId,
                "attendance_excuse_request", request.getId(),
                Map.of("studentId", request.getStudentId(), "status", normalized,
                        "message", "Yêu cầu xin phép chuyên cần đã được "
                                + ("APPROVED".equals(normalized)
                                ? "chấp nhận" : "từ chối")));
        return request;
    }

    public AttendanceSummary summary(
            String studentId, LocalDate fromDate, LocalDate toDate) {
        LocalDate to = toDate == null ? LocalDate.now() : toDate;
        LocalDate from = fromDate == null ? to.minusMonths(1).withDayOfMonth(1) : fromDate;
        if (from.isAfter(to)) {
            throw ApiException.badRequest("fromDate không được sau toDate");
        }
        List<AttendanceRecord> rows =
                records.findByStudentIdAndDateBetweenOrderByDateDesc(
                        studentId, from, to);
        int present = count(rows, "PRESENT");
        int excused = count(rows, "ABSENT_EXCUSED");
        int unexcused = count(rows, "ABSENT_UNEXCUSED");
        int late = count(rows, "LATE");
        int lateMinutes = rows.stream()
                .filter(row -> "LATE".equals(row.getStatus()))
                .mapToInt(row -> row.getLateMinutes() == null
                        ? 0 : row.getLateMinutes())
                .sum();
        double rate = rows.isEmpty() ? 100.0
                : Math.round((present + late) * 10000.0 / rows.size()) / 100.0;
        return new AttendanceSummary(studentId, from, to, rows.size(), present,
                excused, unexcused, late, lateMinutes, rate,
                unexcused + late >= 3, Instant.now());
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

    private void alertRepeatedViolation(AttendanceRecord rec) {
        LocalDate from = rec.getDate().minusDays(29);
        List<AttendanceRecord> recent =
                records.findByStudentIdAndDateBetweenOrderByDateDesc(
                        rec.getStudentId(), from, rec.getDate());
        long violations = recent.stream()
                .filter(row -> "ABSENT_UNEXCUSED".equals(row.getStatus())
                        || "LATE".equals(row.getStatus()))
                .count();
        if (violations == 3 || violations == 5 || violations == 10) {
            events.publish("academic.attendance.repeated_violation",
                    rec.getStudentId(), "attendance", rec.getId(),
                    Map.of("studentId", rec.getStudentId(),
                            "count", violations,
                            "fromDate", from.toString(),
                            "message", "Học sinh có " + violations
                                    + " lượt vắng không phép/đi muộn trong 30 ngày"));
        }
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

    private void validateMark(Mark mark) {
        if (!Set.of("PRESENT", "ABSENT_EXCUSED", "ABSENT_UNEXCUSED", "LATE")
                .contains(mark.status())) {
            throw ApiException.badRequest("Trạng thái điểm danh không hợp lệ");
        }
        if (mark.lateMinutes() != null
                && (!"LATE".equals(mark.status())
                || mark.lateMinutes() < 1 || mark.lateMinutes() > 240)) {
            throw ApiException.badRequest("Số phút đi muộn phải từ 1 đến 240");
        }
    }

    private int count(List<AttendanceRecord> rows, String status) {
        return (int) rows.stream()
                .filter(row -> status.equals(row.getStatus())).count();
    }

    private String requireText(String value, String label) {
        if (value == null || value.isBlank()) {
            throw ApiException.badRequest("Thiếu " + label);
        }
        return value.trim();
    }

    private String appendNote(String current, String added) {
        return current == null || current.isBlank() ? added : current + "; " + added;
    }
}
