package com.sse.app.academic.timetable;

import com.sse.app.academic.structure.SchoolClass;
import com.sse.app.academic.structure.Semester;
import com.sse.app.academic.structure.StructureService;
import com.sse.app.academic.timetable.TeachingAssignmentDtos.SaveTeachingAssignmentRequest;
import com.sse.app.academic.timetable.TeachingAssignmentDtos.TeacherClassAssignmentResponse;
import com.sse.app.academic.timetable.TeachingAssignmentDtos.TeachingAssignmentResponse;
import com.sse.app.academic.timetable.TeachingAssignmentDtos.TeacherWorkloadResponse;
import com.sse.app.common.ApiException;
import com.sse.app.common.Ids;
import com.sse.app.identity.User;
import com.sse.app.identity.UserService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
public class TeachingAssignmentService {
    private final TeachingAssignmentRepository assignments;
    private final TimetableRepository slots;
    private final StructureService structure;
    private final UserService users;

    public TeachingAssignmentService(TeachingAssignmentRepository assignments,
                                     TimetableRepository slots,
                                     StructureService structure,
                                     UserService users) {
        this.assignments = assignments;
        this.slots = slots;
        this.structure = structure;
        this.users = users;
    }

    public List<TeachingAssignmentResponse> list(String classId, String subjectId, String teacherId,
                                                  String semesterId, String dayOfWeek, Integer periodNo) {
        return assignments.findAll().stream()
                .filter(item -> classId == null || classId.equals(item.getClassId()))
                .filter(item -> subjectId == null || subjectId.equals(item.getSubjectId()))
                .filter(item -> teacherId == null || teacherId.equals(item.getTeacherId()))
                .filter(item -> semesterId == null || semesterId.equals(item.getSemesterId()))
                .sorted((left, right) -> {
                    int byClass = left.getClassCode().compareToIgnoreCase(right.getClassCode());
                    return byClass != 0 ? byClass : left.getSubjectName().compareToIgnoreCase(right.getSubjectName());
                })
                .map(item -> response(item, dayOfWeek, periodNo))
                .toList();
    }

    public List<TeacherWorkloadResponse> teacherWorkloads(String semesterId) {
        return users.list("TEACHER", null, null).stream()
                .map(teacher -> {
                    List<TeachingAssignment> teacherAssignments = assignments.findByTeacherId(teacher.id()).stream()
                            .filter(item -> semesterId == null || semesterId.equals(item.getSemesterId()))
                            .sorted((left, right) -> {
                                int byClass = left.getClassCode().compareToIgnoreCase(right.getClassCode());
                                return byClass != 0 ? byClass
                                        : left.getSubjectName().compareToIgnoreCase(right.getSubjectName());
                            })
                            .toList();
                    List<TeacherClassAssignmentResponse> details = teacherAssignments.stream()
                            .map(item -> new TeacherClassAssignmentResponse(
                                    item.getId(), item.getClassId(), item.getClassCode(),
                                    item.getSubjectId(), item.getSubjectName(), item.getSemesterId(),
                                    item.getWeeklyPeriods(), scheduledCount(item, null)))
                            .toList();
                    List<String> classCodes = teacherAssignments.stream()
                            .map(TeachingAssignment::getClassCode).distinct().sorted().toList();
                    List<String> subjectNames = teacherAssignments.stream()
                            .map(TeachingAssignment::getSubjectName).distinct().sorted().toList();
                    int weeklyPeriods = teacherAssignments.stream()
                            .mapToInt(TeachingAssignment::getWeeklyPeriods).sum();
                    int scheduledPeriods = details.stream()
                            .mapToInt(TeacherClassAssignmentResponse::scheduledPeriods).sum();
                    return new TeacherWorkloadResponse(
                            teacher.id(), teacher.teacherCode(), teacher.fullName(), teacher.mainSubject(),
                            teacher.status(), classCodes.size(), subjectNames.size(), weeklyPeriods,
                            scheduledPeriods, classCodes, subjectNames, details);
                })
                .sorted((left, right) -> left.teacherName().compareToIgnoreCase(right.teacherName()))
                .toList();
    }

    @Transactional
    public TeachingAssignmentResponse create(SaveTeachingAssignmentRequest request, String actorId) {
        AssignmentScope scope = validateScope(request);
        assignments.findByClassIdAndSubjectIdAndSemesterId(
                        request.classId(), request.subjectId(), request.semesterId())
                .ifPresent(existing -> {
                    throw ApiException.conflict("Lớp đã có giáo viên phụ trách môn này trong học kỳ đã chọn");
                });
        Instant now = Instant.now();
        TeachingAssignment created = assignments.save(TeachingAssignment.builder()
                .id(Ids.gen("ta"))
                .classId(scope.schoolClass().getId())
                .classCode(scope.schoolClass().getCode())
                .subjectId(request.subjectId())
                .subjectName(scope.subjectName())
                .teacherId(scope.teacher().getId())
                .teacherName(scope.teacher().getFullName())
                .semesterId(scope.semester().getId())
                .weeklyPeriods(request.weeklyPeriods())
                .assignedAt(now)
                .assignedBy(actorId)
                .updatedAt(now)
                .build());
        return response(created, null, null);
    }

    @Transactional
    public TeachingAssignmentResponse update(String id, SaveTeachingAssignmentRequest request) {
        TeachingAssignment current = require(id);
        AssignmentScope scope = validateScope(request);
        boolean scopeChanged = !Objects.equals(current.getClassId(), request.classId())
                || !Objects.equals(current.getSubjectId(), request.subjectId())
                || !Objects.equals(current.getTeacherId(), request.teacherId())
                || !Objects.equals(current.getSemesterId(), request.semesterId());
        int scheduled = scheduledCount(current, null);
        if (scopeChanged && scheduled > 0) {
            throw ApiException.conflict("Phân công đã có tiết trong thời khóa biểu; hãy xóa các tiết đó trước khi đổi lớp, môn, giáo viên hoặc học kỳ");
        }
        if (request.weeklyPeriods() < scheduled) {
            throw ApiException.conflict("Số tiết/tuần không thể nhỏ hơn " + scheduled + " tiết đã xếp");
        }
        assignments.findByClassIdAndSubjectIdAndSemesterId(
                        request.classId(), request.subjectId(), request.semesterId())
                .filter(existing -> !existing.getId().equals(id))
                .ifPresent(existing -> {
                    throw ApiException.conflict("Lớp đã có giáo viên phụ trách môn này trong học kỳ đã chọn");
                });
        current.setClassId(scope.schoolClass().getId());
        current.setClassCode(scope.schoolClass().getCode());
        current.setSubjectId(request.subjectId());
        current.setSubjectName(scope.subjectName());
        current.setTeacherId(scope.teacher().getId());
        current.setTeacherName(scope.teacher().getFullName());
        current.setSemesterId(scope.semester().getId());
        current.setWeeklyPeriods(request.weeklyPeriods());
        current.setUpdatedAt(Instant.now());
        return response(assignments.save(current), null, null);
    }

    @Transactional
    public void delete(String id) {
        TeachingAssignment current = require(id);
        if (scheduledCount(current, null) > 0) {
            throw ApiException.conflict("Không thể xóa phân công đã có thời khóa biểu; hãy xóa các tiết liên quan trước");
        }
        assignments.delete(current);
    }

    public TeachingAssignment requireForSlot(String classId, String subjectId,
                                              String teacherId, String semesterId) {
        return assignments.lockForScheduling(
                        classId, subjectId, teacherId, semesterId)
                .orElseThrow(() -> ApiException.badRequest(
                        "Chưa phân công giáo viên này dạy môn đã chọn cho lớp trong học kỳ hiện tại"));
    }

    public void assertCanSchedule(TeachingAssignment assignment, String ignoredSlotId) {
        int scheduled = scheduledCount(assignment, ignoredSlotId);
        if (scheduled >= assignment.getWeeklyPeriods()) {
            throw ApiException.conflict("Phân công " + assignment.getSubjectName() + " - lớp "
                    + assignment.getClassCode() + " đã xếp đủ " + scheduled + "/"
                    + assignment.getWeeklyPeriods() + " tiết/tuần; không thể xếp thêm");
        }
    }

    public boolean isAssigned(String teacherId, String classId) {
        if (teacherId == null || classId == null) return false;
        return assignments.findByTeacherId(teacherId).stream()
                .anyMatch(item -> classId.equals(item.getClassId()));
    }

    public boolean isAssigned(String teacherId, String classId, String subjectId, String semesterId) {
        if (teacherId == null || classId == null || subjectId == null) return false;
        return assignments.findByClassIdAndSubjectIdAndTeacherIdAndSemesterId(
                classId, subjectId, teacherId, semesterId).isPresent();
    }

    public List<TeachingAssignment> assignmentsOfTeacher(String teacherId) {
        return assignments.findByTeacherId(teacherId);
    }

    public List<TeachingAssignment> assignmentsOfClass(String classId, String semesterId) {
        return assignments.findAll().stream()
                .filter(item -> classId.equals(item.getClassId()))
                .filter(item -> semesterId == null || semesterId.equals(item.getSemesterId()))
                .toList();
    }

    @Transactional
    public void seedFromSlots(List<TimetableSlot> timetableSlots) {
        Map<String, List<TimetableSlot>> groups = new LinkedHashMap<>();
        timetableSlots.forEach(slot -> groups.computeIfAbsent(
                slot.getClassId() + "|" + slot.getSubjectId() + "|" + slot.getTeacherId() + "|" + slot.getSemesterId(),
                ignored -> new java.util.ArrayList<>()).add(slot));
        groups.values().forEach(group -> {
            TimetableSlot sample = group.get(0);
            if (assignments.findByClassIdAndSubjectIdAndSemesterId(
                    sample.getClassId(), sample.getSubjectId(), sample.getSemesterId()).isPresent()) return;
            SchoolClass schoolClass = structure.getClass(sample.getClassId());
            Instant now = Instant.now();
            assignments.save(TeachingAssignment.builder()
                    .id(Ids.gen("ta"))
                    .classId(sample.getClassId()).classCode(schoolClass.getCode())
                    .subjectId(sample.getSubjectId()).subjectName(sample.getSubjectName())
                    .teacherId(sample.getTeacherId()).teacherName(sample.getTeacherName())
                    .semesterId(sample.getSemesterId()).weeklyPeriods(group.size())
                    .assignedAt(now).assignedBy("SYSTEM").updatedAt(now).build());
        });
    }

    private TeachingAssignment require(String id) {
        return assignments.findById(id).orElseThrow(() -> ApiException.notFound("Phân công giảng dạy"));
    }

    private AssignmentScope validateScope(SaveTeachingAssignmentRequest request) {
        SchoolClass schoolClass = structure.getClass(request.classId());
        Semester semester = structure.getSemester(request.semesterId());
        if (schoolClass.getAcademicYearId() != null && semester.getAcademicYearId() != null
                && !schoolClass.getAcademicYearId().equals(semester.getAcademicYearId())) {
            throw ApiException.badRequest("Lớp và học kỳ không thuộc cùng năm học");
        }
        String subjectName = structure.requireSubjectName(request.subjectId());
        User teacher = users.getById(request.teacherId());
        if (!"TEACHER".equals(teacher.getRole()) || !"ACTIVE".equals(teacher.getStatus())) {
            throw ApiException.badRequest("Chỉ có thể phân công giáo viên đang hoạt động");
        }
        return new AssignmentScope(schoolClass, semester, subjectName, teacher);
    }

    private TeachingAssignmentResponse response(TeachingAssignment item, String dayOfWeek, Integer periodNo) {
        int scheduled = scheduledCount(item, null);
        int remaining = Math.max(0, item.getWeeklyPeriods() - scheduled);
        boolean full = remaining == 0;
        List<TeachingAssignment> teacherAssignments = assignments.findByTeacherId(item.getTeacherId()).stream()
                .filter(assignment -> item.getSemesterId().equals(assignment.getSemesterId()))
                .toList();
        int teacherClassCount = (int) teacherAssignments.stream()
                .map(TeachingAssignment::getClassId).distinct().count();
        int teacherWeeklyPeriods = teacherAssignments.stream()
                .mapToInt(TeachingAssignment::getWeeklyPeriods).sum();
        int teacherScheduledPeriods = (int) slots.findByTeacherId(item.getTeacherId()).stream()
                .filter(slot -> item.getSemesterId().equals(slot.getSemesterId()))
                .count();
        TimetableSlot busySlot = null;
        if (dayOfWeek != null && periodNo != null) {
            busySlot = slots.findByDayOfWeekAndPeriodNo(dayOfWeek.toUpperCase(), periodNo).stream()
                    .filter(slot -> item.getSemesterId().equals(slot.getSemesterId()))
                    .filter(slot -> item.getTeacherId().equals(slot.getTeacherId()))
                    .findFirst().orElse(null);
        }
        boolean busy = busySlot != null;
        String message = null;
        if (full) {
            message = "Đã xếp đủ " + scheduled + "/" + item.getWeeklyPeriods() + " tiết/tuần";
        } else if (busy) {
            String busyClass = structure.getClass(busySlot.getClassId()).getCode();
            message = "Giáo viên đang dạy " + busySlot.getSubjectName() + " tại lớp " + busyClass
                    + " ở tiết đã chọn";
        }
        return new TeachingAssignmentResponse(item.getId(), item.getClassId(), item.getClassCode(),
                item.getSubjectId(), item.getSubjectName(), item.getTeacherId(), item.getTeacherName(),
                item.getSemesterId(), item.getWeeklyPeriods(), scheduled, remaining,
                teacherClassCount, teacherWeeklyPeriods, teacherScheduledPeriods, full, busy,
                !full && !busy, message, item.getAssignedAt(), item.getAssignedBy(), item.getUpdatedAt());
    }

    private int scheduledCount(TeachingAssignment item, String ignoredSlotId) {
        return (int) slots.findByClassId(item.getClassId()).stream()
                .filter(slot -> item.getSubjectId().equals(slot.getSubjectId()))
                .filter(slot -> item.getTeacherId().equals(slot.getTeacherId()))
                .filter(slot -> item.getSemesterId().equals(slot.getSemesterId()))
                .filter(slot -> ignoredSlotId == null || !ignoredSlotId.equals(slot.getId()))
                .count();
    }

    private record AssignmentScope(SchoolClass schoolClass, Semester semester,
                                   String subjectName, User teacher) {}
}
