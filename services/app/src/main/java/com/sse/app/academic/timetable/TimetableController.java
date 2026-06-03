package com.sse.app.academic.timetable;

import com.sse.app.academic.timetable.TimetableDtos.CreateSlotRequest;
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

    @GetMapping("/timetableSlots")
    public List<TimetableSlot> list(@RequestParam(required = false) String classId,
                                    @RequestParam(required = false) String teacherId,
                                    @RequestParam(required = false) String semesterId,
                                    @RequestParam(required = false) String dayOfWeek) {
        CurrentUserHolder.require();
        return timetable.list(classId, teacherId, semesterId, dayOfWeek);
    }

    @PostMapping("/timetableSlots")
    public TimetableSlot create(@Valid @RequestBody CreateSlotRequest r) {
        CurrentUserHolder.requireRole("ADMIN");
        return timetable.create(r);
    }

    @DeleteMapping("/timetableSlots/{id}")
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
}
