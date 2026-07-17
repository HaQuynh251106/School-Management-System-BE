package com.sse.app.academic.teaching;

import com.sse.app.academic.structure.SchoolClass;
import com.sse.app.academic.structure.StructureService;
import com.sse.app.academic.teaching.TeachingDtos.*;
import com.sse.app.academic.timetable.TimetableSlot;
import com.sse.app.common.ApiException;
import com.sse.app.common.Ids;
import com.sse.app.identity.User;
import com.sse.app.identity.UserService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;

@Service
public class TeachingAssignmentService {

    private final TeachingAssignmentRepository assignments;
    private final StructureService structure;
    private final UserService users;

    public TeachingAssignmentService(TeachingAssignmentRepository assignments,
                                     StructureService structure,
                                     UserService users) {
        this.assignments = assignments;
        this.structure = structure;
        this.users = users;
    }

    public List<TeachingAssignmentDto> list(String teacherId, String classId, String subjectId,
                                           String semesterId, String status) {
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
        return base.stream()
                .filter(a -> subjectId == null || subjectId.isBlank() || subjectId.equals(a.getSubjectId()))
                .filter(a -> semesterId == null || semesterId.isBlank() || semesterId.equals(a.getSemesterId()))
                .sorted(Comparator.comparing(TeacherClassSubject::getClassCode, nullsLast())
                        .thenComparing(TeacherClassSubject::getSubjectName, nullsLast()))
                .map(this::toDto)
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
        String subjectName = structure.subjectName(request.subjectId());
        if (subjectName == null) throw ApiException.notFound("Mon hoc");

        TeacherClassSubject saved = assignments.save(TeacherClassSubject.builder()
                .id(request.id() == null || request.id().isBlank() ? Ids.gen("tcs") : request.id())
                .teacherId(teacher.getId())
                .teacherName(teacher.getFullName())
                .classId(schoolClass.getId())
                .classCode(schoolClass.getCode())
                .subjectId(request.subjectId())
                .subjectName(subjectName)
                .semesterId(request.semesterId())
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

        if ("ACTIVE".equals(nextStatus)) {
            assertScopeAvailable(existing.getId(), existing.getClassId(), existing.getSubjectId(), existing.getSemesterId());
        }
        User teacher = requireTeacher(nextTeacherId);
        existing.setTeacherId(teacher.getId());
        existing.setTeacherName(teacher.getFullName());
        existing.setStatus(nextStatus);
        existing.setUpdatedAt(Instant.now());
        return toDto(assignments.save(existing));
    }

    @Transactional
    public TeachingAssignmentDto deactivate(String id) {
        TeacherClassSubject existing = assignments.findById(id)
                .orElseThrow(() -> ApiException.notFound("Phan cong"));
        existing.setStatus("INACTIVE");
        existing.setUpdatedAt(Instant.now());
        return toDto(assignments.save(existing));
    }

    public boolean teacherAssigned(String teacherId, String classId, String subjectId, String semesterId) {
        return assignments.existsByTeacherIdAndClassIdAndSubjectIdAndSemesterIdAndStatus(
                teacherId, classId, subjectId, semesterId, "ACTIVE");
    }

    /** Assignment records do not carry semester data; an active scope authorizes the teacher. */
    public boolean teacherAssignedToClassSubject(String teacherId, String classId, String subjectId) {
        return assignments.existsByTeacherIdAndClassIdAndSubjectIdAndStatus(
                teacherId, classId, subjectId, "ACTIVE");
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
                            null, slot.getTeacherId(), slot.getClassId(), slot.getSubjectId(), slot.getSemesterId(), "ACTIVE"));
                    created++;
                } catch (RuntimeException ignored) {
                    // Startup backfill is best-effort; invalid historical TKB data should not block boot.
                }
            }
        }
        return created;
    }

    private TeachingAssignmentDto toDto(TeacherClassSubject a) {
        return new TeachingAssignmentDto(a.getId(), a.getTeacherId(), a.getTeacherName(),
                a.getClassId(), a.getClassCode(), a.getSubjectId(), a.getSubjectName(),
                a.getSemesterId(), a.getStatus(), a.getCreatedAt(), a.getUpdatedAt());
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
        return teacher;
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
