package com.sse.app.academic.timetable;

import com.sse.app.academic.timetable.TimetableDtos.CreateSlotRequest;
import com.sse.app.identity.User;
import com.sse.app.identity.UserService;
import com.sse.app.security.CurrentUser;
import com.sse.app.security.CurrentUserHolder;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import com.sse.app.academic.structure.SchoolClass;
import com.sse.app.academic.structure.StructureService;
import com.sse.app.common.ApiException;

/** A3/B2/C2: TKB lớp + TKB cá nhân. Route /timetableSlots khớp json-server. */
@RestController
public class TimetableController {

    private final TimetableService timetable;
    private final UserService users;
    private final StructureService structure;
    private final TeachingAssignmentService teachingAssignments;
    private final AutomaticTimetableService automaticTimetable;

    public TimetableController(TimetableService timetable, UserService users, StructureService structure,
                               TeachingAssignmentService teachingAssignments,
                               AutomaticTimetableService automaticTimetable) {
        this.timetable = timetable;
        this.users = users;
        this.structure = structure;
        this.teachingAssignments = teachingAssignments;
        this.automaticTimetable = automaticTimetable;
    }

    @GetMapping("/timetableSlots")
    public List<TimetableSlot> list(@RequestParam(required = false) String classId,
                                    @RequestParam(required = false) String teacherId,
                                    @RequestParam(required = false) String semesterId,
                                    @RequestParam(required = false) String dayOfWeek) {
        CurrentUser current = CurrentUserHolder.require();
        assertCanView(current, classId, teacherId);
        return timetable.list(classId, teacherId, semesterId, dayOfWeek);
    }

    @PostMapping("/timetableSlots")
    public TimetableSlot create(@Valid @RequestBody CreateSlotRequest r) {
        CurrentUserHolder.requireRole("ADMIN", "ACADEMIC_STAFF");
        return timetable.create(r);
    }

    @PostMapping("/timetableSlots/auto-plan")
    public TimetableDtos.AutoTimetablePlan autoPlan(
            @Valid @RequestBody TimetableDtos.AutoTimetableRequest request) {
        CurrentUserHolder.requireRole("ADMIN", "ACADEMIC_STAFF");
        return automaticTimetable.plan(request);
    }

    @DeleteMapping("/timetableSlots/{id}")
    public void delete(@PathVariable String id) {
        CurrentUserHolder.requireRole("ADMIN", "ACADEMIC_STAFF");
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

    @PutMapping("/timetableSlots/{id}")
    public TimetableSlot update(@PathVariable String id, @Valid @RequestBody CreateSlotRequest r) {
        CurrentUserHolder.requireRole("ADMIN", "ACADEMIC_STAFF");
        return timetable.update(id, r);
    }

    @GetMapping("/me/teaching-classes")
    public List<SchoolClass> teachingClasses() {
        CurrentUser me = CurrentUserHolder.require();
        CurrentUserHolder.requireRole("TEACHER");
        java.util.LinkedHashSet<String> ids = new java.util.LinkedHashSet<>();
        teachingAssignments.assignmentsOfTeacher(me.id()).forEach(item -> ids.add(item.getClassId()));
        structure.classesOfHomeroom(me.id()).forEach(schoolClass -> ids.add(schoolClass.getId()));
        return ids.stream().map(structure::getClass).toList();
    }

    private void assertCanView(CurrentUser current, String classId, String teacherId) {
        if (current.canManageAcademics()) return;
        if (current.isTeacher()) {
            if (teacherId != null && teacherId.equals(current.id())) return;
            if (classId != null) {
                boolean teaches = teachingAssignments.isAssigned(current.id(), classId);
                boolean homeroom = current.id().equals(structure.getClass(classId).getHomeroomTeacherId());
                if (teaches || homeroom) return;
            }
            throw ApiException.forbidden("Không có quyền xem thời khóa biểu ngoài lớp được phân công");
        }
        if (current.isStudent()) {
            User student = users.getById(current.id());
            if (classId != null && classId.equals(student.getClassId())) return;
            throw ApiException.forbidden("Học sinh chỉ được xem thời khóa biểu lớp mình");
        }
        if (current.isParent()) {
            boolean childClass = classId != null && users.childrenOf(current.id()).stream()
                    .anyMatch(child -> classId.equals(child.classId()));
            if (childClass) return;
            throw ApiException.forbidden("Phụ huynh chỉ được xem thời khóa biểu của con");
        }
        throw ApiException.forbidden("Không có quyền xem thời khóa biểu");
    }
}
