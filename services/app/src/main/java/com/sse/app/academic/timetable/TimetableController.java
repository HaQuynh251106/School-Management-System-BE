package com.sse.app.academic.timetable;

import com.sse.app.academic.timetable.TimetableDtos.CreateSlotRequest;
import com.sse.app.common.ApiException;
import com.sse.app.identity.User;
import com.sse.app.identity.UserService;
import com.sse.app.security.CurrentUser;
import com.sse.app.security.CurrentUserHolder;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** A3/B2/C2: TKB lớp + TKB cá nhân. Route /timetableSlots khớp json-server. */
@RestController
public class TimetableController {

    private final TimetableService timetable;
    private final UserService users;

    public TimetableController(TimetableService timetable, UserService users) {
        this.timetable = timetable;
        this.users = users;
    }

    @GetMapping({"/timetableSlots", "/timetable/slots"})
    public List<TimetableSlot> list(@RequestParam(required = false) String classId,
                                    @RequestParam(required = false) String teacherId,
                                    @RequestParam(required = false) String semesterId,
                                    @RequestParam(required = false) String dayOfWeek) {
        CurrentUser me = CurrentUserHolder.require();
        if (me.isParent()) {
            throw ApiException.forbidden("Phụ huynh xem TKB qua /students/{studentId}/timetable");
        }
        if (me.isTeacher()) {
            teacherId = me.id();
        }
        if (me.isStudent()) {
            User u = users.getById(me.id());
            if (u.getClassId() == null || u.getClassId().isBlank()) return List.of();
            classId = u.getClassId();
            teacherId = null;
        }
        return timetable.list(classId, teacherId, semesterId, dayOfWeek);
    }

    @PostMapping({"/timetableSlots", "/timetable/slots"})
    public TimetableSlot create(@Valid @RequestBody CreateSlotRequest r) {
        CurrentUserHolder.requireRole("ADMIN");
        return timetable.create(r);
    }

    @DeleteMapping({"/timetableSlots/{id}", "/timetable/slots/{id}"})
    public void delete(@PathVariable String id) {
        CurrentUserHolder.requireRole("ADMIN");
        timetable.delete(id);
    }

    /** B2/C2: TKB của chính người đang đăng nhập (HS theo lớp, GV theo mã GV). */
    @GetMapping("/me/timetable")
    public List<TimetableSlot> myTimetable() {
        CurrentUser me = CurrentUserHolder.require();
        if (me.isTeacher()) return timetable.list(null, me.id(), null, null);
        User u = users.getById(me.id());
        if (u.getClassId() != null) return timetable.list(u.getClassId(), null, null, null);
        return List.of();
    }

    /** D2: phụ huynh xem TKB của đúng con mình; học sinh chỉ xem chính mình. */
    @GetMapping("/students/{studentId}/timetable")
    public List<TimetableSlot> studentTimetable(@PathVariable String studentId,
                                                @RequestParam(required = false) String semesterId,
                                                @RequestParam(required = false) String dayOfWeek) {
        CurrentUser me = CurrentUserHolder.require();
        if (me.isStudent() && !me.id().equals(studentId)) {
            throw ApiException.forbidden("Không đủ quyền");
        }
        if (me.isParent()) {
            users.assertParentOf(me.id(), studentId);
        }

        User student = users.getById(studentId);
        if (!"STUDENT".equals(student.getRole())) {
            throw ApiException.badRequest("Không phải học sinh");
        }
        if (student.getClassId() == null || student.getClassId().isBlank()) {
            return List.of();
        }
        return timetable.list(student.getClassId(), null, semesterId, dayOfWeek);
    }
}
