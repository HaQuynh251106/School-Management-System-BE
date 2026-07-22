package com.sse.app.academic.attendance;

import com.sse.app.academic.attendance.AttendanceDtos.*;
import com.sse.app.academic.timetable.TimetableService;
import com.sse.app.academic.timetable.TimetableSlot;
import com.sse.app.academic.structure.Semester;
import com.sse.app.academic.structure.StructureService;
import com.sse.app.academic.leave.LeaveRequest;
import com.sse.app.academic.leave.LeaveRequestService;
import com.sse.app.common.Ids;
import com.sse.app.common.ApiException;
import com.sse.app.identity.User;
import com.sse.app.identity.UserService;
import com.sse.app.notification.NotificationService;
import com.sse.app.notification.Announcement;
import com.sse.app.security.CurrentUser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** B3: Sổ điểm danh + D2: cảnh báo vắng tức thời (flowchart 2.5). */
@Service
public class AttendanceService {

    private static final ZoneId SCHOOL_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    private final AttendanceRepository records;
    private final TimetableService timetable;
    private final UserService users;
    private final NotificationService notifications;
    private final StructureService structure;
    private final AttendanceSessionAccessRepository sessionAccesses;
    private final LeaveRequestService leaveRequests;

    public AttendanceService(AttendanceRepository records, TimetableService timetable,
                             UserService users, NotificationService notifications,
                             StructureService structure, AttendanceSessionAccessRepository sessionAccesses,
                             LeaveRequestService leaveRequests) {
        this.records = records;
        this.timetable = timetable;
        this.users = users;
        this.notifications = notifications;
        this.structure = structure;
        this.sessionAccesses = sessionAccesses;
        this.leaveRequests = leaveRequests;
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

    public void assertCanManageSlot(CurrentUser actor, String slotId) {
        TimetableSlot slot = requireSlot(slotId);
        if (actor.isTeacher()) {
            if (!actor.id().equals(slot.getTeacherId())) {
                throw ApiException.forbidden("Không có quyền điểm danh tiết học của giáo viên khác");
            }
            User teacher = users.getById(actor.id());
            String mainSubject = normalizeSubject(teacher.getMainSubject());
            if (mainSubject == null) {
                throw ApiException.forbidden("Giáo viên chưa được cấu hình môn chuyên ngành để điểm danh");
            }
            if (!matchesSubject(mainSubject, slot.getSubjectId(), slot.getSubjectName())) {
                throw ApiException.forbidden("Giáo viên chỉ được điểm danh các tiết đúng môn chuyên ngành của mình");
            }
        }
    }

    public AttendanceDayStatus dayStatus(LocalDate date) {
        return notifications.schoolHolidayOn(date)
                .map(holiday -> new AttendanceDayStatus(false, holiday.getId(), holiday.getTitle(), holiday.getBody(),
                        holiday.getHolidayStartDate(), holiday.getHolidayEndDate()))
                .orElseGet(() -> new AttendanceDayStatus(true, null, null, null, null, null));
    }

    public AttendanceSessionStatus sessionStatus(String slotId, LocalDate date) {
        return sessionStatus(requireSlot(slotId), date, ZonedDateTime.now(SCHOOL_ZONE));
    }

    public List<LeaveRequest> approvedLeaves(String slotId, LocalDate date, CurrentUser actor) {
        TimetableSlot slot = requireSlot(slotId);
        assertCanManageSlot(actor, slotId);
        return leaveRequests.approvedForClassOn(slot.getClassId(), date);
    }

    @Transactional
    public AttendanceSessionStatus unlockLateAttendance(UnlockAttendanceRequest request, CurrentUser actor) {
        TimetableSlot slot = requireSlot(request.slotId());
        assertCanManageSlot(actor, slot.getId());
        AttendanceSessionStatus current = sessionStatus(slot, request.date(), ZonedDateTime.now(SCHOOL_ZONE));
        if ("LATE_UNLOCKED".equals(current.state()) || "COMPLETED_LATE".equals(current.state())) {
            return current;
        }
        if (!"LOCKED_REASON_REQUIRED".equals(current.state())) {
            throw ApiException.badRequest(current.message());
        }

        AttendanceSessionAccess access = accessFor(slot, request.date());
        access.setUnlockReason(request.reason().trim());
        access.setUnlockedAt(java.time.Instant.now());
        access.setUnlockedBy(actor.id());
        sessionAccesses.save(access);

        String classCode = structure.getClass(slot.getClassId()).getCode();
        String teacherName = users.fullNameOf(actor.id());
        String body = (teacherName == null ? "Giáo viên" : teacherName)
                + " đã mở khóa điểm danh muộn môn " + slot.getSubjectName()
                + " tại lớp " + classCode + ", tiết " + slot.getPeriodNo()
                + " ngày " + request.date() + ". Lý do: " + access.getUnlockReason();
        notifications.notifyUsers(users.userIdsByRole("ADMIN"), "ATTENDANCE_UNLOCK", "IMPORTANT",
                "Mở khóa điểm danh muộn", body, "ATTENDANCE_SESSION", access.getId());
        return sessionStatus(slot, request.date(), ZonedDateTime.now(SCHOOL_ZONE));
    }

    @Transactional
    public synchronized int sendDueReminders(ZonedDateTime now) {
        ZonedDateTime schoolNow = now.withZoneSameInstant(SCHOOL_ZONE);
        LocalDate date = schoolNow.toLocalDate();
        if (notifications.schoolHolidayOn(date).isPresent()) return 0;
        String day = date.getDayOfWeek().name().substring(0, 3);
        int sent = 0;
        for (TimetableSlot slot : timetable.list(null, null, null, day)) {
            if (!isScheduledOccurrence(slot, date)) continue;
            LocalTime start = parseTime(slot.getStartTime());
            LocalTime end = parseTime(slot.getEndTime());
            if (start == null || end == null || schoolNow.toLocalTime().isBefore(start)
                    || !schoolNow.toLocalTime().isBefore(end)) continue;
            if (records.existsBySlotIdAndDate(slot.getId(), date)) continue;

            AttendanceSessionAccess access = accessFor(slot, date);
            if (access.getReminderSentAt() != null) continue;
            access.setReminderSentAt(java.time.Instant.now());
            sessionAccesses.save(access);

            String classCode = structure.getClass(slot.getClassId()).getCode();
            notifications.notifyUser(slot.getTeacherId(), "ATTENDANCE_REMINDER", "IMPORTANT",
                    "Đến giờ điểm danh tiết " + slot.getPeriodNo(),
                    "Môn " + slot.getSubjectName() + " · Lớp " + classCode + " · "
                            + slot.getStartTime() + "–" + slot.getEndTime()
                            + ". Thầy/cô vui lòng mở sổ điểm danh.",
                    "ATTENDANCE_SESSION", slot.getId() + ":" + date);
            sent++;
        }
        return sent;
    }

    @Transactional
    public List<AttendanceRecord> bulkMark(BulkAttendanceRequest req, CurrentUser actor) {
        TimetableSlot slot = requireSlot(req.slotId());
        assertCanManageSlot(actor, req.slotId());
        validateOccurrence(slot, req.date());

        Set<String> seenStudents = new HashSet<>();
        for (Mark mark : req.marks()) {
            if (!seenStudents.add(mark.studentId())) {
                throw ApiException.badRequest("Danh sách điểm danh chứa học sinh bị trùng");
            }
            boolean approvedLeave = leaveRequests.hasApprovedLeave(mark.studentId(), req.date());
            if (!"PRESENT".equals(mark.status()) && !approvedLeave && (mark.note() == null || mark.note().isBlank())) {
                throw ApiException.badRequest("Cần nhập ghi chú cho học sinh vắng hoặc đi muộn");
            }
            User student = users.getById(mark.studentId());
            if (!"STUDENT".equals(student.getRole()) || !slot.getClassId().equals(student.getClassId())) {
                throw ApiException.badRequest("Học sinh " + student.getFullName() + " không thuộc lớp của tiết học");
            }
        }

        List<AttendanceRecord> saved = new ArrayList<>();
        for (Mark m : req.marks()) {
            AttendanceRecord rec = records
                    .findBySlotIdAndDateAndStudentId(req.slotId(), req.date(), m.studentId())
                    .orElseGet(() -> AttendanceRecord.builder().id(Ids.gen("att")).build());
            String previousStatus = rec.getStatus();
            rec.setStudentId(m.studentId());
            rec.setClassId(slot.getClassId());
            rec.setSlotId(req.slotId());
            rec.setDate(req.date());
            boolean approvedLeave = leaveRequests.hasApprovedLeave(m.studentId(), req.date());
            String resolvedStatus = approvedLeave && !"PRESENT".equals(m.status()) ? "ABSENT_EXCUSED" : m.status();
            String resolvedNote = approvedLeave && !"PRESENT".equals(m.status())
                    ? "Đơn xin nghỉ đã được GVCN duyệt" : normalizeNote(m.note());
            rec.setStatus(resolvedStatus);
            rec.setNote(resolvedNote);
            rec.setSubjectName(slot.getSubjectName());
            rec.setPeriodNo(slot.getPeriodNo());
            saved.add(records.save(rec));

            if (!Objects.equals(previousStatus, resolvedStatus)) {
                notifyAttendanceStatus(rec);
            }
        }
        sessionAccesses.findBySlotIdAndSessionDate(slot.getId(), req.date()).ifPresent(access -> {
            if (access.getUnlockReason() != null && access.getLateAttendanceSavedAt() == null) {
                access.setLateAttendanceSavedAt(java.time.Instant.now());
                sessionAccesses.save(access);
            }
        });
        return saved;
    }

    private TimetableSlot requireSlot(String slotId) {
        TimetableSlot slot = timetable.findSlot(slotId);
        if (slot == null) throw ApiException.notFound("Tiết học");
        return slot;
    }

    private void validateOccurrence(TimetableSlot slot, LocalDate date) {
        AttendanceSessionStatus status = sessionStatus(slot, date, ZonedDateTime.now(SCHOOL_ZONE));
        if (!status.canMark()) throw ApiException.badRequest(status.message());
    }

    private AttendanceSessionStatus sessionStatus(TimetableSlot slot, LocalDate date, ZonedDateTime now) {
        Announcement holiday = notifications.schoolHolidayOn(date).orElse(null);
        if (holiday != null) {
            return status("HOLIDAY", false, false, "Không cần điểm danh ngày nghỉ: " + holiday.getTitle(),
                    slot, date, null);
        }
        if (!isScheduledOccurrence(slot, date)) {
            return status("INVALID", false, false,
                    "Ngày đã chọn không thuộc lịch học của tiết này hoặc nằm ngoài học kỳ", slot, date, null);
        }

        LocalTime start = parseTime(slot.getStartTime());
        LocalTime end = parseTime(slot.getEndTime());
        if (start == null || end == null) {
            return status("INVALID", false, false, "Tiết học chưa được cấu hình thời gian hợp lệ", slot, date, null);
        }

        AttendanceSessionAccess access = sessionAccesses.findBySlotIdAndSessionDate(slot.getId(), date).orElse(null);
        boolean hasAttendance = records.existsBySlotIdAndDate(slot.getId(), date);
        if (date.isAfter(now.toLocalDate()) || (date.equals(now.toLocalDate()) && now.toLocalTime().isBefore(start))) {
            return status("UPCOMING", false, false, "Tiết học chưa bắt đầu", slot, date, access);
        }
        if (date.equals(now.toLocalDate()) && now.toLocalTime().isBefore(end)) {
            return status("OPEN", true, false, "Tiết học đang diễn ra — sổ điểm danh đang mở", slot, date, access);
        }
        if (hasAttendance) {
            String state = access != null && access.getUnlockReason() != null ? "COMPLETED_LATE" : "COMPLETED";
            return status(state, true, false,
                    "COMPLETED_LATE".equals(state) ? "Điểm danh muộn đã được ghi nhận" : "Sổ điểm danh đã được lưu",
                    slot, date, access);
        }
        if (access != null && access.getUnlockReason() != null) {
            return status("LATE_UNLOCKED", true, false,
                    "Đã mở khóa điểm danh muộn — vui lòng hoàn tất và lưu sổ", slot, date, access);
        }
        return status("LOCKED_REASON_REQUIRED", false, true,
                "Tiết học đã kết thúc. Cần ghi rõ lý do quên điểm danh để mở khóa", slot, date, access);
    }

    private AttendanceSessionStatus status(String state, boolean canMark, boolean requiresReason, String message,
                                           TimetableSlot slot, LocalDate date, AttendanceSessionAccess access) {
        return new AttendanceSessionStatus(state, canMark, requiresReason, message, date,
                slot.getStartTime(), slot.getEndTime(), access == null ? null : access.getUnlockReason(),
                access == null ? null : access.getUnlockedAt());
    }

    private boolean isScheduledOccurrence(TimetableSlot slot, LocalDate date) {
        if (date == null) return false;
        Semester semester = structure.getSemester(slot.getSemesterId());
        if ((semester.getStartDate() != null && date.isBefore(semester.getStartDate()))
                || (semester.getEndDate() != null && date.isAfter(semester.getEndDate()))) return false;
        return date.getDayOfWeek().name().substring(0, 3).equalsIgnoreCase(slot.getDayOfWeek());
    }

    private LocalTime parseTime(String value) {
        if (value == null || value.isBlank()) return null;
        try { return LocalTime.parse(value.trim()); }
        catch (DateTimeParseException ignored) { return null; }
    }

    private AttendanceSessionAccess accessFor(TimetableSlot slot, LocalDate date) {
        return sessionAccesses.findBySlotIdAndSessionDate(slot.getId(), date)
                .orElseGet(() -> AttendanceSessionAccess.builder()
                        .id(Ids.gen("ats")).slotId(slot.getId()).sessionDate(date)
                        .teacherId(slot.getTeacherId()).classId(slot.getClassId()).build());
    }

    private String normalizeNote(String note) {
        if (note == null || note.isBlank()) return null;
        return note.trim();
    }

    private String normalizeSubject(String subject) {
        if (subject == null || subject.isBlank()) return null;
        return subject.trim();
    }

    private boolean matchesSubject(String mainSubject, String subjectId, String subjectName) {
        return (subjectId != null && subjectId.trim().equalsIgnoreCase(mainSubject))
                || (subjectName != null && subjectName.trim().equalsIgnoreCase(mainSubject));
    }

    private void notifyAttendanceStatus(AttendanceRecord rec) {
        String studentName = users.fullNameOf(rec.getStudentId());
        String priority = attendancePriority(rec.getStatus());
        String title = isAbsentOrLate(rec.getStatus()) ? "Cảnh báo chuyên cần" : "Điểm danh đã cập nhật";
        String detail = String.format("%s môn %s, tiết %d ngày %s%s",
                statusLabel(rec.getStatus()),
                rec.getSubjectName() == null ? "" : rec.getSubjectName(),
                rec.getPeriodNo() == null ? 0 : rec.getPeriodNo(),
                rec.getDate(),
                rec.getNote() == null ? "" : ". Ghi chú: " + rec.getNote());
        notifications.notifyUser(rec.getStudentId(), "ATTENDANCE", priority,
                title, "Bạn " + detail, "ATTENDANCE", rec.getId());
        notifications.notifyParentsOfStudent(rec.getStudentId(), "ATTENDANCE", priority,
                title, (studentName == null ? "Học sinh" : studentName) + " " + detail,
                "ATTENDANCE", rec.getId());
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

    private String attendancePriority(String status) {
        if (status != null && status.startsWith("ABSENT")) return "URGENT";
        if ("LATE".equals(status)) return "IMPORTANT";
        return "NORMAL";
    }

    private String statusLabel(String status) {
        return switch (status) {
            case "PRESENT" -> "có mặt";
            case "ABSENT_UNEXCUSED" -> "vắng không phép";
            case "ABSENT_EXCUSED" -> "vắng có phép";
            case "LATE" -> "đi muộn";
            default -> status;
        };
    }
}
