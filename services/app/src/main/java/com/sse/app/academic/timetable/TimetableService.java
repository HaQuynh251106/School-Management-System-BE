package com.sse.app.academic.timetable;

import com.sse.app.academic.structure.StructureService;
import com.sse.app.academic.timetable.TimetableDtos.CreateSlotRequest;
import com.sse.app.common.ApiException;
import com.sse.app.common.Ids;
import com.sse.app.identity.User;
import com.sse.app.identity.UserService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.List;

/** Xếp thời khóa biểu và xử lý xung đột lớp, giáo viên, phòng học. */
@Service
public class TimetableService {
    private final TimetableRepository slots;
    private final StructureService structure;
    private final UserService users;
    private final TeachingAssignmentService teachingAssignments;

    public TimetableService(TimetableRepository slots, StructureService structure, UserService users,
                            TeachingAssignmentService teachingAssignments) {
        this.slots = slots;
        this.structure = structure;
        this.users = users;
        this.teachingAssignments = teachingAssignments;
    }

    public List<TimetableSlot> list(String classId, String teacherId, String semesterId, String dayOfWeek) {
        List<TimetableSlot> base;
        if (classId != null) base = slots.findByClassId(classId);
        else if (teacherId != null) base = slots.findByTeacherId(teacherId);
        else if (semesterId != null) base = slots.findBySemesterId(semesterId);
        else base = slots.findAll();
        return base.stream()
                .filter(slot -> semesterId == null || semesterId.equals(slot.getSemesterId()))
                .filter(slot -> dayOfWeek == null || dayOfWeek.equalsIgnoreCase(slot.getDayOfWeek()))
                .peek(this::attachClassCode)
                .sorted((left, right) -> {
                    int day = dayIndex(left.getDayOfWeek()) - dayIndex(right.getDayOfWeek());
                    return day != 0 ? day : Integer.compare(left.getPeriodNo(), right.getPeriodNo());
                })
                .toList();
    }

    @Transactional
    public TimetableSlot create(CreateSlotRequest request) {
        SlotContext context = validateSlot(request, null);
        checkConflicts(request, null);
        return slots.save(TimetableSlot.builder()
                .id(request.id() == null || request.id().isBlank() ? Ids.gen("tt") : request.id())
                .classId(request.classId())
                .classCode(context.classCode())
                .studyShift(context.studyShift())
                .subjectId(request.subjectId())
                .subjectName(context.subjectName())
                .teacherId(request.teacherId())
                .teacherName(context.teacher().getFullName())
                .roomCode(request.roomCode())
                .dayOfWeek(request.dayOfWeek().toUpperCase())
                .periodNo(request.periodNo())
                .startTime(request.startTime())
                .endTime(request.endTime())
                .semesterId(request.semesterId())
                .build());
    }

    @Transactional
    public TimetableSlot update(String id, CreateSlotRequest request) {
        TimetableSlot slot = slots.findById(id).orElseThrow(() -> ApiException.notFound("Tiết học"));
        SlotContext context = validateSlot(request, id);
        checkConflicts(request, id);
        slot.setClassId(request.classId());
        slot.setClassCode(context.classCode());
        slot.setStudyShift(context.studyShift());
        slot.setSubjectId(request.subjectId());
        slot.setSubjectName(context.subjectName());
        slot.setTeacherId(request.teacherId());
        slot.setTeacherName(context.teacher().getFullName());
        slot.setRoomCode(request.roomCode());
        slot.setDayOfWeek(request.dayOfWeek().toUpperCase());
        slot.setPeriodNo(request.periodNo());
        slot.setStartTime(request.startTime());
        slot.setEndTime(request.endTime());
        slot.setSemesterId(request.semesterId());
        return slots.save(slot);
    }

    private SlotContext validateSlot(CreateSlotRequest request, String ignoredSlotId) {
        LocalTime start = parseTime(request.startTime());
        LocalTime end = parseTime(request.endTime());
        if (start == null || end == null || !start.isBefore(end)) {
            throw ApiException.badRequest("Khung giờ tiết học không hợp lệ");
        }
        var schoolClass = structure.getClass(request.classId());
        String classCode = schoolClass.getCode();
        structure.assertSemesterWritable(request.semesterId());
        String subjectName = structure.requireSubjectName(request.subjectId());
        User teacher = users.getById(request.teacherId());
        if (!"TEACHER".equals(teacher.getRole()) || !"ACTIVE".equals(teacher.getStatus())) {
            throw ApiException.badRequest("Chỉ có thể xếp lịch cho giáo viên đang hoạt động");
        }
        TeachingAssignment assignment = teachingAssignments.requireForSlot(
                request.classId(), request.subjectId(), request.teacherId(), request.semesterId());
        teachingAssignments.assertCanSchedule(assignment, ignoredSlotId);
        // Kiểm tra điều kiện nghiệp vụ cốt lõi trước điều kiện phòng học để thông báo
        // đúng việc người dùng cần xử lý (phân công giáo viên) thay vì lỗi thứ cấp.
        structure.requireRoomForClass(request.roomCode(), request.classId());
        return new SlotContext(classCode, schoolClass.getStudyShift(), subjectName, teacher);
    }

    private void checkConflicts(CreateSlotRequest request, String ignoredSlotId) {
        TimetableRulePolicy.assertCanAdd(view(request), slots.findBySemesterId(request.semesterId()).stream()
                .map(this::view).toList(), ignoredSlotId);
    }

    private LocalTime parseTime(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return LocalTime.parse(value.trim());
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }

    public void delete(String id) {
        TimetableSlot slot = slots.findById(id).orElseThrow(() -> ApiException.notFound("Tiết học"));
        structure.assertSemesterWritable(slot.getSemesterId());
        slots.delete(slot);
    }

    public TimetableSlot findSlot(String id) {
        TimetableSlot slot = id == null ? null : slots.findById(id).orElse(null);
        if (slot != null) attachClassCode(slot);
        return slot;
    }

    public boolean teacherTeachesClass(String teacherId, String classId) {
        return teachingAssignments.isAssigned(teacherId, classId);
    }

    /** Chèn dữ liệu mẫu và tự tạo phân công tương ứng. */
    public void seedSlots(List<TimetableSlot> list) {
        TimetableRulePolicy.Validation validation = TimetableRulePolicy.validate(list.stream().map(this::view).toList());
        if (!validation.valid()) throw ApiException.conflict(validation.summary());
        slots.saveAll(list);
        teachingAssignments.seedFromSlots(list);
    }

    private int dayIndex(String day) {
        return switch (day == null ? "" : day.toUpperCase()) {
            case "MON" -> 1;
            case "TUE" -> 2;
            case "WED" -> 3;
            case "THU" -> 4;
            case "FRI" -> 5;
            case "SAT" -> 6;
            case "SUN" -> 7;
            default -> 9;
        };
    }

    private void attachClassCode(TimetableSlot slot) {
        if (slot.getClassId() != null) {
            var schoolClass = structure.getClass(slot.getClassId());
            slot.setClassCode(schoolClass.getCode());
            slot.setStudyShift(schoolClass.getStudyShift());
        }
    }

    private TimetableRulePolicy.SlotView view(CreateSlotRequest request) {
        return new TimetableRulePolicy.SlotView(request.id(), request.classId(), request.subjectId(),
                request.teacherId(), request.roomCode(), request.dayOfWeek(), request.periodNo(),
                request.startTime(), request.endTime(), request.semesterId());
    }

    private TimetableRulePolicy.SlotView view(TimetableSlot slot) {
        return new TimetableRulePolicy.SlotView(slot.getId(), slot.getClassId(), slot.getSubjectId(),
                slot.getTeacherId(), slot.getRoomCode(), slot.getDayOfWeek(), slot.getPeriodNo(),
                slot.getStartTime(), slot.getEndTime(), slot.getSemesterId());
    }

    private record SlotContext(String classCode, String studyShift, String subjectName, User teacher) {}
}
