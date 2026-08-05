package com.sse.app.academic.timetable;

import com.sse.app.academic.timetable.TimetableDtos.CreateSlotRequest;
import com.sse.app.academic.timetable.TimetableDtos.GenerateScheduleRequest;
import com.sse.app.academic.timetable.TimetableDtos.GenerationResult;
import com.sse.app.academic.timetable.TimetableDtos.GenerationReadiness;
import com.sse.app.academic.timetable.TimetableDtos.MoveDraftSlotRequest;
import com.sse.app.academic.timetable.TimetableDtos.ScheduleValidation;
import com.sse.app.common.ApiException;
import com.sse.app.identity.User;
import com.sse.app.identity.UserService;
import com.sse.app.report.AcademicEnrollmentService;
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
    private final AutomaticTimetableService automatic;
    private final AcademicEnrollmentService enrollments;

    public TimetableController(TimetableService timetable, UserService users,
                               AutomaticTimetableService automatic,
                               AcademicEnrollmentService enrollments) {
        this.timetable = timetable;
        this.users = users;
        this.automatic = automatic;
        this.enrollments = enrollments;
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
            classId = enrollments.classIdForSemester(me.id(), semesterId)
                    .orElse(null);
            if (classId == null) return List.of();
            teacherId = null;
        }
        return timetable.listEffective(classId, teacherId, semesterId, dayOfWeek);
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
    public List<TimetableSlot> myTimetable(
            @RequestParam(required = false) String semesterId) {
        CurrentUser me = CurrentUserHolder.require();
        if (me.isTeacher()) {
            return timetable.listEffective(null, me.id(), semesterId, null);
        }
        String classId = enrollments.classIdForSemester(me.id(), semesterId)
                .orElse(null);
        if (classId != null) {
            return timetable.listEffective(classId, null, semesterId, null);
        }
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
        String classId = enrollments.classIdForSemester(studentId, semesterId)
                .orElse(null);
        if (classId == null) {
            return List.of();
        }
        return timetable.listEffective(classId, null, semesterId, dayOfWeek);
    }

    @GetMapping("/timetable/schedules")
    public List<TimetableSchedule> schedules(
            @RequestParam(required = false) String semesterId) {
        CurrentUserHolder.requirePermission("ACADEMIC_TIMETABLE_READ");
        return automatic.listSchedules(semesterId);
    }

    @PostMapping("/timetable/schedules/generate")
    public GenerationResult generate(
            @Valid @RequestBody GenerateScheduleRequest request) {
        CurrentUser actor = CurrentUserHolder.require();
        CurrentUserHolder.requirePermission("ACADEMIC_TIMETABLE_MANAGE");
        return automatic.generate(request, actor.id());
    }

    @GetMapping("/timetable/schedules/generation-readiness")
    public GenerationReadiness generationReadiness(
            @RequestParam String academicYearId,
            @RequestParam String semesterId,
            @RequestParam(required = false) String scopeGradeLevel) {
        CurrentUserHolder.requirePermission("ACADEMIC_TIMETABLE_READ");
        return automatic.generationReadiness(
                academicYearId, semesterId, scopeGradeLevel);
    }

    @GetMapping("/timetable/schedules/{scheduleId}/slots")
    public List<TimetableDraftSlot> draftSlots(
            @PathVariable String scheduleId,
            @RequestParam(required = false) String classId) {
        CurrentUserHolder.requirePermission("ACADEMIC_TIMETABLE_READ");
        return automatic.listDraftSlots(scheduleId, classId);
    }

    @GetMapping("/timetable/schedules/{scheduleId}/validation")
    public ScheduleValidation validate(@PathVariable String scheduleId) {
        CurrentUserHolder.requirePermission("ACADEMIC_TIMETABLE_READ");
        return automatic.validate(scheduleId);
    }

    @DeleteMapping("/timetable/schedules/{scheduleId}")
    public void deleteDraft(@PathVariable String scheduleId) {
        CurrentUserHolder.requirePermission("ACADEMIC_TIMETABLE_MANAGE");
        automatic.deleteDraft(scheduleId);
    }

    @PutMapping("/timetable/schedules/{scheduleId}/slots/{slotId}")
    public TimetableDraftSlot move(
            @PathVariable String scheduleId,
            @PathVariable String slotId,
            @Valid @RequestBody MoveDraftSlotRequest request) {
        CurrentUserHolder.requirePermission("ACADEMIC_TIMETABLE_MANAGE");
        return automatic.move(scheduleId, slotId, request);
    }

    @PostMapping("/timetable/schedules/{scheduleId}/publish")
    public TimetableSchedule publish(@PathVariable String scheduleId) {
        CurrentUser actor = CurrentUserHolder.require();
        CurrentUserHolder.requirePermission("ACADEMIC_TIMETABLE_PUBLISH");
        return automatic.publish(scheduleId, actor.id());
    }
}
