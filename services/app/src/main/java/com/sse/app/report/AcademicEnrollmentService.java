package com.sse.app.report;

import com.sse.app.academic.structure.AcademicYear;
import com.sse.app.academic.structure.SchoolClass;
import com.sse.app.academic.structure.StructureService;
import com.sse.app.common.ApiException;
import com.sse.app.common.Ids;
import com.sse.app.identity.User;
import com.sse.app.identity.UserRepository;
import com.sse.app.report.AcademicEnrollmentDtos.BulkEnrollmentRequest;
import com.sse.app.report.AcademicEnrollmentDtos.BulkEnrollmentResult;
import com.sse.app.report.AcademicEnrollmentDtos.EnrollmentView;
import com.sse.app.report.AcademicEnrollmentDtos.StudentCandidate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Optional;

@Service
public class AcademicEnrollmentService {
    private final StudentClassEnrollmentRepository enrollments;
    private final UserRepository users;
    private final StructureService structure;

    public AcademicEnrollmentService(
            StudentClassEnrollmentRepository enrollments,
            UserRepository users,
            StructureService structure) {
        this.enrollments = enrollments;
        this.users = users;
        this.structure = structure;
    }

    public List<EnrollmentView> list(String academicYearId, String classId) {
        structure.getYear(academicYearId);
        SchoolClass schoolClass = structure.getClass(classId);
        if (!academicYearId.equals(schoolClass.getAcademicYearId())) {
            throw ApiException.badRequest("Lớp không thuộc năm học đã chọn");
        }
        return enrollments.findByAcademicYearIdAndClassId(
                        academicYearId, classId).stream()
                .filter(item -> "ACTIVE".equals(item.getStatus()))
                .map(item -> toView(item, schoolClass.getCode()))
                .sorted(Comparator.comparing(
                        EnrollmentView::studentName,
                        String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    public Optional<String> activeClassId(String studentId) {
        return structure.listYears().stream()
                .filter(year -> "ACTIVE".equalsIgnoreCase(year.getStatus()))
                .findFirst()
                .flatMap(year -> classId(studentId, year.getId()));
    }

    public Optional<String> classIdForSemester(String studentId, String semesterId) {
        if (semesterId == null || semesterId.isBlank()) {
            return activeClassId(studentId);
        }
        return classId(studentId,
                structure.getSemester(semesterId).getAcademicYearId());
    }

    private Optional<String> classId(String studentId, String academicYearId) {
        return enrollments.findByAcademicYearIdAndStudentId(
                        academicYearId, studentId)
                .filter(item -> "ACTIVE".equals(item.getStatus()))
                .map(StudentClassEnrollment::getClassId);
    }

    public List<StudentCandidate> unassigned(
            String academicYearId, String keyword) {
        structure.getYear(academicYearId);
        Set<String> assigned = new HashSet<>();
        for (SchoolClass schoolClass
                : structure.listClasses(academicYearId, null)) {
            enrollments.findByAcademicYearIdAndClassId(
                            academicYearId, schoolClass.getId()).stream()
                    .filter(item -> "ACTIVE".equals(item.getStatus()))
                    .map(StudentClassEnrollment::getStudentId)
                    .forEach(assigned::add);
        }
        String query = keyword == null ? "" : keyword.trim().toLowerCase();
        return users.findByRoleAndStatusNot("STUDENT", "DELETED").stream()
                .filter(user -> !assigned.contains(user.getId()))
                .filter(user -> query.isBlank()
                        || contains(user.getFullName(), query)
                        || contains(user.getStudentCode(), query)
                        || contains(user.getPhone(), query)
                        || contains(user.getEmail(), query))
                .map(user -> new StudentCandidate(
                        user.getId(), user.getStudentCode(),
                        user.getFullName(), user.getClassId(),
                        user.getClassName()))
                .sorted(Comparator.comparing(
                        StudentCandidate::fullName,
                        String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    @Transactional
    public BulkEnrollmentResult assign(
            BulkEnrollmentRequest request, String actorId) {
        if (request.reason().trim().length() < 5) {
            throw ApiException.badRequest("Lý do phân lớp phải có ít nhất 5 ký tự");
        }
        AcademicYear year = structure.getYear(request.academicYearId());
        SchoolClass target = structure.getClass(request.classId());
        if (!year.getId().equals(target.getAcademicYearId())) {
            throw ApiException.badRequest("Lớp không thuộc năm học đã chọn");
        }
        Set<String> distinctIds = new HashSet<>(request.studentIds());
        long current = enrollments.findByAcademicYearIdAndClassId(
                        year.getId(), target.getId()).stream()
                .filter(item -> "ACTIVE".equals(item.getStatus()))
                .count();
        long incoming = distinctIds.stream()
                .filter(studentId -> enrollments
                        .findByAcademicYearIdAndStudentId(
                                year.getId(), studentId)
                        .filter(item -> "ACTIVE".equals(item.getStatus())
                                && target.getId().equals(item.getClassId()))
                        .isEmpty())
                .count();
        int capacity = target.getMaxStudents() == null
                ? 45 : target.getMaxStudents();
        if (current + incoming > capacity) {
            throw ApiException.conflict(
                    "Lớp vượt sĩ số tối đa " + capacity + " học sinh");
        }

        int assigned = 0;
        int transferred = 0;
        int unchanged = 0;
        Instant now = Instant.now();
        Set<String> affectedClasses = new HashSet<>();
        for (String studentId : distinctIds) {
            User student = users.findById(studentId)
                    .orElseThrow(() -> ApiException.notFound("Học sinh"));
            if (!"STUDENT".equals(student.getRole())
                    || "DELETED".equals(student.getStatus())) {
                throw ApiException.badRequest(
                        student.getFullName() + " không phải học sinh hợp lệ");
            }
            StudentClassEnrollment enrollment = enrollments
                    .findByAcademicYearIdAndStudentId(year.getId(), studentId)
                    .orElse(null);
            if (enrollment != null
                    && "ACTIVE".equals(enrollment.getStatus())
                    && target.getId().equals(enrollment.getClassId())) {
                unchanged++;
                continue;
            }
            if (enrollment == null) {
                enrollment = StudentClassEnrollment.builder()
                        .id(Ids.gen("enr"))
                        .academicYearId(year.getId())
                        .studentId(student.getId())
                        .build();
                assigned++;
            } else {
                if ("ACTIVE".equals(enrollment.getStatus())) {
                    affectedClasses.add(enrollment.getClassId());
                    transferred++;
                } else {
                    assigned++;
                }
            }
            enrollment.setClassId(target.getId());
            enrollment.setStudentCode(student.getStudentCode());
            enrollment.setStudentName(student.getFullName());
            enrollment.setEnrollmentType("MANUAL");
            enrollment.setStatus("ACTIVE");
            enrollment.setEnrolledBy(actorId);
            enrollment.setEnrolledAt(now);
            enrollment.setRevertedBy(null);
            enrollment.setRevertedAt(null);
            enrollment.setRevertReason(request.reason().trim());
            enrollments.save(enrollment);
            if ("ACTIVE".equals(year.getStatus())) {
                student.setClassId(target.getId());
                student.setClassName(target.getCode());
                users.save(student);
            }
        }
        affectedClasses.add(target.getId());
        affectedClasses.forEach(classId -> refreshCount(year.getId(), classId));
        return new BulkEnrollmentResult(
                assigned, transferred, unchanged, target.getId());
    }

    @Transactional
    public void assignStudentCurrentClass(String studentId, String classId, String actorId) {
        if (classId == null || classId.isBlank()) return;
        SchoolClass target = structure.getClass(classId);
        assign(new BulkEnrollmentRequest(target.getAcademicYearId(), target.getId(),
                List.of(studentId), "Phân lớp cùng hồ sơ học sinh"),
                actorId == null ? "SYSTEM" : actorId);
    }

    @Transactional
    public void remove(String id, String reason, String actorId) {
        if (reason == null || reason.trim().length() < 5) {
            throw ApiException.badRequest("Lý do bỏ phân lớp phải có ít nhất 5 ký tự");
        }
        StudentClassEnrollment enrollment = enrollments.findById(id)
                .orElseThrow(() -> ApiException.notFound("Phân lớp"));
        if (!"ACTIVE".equals(enrollment.getStatus())) return;
        enrollment.setStatus("REVERTED");
        enrollment.setRevertedBy(actorId);
        enrollment.setRevertedAt(Instant.now());
        enrollment.setRevertReason(reason.trim());
        enrollments.save(enrollment);
        AcademicYear year = structure.getYear(enrollment.getAcademicYearId());
        if ("ACTIVE".equals(year.getStatus())) {
            users.findById(enrollment.getStudentId()).ifPresent(student -> {
                if (enrollment.getClassId().equals(student.getClassId())) {
                    student.setClassId(null);
                    student.setClassName(null);
                    users.save(student);
                }
            });
        }
        refreshCount(enrollment.getAcademicYearId(), enrollment.getClassId());
    }

    private void refreshCount(String academicYearId, String classId) {
        int count = (int) enrollments.findByAcademicYearIdAndClassId(
                        academicYearId, classId).stream()
                .filter(item -> "ACTIVE".equals(item.getStatus()))
                .count();
        structure.updateClassStudentCount(classId, count);
    }

    private EnrollmentView toView(
            StudentClassEnrollment item, String classCode) {
        return new EnrollmentView(
                item.getId(), item.getAcademicYearId(), item.getClassId(),
                classCode, item.getStudentId(), item.getStudentCode(),
                item.getStudentName(), item.getStatus(),
                item.getEnrollmentType(), item.getEnrolledAt());
    }

    private boolean contains(String value, String query) {
        return value != null && value.toLowerCase().contains(query);
    }
}
