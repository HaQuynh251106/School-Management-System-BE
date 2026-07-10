package com.sse.app.academic.attendance;

import com.sse.app.academic.attendance.AttendanceDtos.BulkAttendanceRequest;
import com.sse.app.common.ApiException;
import com.sse.app.identity.UserService;
import com.sse.app.security.CurrentUser;
import com.sse.app.security.CurrentUserHolder;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

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
        }
        return attendance.list(studentId, classId, slotId, date);
    }

    @GetMapping("/students/{studentId}/attendance")
    public List<AttendanceRecord> studentAttendance(@PathVariable String studentId,
                                                    @RequestParam(required = false) String classId,
                                                    @RequestParam(required = false) String slotId,
                                                    @RequestParam(required = false)
                                                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        CurrentUser me = CurrentUserHolder.require();
        if (me.isStudent() && !me.id().equals(studentId)) throw ApiException.forbidden("Không đủ quyền");
        if (me.isParent()) users.assertParentOf(me.id(), studentId);
        return attendance.list(studentId, classId, slotId, date);
    }

    @PostMapping("/attendance/bulk")
    public List<AttendanceRecord> bulk(@Valid @RequestBody BulkAttendanceRequest req) {
        CurrentUser me = CurrentUserHolder.require();
        CurrentUserHolder.requireRole("TEACHER", "ADMIN");
        return attendance.bulkMark(req, me.id(), me.isTeacher());
    }
}
