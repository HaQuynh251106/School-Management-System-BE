package com.sse.app.academic.teaching;

import com.sse.app.academic.structure.SchoolClass;
import com.sse.app.academic.structure.Subject;
import com.sse.app.academic.structure.StructureService;
import com.sse.app.academic.teaching.TeachingDtos.*;
import com.sse.app.academic.timetable.TimetableSlot;
import com.sse.app.academic.timetable.TimetableService;
import com.sse.app.common.ApiException;
import com.sse.app.common.Ids;
import com.sse.app.identity.User;
import com.sse.app.identity.UserDto;
import com.sse.app.identity.UserService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.text.Normalizer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Service
public class TeachingAssignmentService {

    private final TeachingAssignmentRepository assignments;
    private final StructureService structure;
    private final UserService users;
    private final TimetableService timetable;

    public TeachingAssignmentService(TeachingAssignmentRepository assignments,
                                     StructureService structure,
                                     UserService users,
                                     TimetableService timetable) {
        this.assignments = assignments;
        this.structure = structure;
        this.users = users;
        this.timetable = timetable;
    }

    public List<TeachingAssignmentDto> list(String teacherId, String classId, String subjectId,
                                           String semesterId, String status) {
        return list(teacherId, classId, subjectId, semesterId, status, null, null);
    }

    public List<TeachingAssignmentDto> list(String teacherId, String classId, String subjectId,
                                           String semesterId, String status,
                                           String dayOfWeek, Integer periodNo) {
        String targetStatus = status == null || status.isBlank() ? "ACTIVE" : status;
        List<TeacherClassSubject> base;
        if (teacherId != null && !teacherId.isBlank()) {
            base = assignments.findByTeacherIdAndStatus(teacherId, targetStatus);
        } else if (classId != null && !classId.isBlank()) {
            base = assignments.findByClassIdAndStatus(classId, targetStatus);
        } else {
            base = assignments.findAll().stream()
                    .filter(a -> targetStatus.equals(a.getStatus()))
                    .toList();
        }
        List<TeacherClassSubject> activeAssignments = assignments.findAll().stream()
                .filter(a -> "ACTIVE".equals(a.getStatus()))
                .toList();
        List<TimetableSlot> slots = timetable.allSlots();
        return base.stream()
                .filter(a -> subjectId == null || subjectId.isBlank() || subjectId.equals(a.getSubjectId()))
                .filter(a -> semesterId == null || semesterId.isBlank() || semesterId.equals(a.getSemesterId()))
                .sorted(Comparator.comparing(TeacherClassSubject::getClassCode, nullsLast())
                        .thenComparing(TeacherClassSubject::getSubjectName, nullsLast()))
                .map(a -> toDto(a, activeAssignments, slots, dayOfWeek, periodNo))
                .toList();
    }

    @Transactional
    public TeachingAssignmentDto create(CreateTeachingAssignmentRequest request) {
        String status = normalizeStatus(request.status());
        if ("ACTIVE".equals(status)) {
            assertScopeAvailable(null, request.classId(), request.subjectId(), request.semesterId());
        }
        User teacher = requireTeacher(request.teacherId());
        SchoolClass schoolClass = structure.getClass(request.classId());
        Subject subject = requireSubject(request.subjectId());
        assertTeacherSpecialty(teacher, subject);
        int weeklyPeriods = normalizeWeeklyPeriods(request.weeklyPeriods(), 2, 0);
        int specializedRoomPeriods = normalizeSpecializedRoomPeriods(
                request.specializedRoomPeriods(), subject, weeklyPeriods, null);

        TeacherClassSubject saved = assignments.save(TeacherClassSubject.builder()
                .id(request.id() == null || request.id().isBlank() ? Ids.gen("tcs") : request.id())
                .teacherId(teacher.getId())
                .teacherName(teacher.getFullName())
                .classId(schoolClass.getId())
                .classCode(schoolClass.getCode())
                .subjectId(request.subjectId())
                .subjectName(subject.getName())
                .semesterId(request.semesterId())
                .weeklyPeriods(weeklyPeriods)
                .specializedRoomPeriods(specializedRoomPeriods)
                .status(status)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build());
        return toDto(saved);
    }

    @Transactional
    public TeachingAssignmentDto update(String id, UpdateTeachingAssignmentRequest request) {
        TeacherClassSubject existing = assignments.findById(id)
                .orElseThrow(() -> ApiException.notFound("Phan cong"));
        String nextStatus = request.status() == null || request.status().isBlank()
                ? existing.getStatus()
                : normalizeStatus(request.status());
        String nextTeacherId = request.teacherId() == null || request.teacherId().isBlank()
                ? existing.getTeacherId()
                : request.teacherId();
        String nextClassId = request.classId() == null || request.classId().isBlank()
                ? existing.getClassId()
                : request.classId();
        String nextSubjectId = request.subjectId() == null || request.subjectId().isBlank()
                ? existing.getSubjectId()
                : request.subjectId();
        String nextSemesterId = request.semesterId() == null || request.semesterId().isBlank()
                ? existing.getSemesterId()
                : request.semesterId();
        int scheduledPeriods = scheduledPeriods(existing, timetable.allSlots());
        boolean changesScope = !nextTeacherId.equals(existing.getTeacherId())
                || !nextClassId.equals(existing.getClassId())
                || !nextSubjectId.equals(existing.getSubjectId())
                || !nextSemesterId.equals(existing.getSemesterId());

        if (scheduledPeriods > 0 && (changesScope || !"ACTIVE".equals(nextStatus))) {
            throw ApiException.conflict("Phân công đã có tiết trong thời khóa biểu; hãy xóa lịch trước khi đổi hoặc bỏ phân công");
        }
        int nextWeeklyPeriods = normalizeWeeklyPeriods(
                request.weeklyPeriods(), plannedPeriods(existing, scheduledPeriods), scheduledPeriods);

        if ("ACTIVE".equals(nextStatus)) {
            assertScopeAvailable(existing.getId(), nextClassId, nextSubjectId, nextSemesterId);
        }
        User teacher = requireTeacher(nextTeacherId);
        SchoolClass schoolClass = structure.getClass(nextClassId);
        Subject subject = requireSubject(nextSubjectId);
        assertTeacherSpecialty(teacher, subject);
        int nextSpecializedRoomPeriods = normalizeSpecializedRoomPeriods(
                request.specializedRoomPeriods(), subject, nextWeeklyPeriods,
                existing.getSpecializedRoomPeriods());
        existing.setTeacherId(teacher.getId());
        existing.setTeacherName(teacher.getFullName());
        existing.setClassId(schoolClass.getId());
        existing.setClassCode(schoolClass.getCode());
        existing.setSubjectId(subject.getId());
        existing.setSubjectName(subject.getName());
        existing.setSemesterId(nextSemesterId);
        existing.setWeeklyPeriods(nextWeeklyPeriods);
        existing.setSpecializedRoomPeriods(nextSpecializedRoomPeriods);
        existing.setStatus(nextStatus);
        existing.setUpdatedAt(Instant.now());
        return toDto(assignments.save(existing));
    }

    @Transactional
    public TeachingAssignmentDto deactivate(String id) {
        TeacherClassSubject existing = assignments.findById(id)
                .orElseThrow(() -> ApiException.notFound("Phan cong"));
        if (scheduledPeriods(existing, timetable.allSlots()) > 0) {
            throw ApiException.conflict("Phân công đã có tiết trong thời khóa biểu; hãy xóa lịch trước khi bỏ phân công");
        }
        existing.setStatus("INACTIVE");
        existing.setUpdatedAt(Instant.now());
        return toDto(assignments.save(existing));
    }

    public boolean teacherAssigned(String teacherId, String classId, String subjectId, String semesterId) {
        boolean assigned = assignments.existsByTeacherIdAndClassIdAndSubjectIdAndSemesterIdAndStatus(
                teacherId, classId, subjectId, semesterId, "ACTIVE");
        return assigned && teacherMatchesSubject(teacherId, subjectId);
    }

    public boolean teacherAssignedToClass(String teacherId, String classId) {
        return assignments.findByTeacherIdAndStatus(teacherId, "ACTIVE").stream()
                .anyMatch(assignment -> classId.equals(assignment.getClassId()));
    }

    /** Assignment records do not carry semester data; an active scope authorizes the teacher. */
    public boolean teacherAssignedToClassSubject(String teacherId, String classId, String subjectId) {
        boolean assigned = assignments.existsByTeacherIdAndClassIdAndSubjectIdAndStatus(
                teacherId, classId, subjectId, "ACTIVE");
        return assigned && teacherMatchesSubject(teacherId, subjectId);
    }

    @Transactional
    public int backfillFromTimetable(List<TimetableSlot> slots) {
        int created = 0;
        for (TimetableSlot slot : slots) {
            if (slot.getTeacherId() == null || slot.getClassId() == null
                    || slot.getSubjectId() == null || slot.getSemesterId() == null) {
                continue;
            }
            if (!teacherAssigned(slot.getTeacherId(), slot.getClassId(), slot.getSubjectId(), slot.getSemesterId())) {
                try {
                    create(new CreateTeachingAssignmentRequest(
                            null, slot.getTeacherId(), slot.getClassId(), slot.getSubjectId(), slot.getSemesterId(),
                            "ACTIVE", null, null));
                    created++;
                } catch (RuntimeException ignored) {
                    // Startup backfill is best-effort; invalid historical TKB data should not block boot.
                }
            }
        }
        return created;
    }

    public List<TeacherWorkloadDto> workloads(String semesterId) {
        List<TeacherClassSubject> activeAssignments = assignments.findAll().stream()
                .filter(a -> "ACTIVE".equals(a.getStatus()))
                .filter(a -> semesterId == null || semesterId.isBlank() || semesterId.equals(a.getSemesterId()))
                .toList();
        List<TimetableSlot> slots = timetable.allSlots();

        return users.list("TEACHER", null, null).stream()
                .sorted(Comparator.comparing(UserDto::fullName, nullsLast()))
                .map(teacher -> {
                    List<TeacherClassSubject> teacherAssignments = activeAssignments.stream()
                            .filter(a -> teacher.id().equals(a.getTeacherId()))
                            .sorted(Comparator.comparing(TeacherClassSubject::getClassCode, nullsLast())
                                    .thenComparing(TeacherClassSubject::getSubjectName, nullsLast()))
                            .toList();
                    List<TeacherClassAssignmentDto> details = teacherAssignments.stream()
                            .map(a -> {
                                int scheduled = scheduledPeriods(a, slots);
                                return new TeacherClassAssignmentDto(a.getId(), a.getClassId(), a.getClassCode(),
                                        a.getSubjectId(), a.getSubjectName(), a.getSemesterId(),
                                        plannedPeriods(a, scheduled), specializedRoomPeriods(a), scheduled);
                            })
                            .toList();
                    Set<String> classCodes = new LinkedHashSet<>();
                    Set<String> subjectNames = new LinkedHashSet<>();
                    details.forEach(a -> {
                        classCodes.add(a.classCode());
                        subjectNames.add(a.subjectName());
                    });
                    int weeklyPeriods = details.stream().mapToInt(TeacherClassAssignmentDto::weeklyPeriods).sum();
                    int scheduledPeriods = details.stream().mapToInt(TeacherClassAssignmentDto::scheduledPeriods).sum();
                    return new TeacherWorkloadDto(teacher.id(), teacher.teacherCode(), teacher.fullName(),
                            teacher.mainSubject(), teacher.status(), classCodes.size(), subjectNames.size(),
                            weeklyPeriods, scheduledPeriods, new ArrayList<>(classCodes),
                            new ArrayList<>(subjectNames), details);
                })
                .toList();
    }

    private TeachingAssignmentDto toDto(TeacherClassSubject assignment) {
        List<TeacherClassSubject> activeAssignments = assignments.findAll().stream()
                .filter(a -> "ACTIVE".equals(a.getStatus()))
                .toList();
        return toDto(assignment, activeAssignments, timetable.allSlots(), null, null);
    }

    private TeachingAssignmentDto toDto(TeacherClassSubject assignment,
                                        List<TeacherClassSubject> activeAssignments,
                                        List<TimetableSlot> slots,
                                        String dayOfWeek,
                                        Integer periodNo) {
        int scheduled = scheduledPeriods(assignment, slots);
        int planned = plannedPeriods(assignment, scheduled);
        int remaining = Math.max(0, planned - scheduled);
        List<TeacherClassSubject> teacherAssignments = activeAssignments.stream()
                .filter(a -> assignment.getTeacherId().equals(a.getTeacherId()))
                .filter(a -> assignment.getSemesterId().equals(a.getSemesterId()))
                .toList();
        int teacherClassCount = (int) teacherAssignments.stream()
                .map(TeacherClassSubject::getClassId)
                .distinct()
                .count();
        int teacherWeeklyPeriods = teacherAssignments.stream()
                .mapToInt(a -> {
                    int count = scheduledPeriods(a, slots);
                    return plannedPeriods(a, count);
                })
                .sum();
        int teacherScheduledPeriods = (int) slots.stream()
                .filter(slot -> assignment.getTeacherId().equals(slot.getTeacherId()))
                .filter(slot -> assignment.getSemesterId().equals(slot.getSemesterId()))
                .count();
        boolean checkingCell = dayOfWeek != null && !dayOfWeek.isBlank() && periodNo != null;
        boolean teacherBusy = checkingCell && slots.stream().anyMatch(slot ->
                assignment.getTeacherId().equals(slot.getTeacherId())
                        && assignment.getSemesterId().equals(slot.getSemesterId())
                        && dayOfWeek.equalsIgnoreCase(slot.getDayOfWeek())
                        && periodNo == slot.getPeriodNo());
        boolean classBusy = checkingCell && slots.stream().anyMatch(slot ->
                assignment.getClassId().equals(slot.getClassId())
                        && assignment.getSemesterId().equals(slot.getSemesterId())
                        && dayOfWeek.equalsIgnoreCase(slot.getDayOfWeek())
                        && periodNo == slot.getPeriodNo());
        boolean canSchedule = "ACTIVE".equals(assignment.getStatus())
                && remaining > 0 && !teacherBusy && !classBusy;
        String availabilityMessage = null;
        if (remaining == 0) {
            availabilityMessage = "Đã xếp đủ số tiết được phân công";
        } else if (teacherBusy) {
            availabilityMessage = "Giáo viên đã có lịch ở tiết này";
        } else if (classBusy) {
            availabilityMessage = "Lớp đã có môn khác ở tiết này";
        }

        return new TeachingAssignmentDto(assignment.getId(), assignment.getTeacherId(), assignment.getTeacherName(),
                assignment.getClassId(), assignment.getClassCode(), assignment.getSubjectId(), assignment.getSubjectName(),
                assignment.getSemesterId(), assignment.getStatus(), assignment.getCreatedAt(), assignment.getUpdatedAt(),
                planned, specializedRoomPeriods(assignment), scheduled, remaining,
                teacherClassCount, teacherWeeklyPeriods, teacherScheduledPeriods,
                scheduled >= planned, teacherBusy, canSchedule, availabilityMessage,
                assignment.getCreatedAt(), null);
    }

    private void assertScopeAvailable(String currentId, String classId, String subjectId, String semesterId) {
        assignments.findByClassIdAndSubjectIdAndSemesterIdAndStatus(classId, subjectId, semesterId, "ACTIVE")
                .filter(a -> currentId == null || !currentId.equals(a.getId()))
                .ifPresent(a -> {
                    throw ApiException.conflict("Lop/mon/hoc ky nay da co giao vien phu trach");
                });
    }

    private User requireTeacher(String teacherId) {
        User teacher = users.getById(teacherId);
        if (!"TEACHER".equals(teacher.getRole())) {
            throw ApiException.badRequest("teacherId must belong to a TEACHER user");
        }
        if (!"ACTIVE".equals(teacher.getStatus())) {
            throw ApiException.badRequest("Chỉ có thể phân công giáo viên đang hoạt động");
        }
        return teacher;
    }

    private Subject requireSubject(String subjectId) {
        return structure.listSubjects().stream()
                .filter(subject -> subjectId.equals(subject.getId()))
                .findFirst()
                .orElseThrow(() -> ApiException.notFound("Môn học"));
    }

    private void assertTeacherSpecialty(User teacher, Subject subject) {
        String specialty = normalizeForMatch(teacher.getMainSubject());
        String subjectId = normalizeForMatch(subject.getId());
        String subjectCode = normalizeForMatch(subject.getCode());
        String subjectName = normalizeForMatch(subject.getName());
        boolean matches = !specialty.isBlank()
                && (specialty.equals(subjectId)
                || specialty.equals(subjectCode)
                || specialty.equals(subjectName)
                || (specialty.length() >= 3 && subjectName.contains(specialty))
                || (subjectName.length() >= 3 && specialty.contains(subjectName)));
        if (!matches) {
            String teacherSubject = teacher.getMainSubject() == null || teacher.getMainSubject().isBlank()
                    ? "chưa cập nhật"
                    : teacher.getMainSubject();
            throw ApiException.badRequest("Giáo viên " + teacher.getFullName() + " có chuyên môn "
                    + teacherSubject + ", không thể phân công môn " + subject.getName());
        }
    }

    private boolean teacherMatchesSubject(String teacherId, String subjectId) {
        try {
            assertTeacherSpecialty(requireTeacher(teacherId), requireSubject(subjectId));
            return true;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private int scheduledPeriods(TeacherClassSubject assignment, List<TimetableSlot> slots) {
        return (int) slots.stream()
                .filter(slot -> assignment.getTeacherId().equals(slot.getTeacherId()))
                .filter(slot -> assignment.getClassId().equals(slot.getClassId()))
                .filter(slot -> assignment.getSubjectId().equals(slot.getSubjectId()))
                .filter(slot -> assignment.getSemesterId().equals(slot.getSemesterId()))
                .count();
    }

    private int plannedPeriods(TeacherClassSubject assignment, int scheduledPeriods) {
        int configured = assignment.getWeeklyPeriods() == null || assignment.getWeeklyPeriods() < 1
                ? 2
                : assignment.getWeeklyPeriods();
        return Math.max(configured, scheduledPeriods);
    }

    private int normalizeWeeklyPeriods(Integer requested, int fallback, int scheduledPeriods) {
        int value = requested == null ? fallback : requested;
        if (value < 1 || value > 20) {
            throw ApiException.badRequest("Số tiết mỗi tuần phải từ 1 đến 20");
        }
        if (value < scheduledPeriods) {
            throw ApiException.badRequest("Số tiết mỗi tuần không được nhỏ hơn số tiết đã xếp");
        }
        return value;
    }

    private int specializedRoomPeriods(TeacherClassSubject assignment) {
        return assignment.getSpecializedRoomPeriods() == null
                ? 0 : assignment.getSpecializedRoomPeriods();
    }

    private int normalizeSpecializedRoomPeriods(
            Integer requested, Subject subject, int weeklyPeriods, Integer fallback) {
        String roomType = subject.getRequiredRoomType() == null
                ? "GENERAL" : subject.getRequiredRoomType().trim().toUpperCase(Locale.ROOT);
        int defaultValue = switch (roomType) {
            case "LAB" -> Math.min(1, weeklyPeriods);
            case "COMPUTER", "GYM", "MUSIC", "ART" -> weeklyPeriods;
            default -> 0;
        };
        int value = requested == null
                ? (fallback == null ? defaultValue : fallback) : requested;
        if ("GENERAL".equals(roomType)) value = 0;
        if (value < 0 || value > weeklyPeriods) {
            throw ApiException.badRequest(
                    "Số tiết dùng phòng chuyên dụng phải từ 0 đến số tiết mỗi tuần");
        }
        return value;
    }

    private String normalizeForMatch(String value) {
        if (value == null) return "";
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .replace('đ', 'd')
                .replace('Đ', 'D')
                .toLowerCase(Locale.ROOT);
        return normalized.replaceAll("[^a-z0-9]+", " ").trim();
    }

    private String normalizeStatus(String status) {
        if (status == null || status.isBlank()) return "ACTIVE";
        String normalized = status.trim().toUpperCase();
        if (!List.of("ACTIVE", "INACTIVE").contains(normalized)) {
            throw ApiException.badRequest("status must be ACTIVE or INACTIVE");
        }
        return normalized;
    }

    private static Comparator<String> nullsLast() {
        return Comparator.nullsLast(String::compareTo);
    }
}
