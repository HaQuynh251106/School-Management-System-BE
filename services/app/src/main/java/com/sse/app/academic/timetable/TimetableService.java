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

    /**
     * Lịch dành cho Giáo viên/Học sinh chỉ gồm các slot thuộc phiên bản đã phát
     * hành. Các slot null publishedPlanId là workspace nháp của Admin/Giáo vụ.
     */
    public List<TimetableSlot> publishedAudience(String classId, String teacherId) {
        return publishedAudience(classId, teacherId, null, null);
    }

    public List<TimetableSlot> publishedAudience(String classId, String teacherId,
                                                  String semesterId, String dayOfWeek) {
        List<TimetableSlot> base = classId != null
                ? slots.findByClassId(classId)
                : slots.findByTeacherId(teacherId);
        return base.stream()
                .filter(slot -> slot.getPublishedPlanId() != null
                        && !slot.getPublishedPlanId().isBlank())
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
        assertDraftSlot(slot);
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
        teachingAssignments.assertCanScheduleFromPublishedPlan(assignment, ignoredSlotId);
        // Kiểm tra điều kiện nghiệp vụ cốt lõi trước điều kiện phòng học để thông báo
        // đúng việc người dùng cần xử lý (phân công giáo viên) thay vì lỗi thứ cấp.
        structure.requireRoomForClass(request.roomCode(), request.classId());
        return new SlotContext(classCode, schoolClass.getStudyShift(), subjectName, teacher);
    }

    private void checkConflicts(CreateSlotRequest request, String ignoredSlotId) {
        for (TimetableSlot existing : slots.findByDayOfWeek(request.dayOfWeek().toUpperCase())) {
            if (existing.getId().equals(ignoredSlotId)) continue;
            if (differentSemester(request.semesterId(), existing.getSemesterId())) continue;
            if (!overlaps(request, existing)) continue;
            if (request.classId().equals(existing.getClassId())) {
                throw ApiException.conflict("Lớp đã có tiết khác trùng khung giờ "
                        + request.startTime() + "–" + request.endTime() + " vào " + vietnameseDay(request.dayOfWeek()));
            }
            if (request.teacherId().equals(existing.getTeacherId())) {
                String classCode = structure.getClass(existing.getClassId()).getCode();
                throw ApiException.conflict("Giáo viên đã kín lịch do trùng khung giờ: đang dạy "
                        + existing.getSubjectName() + " tại lớp " + classCode + " ("
                        + existing.getStartTime() + "–" + existing.getEndTime() + ")");
            }
            if (request.roomCode() != null && !request.roomCode().isBlank()
                    && request.roomCode().equals(existing.getRoomCode())) {
                throw ApiException.conflict("Phòng " + request.roomCode() + " đang được sử dụng trong khung giờ này");
            }
        }
    }

    private boolean overlaps(CreateSlotRequest request, TimetableSlot existing) {
        LocalTime requestedStart = parseTime(request.startTime());
        LocalTime requestedEnd = parseTime(request.endTime());
        LocalTime existingStart = parseTime(existing.getStartTime());
        LocalTime existingEnd = parseTime(existing.getEndTime());
        if (requestedStart == null || requestedEnd == null || existingStart == null || existingEnd == null) {
            return request.periodNo().equals(existing.getPeriodNo());
        }
        return requestedStart.isBefore(existingEnd) && existingStart.isBefore(requestedEnd);
    }

    private LocalTime parseTime(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return LocalTime.parse(value.trim());
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }

    private boolean differentSemester(String left, String right) {
        return left != null && right != null && !left.equals(right);
    }

    public void delete(String id) {
        TimetableSlot slot = slots.findById(id).orElseThrow(() -> ApiException.notFound("Tiết học"));
        assertDraftSlot(slot);
        structure.assertSemesterWritable(slot.getSemesterId());
        slots.delete(slot);
    }

    private void assertDraftSlot(TimetableSlot slot) {
        if (slot.getPublishedPlanId() != null && !slot.getPublishedPlanId().isBlank()) {
            throw ApiException.conflict(
                    "Lịch đã phát hành chỉ được thay đổi bằng phiên bản nháp mới rồi phát hành lại");
        }
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

    private String vietnameseDay(String day) {
        return switch (day == null ? "" : day.toUpperCase()) {
            case "MON" -> "thứ Hai";
            case "TUE" -> "thứ Ba";
            case "WED" -> "thứ Tư";
            case "THU" -> "thứ Năm";
            case "FRI" -> "thứ Sáu";
            case "SAT" -> "thứ Bảy";
            case "SUN" -> "Chủ nhật";
            default -> day;
        };
    }

    private void attachClassCode(TimetableSlot slot) {
        if (slot.getClassId() != null) {
            var schoolClass = structure.getClass(slot.getClassId());
            slot.setClassCode(schoolClass.getCode());
            slot.setStudyShift(schoolClass.getStudyShift());
        }
    }

    private record SlotContext(String classCode, String studyShift, String subjectName, User teacher) {}
}
