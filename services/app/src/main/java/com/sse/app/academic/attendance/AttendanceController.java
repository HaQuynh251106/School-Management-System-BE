package com.sse.app.academic.attendance;

import com.sse.app.academic.attendance.AttendanceDtos.BulkAttendanceRequest;
import com.sse.app.academic.attendance.AttendanceDtos.AttendanceDayStatus;
import com.sse.app.academic.attendance.AttendanceDtos.AttendanceSessionStatus;
import com.sse.app.academic.attendance.AttendanceDtos.UnlockAttendanceRequest;
import com.sse.app.common.ApiException;
import com.sse.app.identity.UserService;
import com.sse.app.security.CurrentUser;
import com.sse.app.security.CurrentUserHolder;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import com.sse.app.academic.leave.LeaveRequest;

/** B3/C3/D2: Sổ điểm danh. Route /attendance khớp json-server. */
@RestController
public class AttendanceController {

    private final AttendanceService attendance;
    private final UserService users;

    public AttendanceController(AttendanceService attendance, UserService users) {
        this.attendance = attendance;
        this.users = users;
    }

    @GetMapping("/attendance")
    public List<AttendanceRecord> list(@RequestParam(required = false) String studentId,
                                       @RequestParam(required = false) String classId,
                                       @RequestParam(required = false) String slotId,
                                       @RequestParam(required = false)
                                       @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        CurrentUser me = CurrentUserHolder.require();
        if (me.isStudent()) {
            studentId = me.id();                       // HS chỉ xem của chính mình
        } else if (me.isParent()) {
            if (studentId == null) throw ApiException.badRequest("Thiếu studentId (chọn con)");
            users.assertParentOf(me.id(), studentId);  // D1/D2 kiểm soát quyền
        } else if (me.isTeacher()) {
            if (slotId == null || slotId.isBlank()) {
                throw ApiException.badRequest("Giáo viên cần chọn tiết học để xem sổ điểm danh");
            }
            attendance.assertCanManageSlot(me, slotId, date);
        }
        return attendance.list(studentId, classId, slotId, date);
    }

    @PostMapping("/attendance/bulk")
    public List<AttendanceRecord> bulk(@Valid @RequestBody BulkAttendanceRequest req) {
        CurrentUserHolder.requireRole("TEACHER", "ADMIN");
        return attendance.bulkMark(req, CurrentUserHolder.require());
    }

    @GetMapping("/attendance/day-status")
    public AttendanceDayStatus dayStatus(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        CurrentUserHolder.require();
        return attendance.dayStatus(date);
    }

    @GetMapping("/attendance/session-status")
    public AttendanceSessionStatus sessionStatus(
            @RequestParam String slotId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        CurrentUserHolder.requireRole("TEACHER", "ADMIN");
        CurrentUser me = CurrentUserHolder.require();
        attendance.assertCanManageSlot(me, slotId, date);
        return attendance.sessionStatus(slotId, date);
    }

    @GetMapping("/attendance/approved-leaves")
    public List<LeaveRequest> approvedLeaves(
            @RequestParam String slotId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        CurrentUserHolder.requireRole("TEACHER", "ADMIN");
        return attendance.approvedLeaves(slotId, date, CurrentUserHolder.require());
    }

    @PostMapping("/attendance/unlock")
    public AttendanceSessionStatus unlock(@Valid @RequestBody UnlockAttendanceRequest request) {
        CurrentUserHolder.requireRole("TEACHER");
        CurrentUser me = CurrentUserHolder.require();
        return attendance.unlockLateAttendance(request, me);
    }
}
