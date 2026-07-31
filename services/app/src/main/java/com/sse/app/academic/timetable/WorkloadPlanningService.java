package com.sse.app.academic.timetable;

import com.sse.app.academic.structure.SchoolClass;
import com.sse.app.academic.structure.Semester;
import com.sse.app.academic.structure.StructureService;
import com.sse.app.academic.structure.Subject;
import com.sse.app.academic.timetable.TeachingAssignmentDtos.SaveTeachingAssignmentRequest;
import com.sse.app.academic.timetable.WorkloadPlanningDtos.*;
import com.sse.app.common.ApiException;
import com.sse.app.common.Ids;
import com.sse.app.identity.User;
import com.sse.app.identity.UserService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;

@Service
public class WorkloadPlanningService {
    private static final Set<String> REVIEW_STATUSES = Set.of("APPROVED", "REJECTED", "LOCKED", "DRAFT");

    private final CurriculumRequirementRepository requirements;
    private final TeacherLoadRegistrationRepository registrations;
    private final TeachingAssignmentRepository assignments;
    private final TeachingAssignmentService teachingAssignments;
    private final StructureService structure;
    private final UserService users;

    public WorkloadPlanningService(CurriculumRequirementRepository requirements,
                                   TeacherLoadRegistrationRepository registrations,
                                   TeachingAssignmentRepository assignments,
                                   TeachingAssignmentService teachingAssignments,
                                   StructureService structure, UserService users) {
        this.requirements = requirements;
        this.registrations = registrations;
        this.assignments = assignments;
        this.teachingAssignments = teachingAssignments;
        this.structure = structure;
        this.users = users;
    }

    public List<CurriculumRequirement> listRequirements(String semesterId) {
        return requirements.findBySemesterId(semesterId).stream()
                .sorted(Comparator.comparing(CurriculumRequirement::getGradeLevel)
                        .thenComparing(CurriculumRequirement::getSubjectName)).toList();
    }

    @Transactional
    public CurriculumRequirement saveRequirement(SaveCurriculumRequirementRequest request) {
        structure.assertSemesterWritable(request.semesterId());
        Semester semester = structure.getSemester(request.semesterId());
        String grade = normalizeGrade(request.gradeLevel());
        boolean gradeExists = structure.listClasses(semester.getAcademicYearId(), null).stream()
                .anyMatch(item -> grade.equals(normalizeGrade(item.getGradeLevel())));
        if (!gradeExists) throw ApiException.badRequest("Khối " + grade + " chưa có lớp trong năm học đã chọn");
        Subject subject = structure.listSubjects().stream()
                .filter(item -> item.getId().equals(request.subjectId())).findFirst()
                .orElseThrow(() -> ApiException.notFound("Môn học"));
        Instant now = Instant.now();
        CurriculumRequirement item = requirements
                .findBySemesterIdAndGradeLevelAndSubjectId(request.semesterId(), grade, request.subjectId())
                .orElseGet(() -> CurriculumRequirement.builder().id(Ids.gen("cr"))
                        .semesterId(request.semesterId()).gradeLevel(grade)
                        .subjectId(subject.getId()).subjectName(subject.getName()).createdAt(now).build());
        item.setSubjectName(subject.getName());
        item.setWeeklyPeriods(request.weeklyPeriods());
        item.setUpdatedAt(now);
        return requirements.save(item);
    }

    @Transactional
    public void deleteRequirement(String id) {
        CurriculumRequirement item = requirements.findById(id)
                .orElseThrow(() -> ApiException.notFound("Định mức môn học"));
        structure.assertSemesterWritable(item.getSemesterId());
        requirements.delete(item);
    }

    public TeacherLoadResponse mine(String teacherId, String semesterId) {
        return registrations.findByTeacherIdAndSemesterId(teacherId, semesterId)
                .map(this::response).orElse(null);
    }

    public List<TeacherLoadResponse> listRegistrations(String semesterId) {
        return registrations.findBySemesterId(semesterId).stream()
                .sorted(Comparator.comparing(TeacherLoadRegistration::getTeacherName))
                .map(this::response).toList();
    }

    @Transactional
    public TeacherLoadResponse saveMine(String teacherId, SaveTeacherLoadRequest request) {
        structure.assertSemesterWritable(request.semesterId());
        User teacher = requireActiveTeacher(teacherId);
        Instant now = Instant.now();
        TeacherLoadRegistration item = registrations
                .findByTeacherIdAndSemesterId(teacherId, request.semesterId())
                .orElseGet(() -> TeacherLoadRegistration.builder().id(Ids.gen("tlr"))
                        .teacherId(teacherId).teacherName(teacher.getFullName())
                        .semesterId(request.semesterId()).status("DRAFT").createdAt(now).build());
        if (!Set.of("DRAFT", "REJECTED").contains(item.getStatus())) {
            throw ApiException.conflict("Đăng ký đã gửi hoặc đã duyệt. Hãy liên hệ quản trị viên để mở lại.");
        }
        item.setTeacherName(teacher.getFullName());
        item.setMaxWeeklyPeriods(request.maxWeeklyPeriods());
        item.setUnavailableSlots(csv(request.unavailableSlots()));
        item.setPreferredGradeLevels(csv(normalizeGrades(request.preferredGradeLevels())));
        item.setNote(clean(request.note()));
        item.setStatus("DRAFT");
        item.setReviewNote(null);
        item.setReviewedAt(null);
        item.setReviewedBy(null);
        item.setUpdatedAt(now);
        return response(registrations.save(item));
    }

    @Transactional
    public TeacherLoadResponse submitMine(String teacherId, String semesterId) {
        structure.assertSemesterWritable(semesterId);
        TeacherLoadRegistration item = registrations.findByTeacherIdAndSemesterId(teacherId, semesterId)
                .orElseThrow(() -> ApiException.badRequest("Hãy lưu đăng ký tải dạy trước khi gửi duyệt"));
        if (!Set.of("DRAFT", "REJECTED").contains(item.getStatus())) {
            throw ApiException.conflict("Đăng ký hiện không ở trạng thái có thể gửi duyệt");
        }
        item.setStatus("SUBMITTED");
        item.setSubmittedAt(Instant.now());
        item.setUpdatedAt(Instant.now());
        return response(registrations.save(item));
    }

    @Transactional
    public TeacherLoadResponse review(String id, ReviewTeacherLoadRequest request, String actorId) {
        TeacherLoadRegistration item = registrations.findById(id)
                .orElseThrow(() -> ApiException.notFound("Đăng ký tải dạy"));
        structure.assertSemesterWritable(item.getSemesterId());
        String status = request.status().trim().toUpperCase(Locale.ROOT);
        if (!REVIEW_STATUSES.contains(status)) throw ApiException.badRequest("Trạng thái duyệt không hợp lệ");
        if (Set.of("APPROVED", "REJECTED", "LOCKED").contains(status)
                && !"SUBMITTED".equals(item.getStatus()) && !"APPROVED".equals(item.getStatus())) {
            throw ApiException.conflict("Chỉ có thể duyệt đăng ký giáo viên đã gửi");
        }
        item.setStatus(status);
        item.setReviewNote(clean(request.reviewNote()));
        item.setReviewedAt(Instant.now());
        item.setReviewedBy(actorId);
        item.setUpdatedAt(Instant.now());
        return response(registrations.save(item));
    }

    @Transactional
    public AutoAssignmentPlan plan(AutoAssignmentRequest request, String actorId) {
        structure.assertSemesterWritable(request.semesterId());
        Semester semester = structure.getSemester(request.semesterId());
        List<SchoolClass> classes = structure.listClasses(semester.getAcademicYearId(), null);
        List<CurriculumRequirement> required = listRequirements(request.semesterId());
        if (required.isEmpty()) throw ApiException.badRequest("Chưa có định mức môn học cho học kỳ đã chọn");
        List<TeacherLoadRegistration> approved = registrations.findBySemesterId(request.semesterId()).stream()
                .filter(item -> Set.of("APPROVED", "LOCKED").contains(item.getStatus())).toList();
        if (approved.isEmpty()) throw ApiException.badRequest("Chưa có đăng ký tải dạy nào được duyệt trong học kỳ");

        Map<String, User> teacherById = new HashMap<>();
        approved.forEach(item -> teacherById.put(item.getTeacherId(), requireActiveTeacher(item.getTeacherId())));
        Map<String, Integer> projected = new HashMap<>();
        approved.forEach(item -> projected.put(item.getTeacherId(),
                assignments.findByTeacherId(item.getTeacherId()).stream()
                        .filter(a -> request.semesterId().equals(a.getSemesterId()))
                        .mapToInt(TeachingAssignment::getWeeklyPeriods).sum()));

        List<AutoAssignmentItem> items = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        int existingCount = 0, proposedCount = 0, unassignedCount = 0;
        for (SchoolClass schoolClass : classes) {
            for (CurriculumRequirement requirement : required.stream()
                    .filter(item -> normalizeGrade(schoolClass.getGradeLevel()).equals(item.getGradeLevel())).toList()) {
                var existing = assignments.findByClassIdAndSubjectIdAndSemesterId(
                        schoolClass.getId(), requirement.getSubjectId(), request.semesterId());
                if (existing.isPresent()) {
                    TeachingAssignment a = existing.get();
                    items.add(new AutoAssignmentItem(schoolClass.getId(), schoolClass.getCode(),
                            schoolClass.getGradeLevel(), requirement.getSubjectId(), requirement.getSubjectName(),
                            a.getWeeklyPeriods(), a.getTeacherId(), a.getTeacherName(),
                            projected.getOrDefault(a.getTeacherId(), a.getWeeklyPeriods()),
                            "EXISTING", "Giữ nguyên phân công hiện tại"));
                    existingCount++;
                    continue;
                }
                TeacherLoadRegistration selected = approved.stream()
                        .filter(item -> teachingAssignments.teacherSupportsSubject(
                                teacherById.get(item.getTeacherId()), requirement.getSubjectId()))
                        .filter(item -> projected.getOrDefault(item.getTeacherId(), 0)
                                + requirement.getWeeklyPeriods() <= item.getMaxWeeklyPeriods())
                        .min(Comparator.comparing((TeacherLoadRegistration item) ->
                                        !containsCsv(item.getPreferredGradeLevels(),
                                                normalizeGrade(schoolClass.getGradeLevel())))
                                .thenComparingDouble(item -> projected.getOrDefault(item.getTeacherId(), 0)
                                        / (double) item.getMaxWeeklyPeriods())
                                .thenComparing(TeacherLoadRegistration::getTeacherName)).orElse(null);
                if (selected == null) {
                    String message = "Thiếu giáo viên đúng chuyên môn hoặc không còn đủ tải dạy";
                    items.add(new AutoAssignmentItem(schoolClass.getId(), schoolClass.getCode(),
                            schoolClass.getGradeLevel(), requirement.getSubjectId(), requirement.getSubjectName(),
                            requirement.getWeeklyPeriods(), null, null, 0, "UNASSIGNED", message));
                    warnings.add(schoolClass.getCode() + " · " + requirement.getSubjectName() + ": " + message);
                    unassignedCount++;
                    continue;
                }
                int nextLoad = projected.getOrDefault(selected.getTeacherId(), 0) + requirement.getWeeklyPeriods();
                projected.put(selected.getTeacherId(), nextLoad);
                items.add(new AutoAssignmentItem(schoolClass.getId(), schoolClass.getCode(),
                        schoolClass.getGradeLevel(), requirement.getSubjectId(), requirement.getSubjectName(),
                        requirement.getWeeklyPeriods(), selected.getTeacherId(), selected.getTeacherName(),
                        nextLoad, "PROPOSED", "Theo chuyên môn, tải dạy và khối ưu tiên"));
                proposedCount++;
            }
        }
        boolean apply = Boolean.TRUE.equals(request.apply());
        if (apply && unassignedCount > 0 && !Boolean.TRUE.equals(request.allowPartial())) {
            throw ApiException.conflict("Còn " + unassignedCount
                    + " môn/lớp chưa tìm được giáo viên. Hãy bổ sung tải dạy trước khi áp dụng.");
        }
        if (apply) {
            for (AutoAssignmentItem item : items) if ("PROPOSED".equals(item.status())) {
                teachingAssignments.create(new SaveTeachingAssignmentRequest(item.classId(), item.subjectId(),
                        item.teacherId(), request.semesterId(), item.weeklyPeriods()), actorId);
            }
        }
        return new AutoAssignmentPlan(request.semesterId(), items.size(), existingCount,
                proposedCount, unassignedCount, apply, items, warnings);
    }

    private TeacherLoadResponse response(TeacherLoadRegistration item) {
        User teacher = users.getById(item.getTeacherId());
        int assigned = assignments.findByTeacherId(item.getTeacherId()).stream()
                .filter(a -> item.getSemesterId().equals(a.getSemesterId()))
                .mapToInt(TeachingAssignment::getWeeklyPeriods).sum();
        return new TeacherLoadResponse(item.getId(), item.getTeacherId(), teacher.getTeacherCode(),
                item.getTeacherName(), teacher.getMainSubject(), item.getSemesterId(),
                item.getMaxWeeklyPeriods(), assigned, Math.max(0, item.getMaxWeeklyPeriods() - assigned),
                list(item.getUnavailableSlots()), list(item.getPreferredGradeLevels()),
                item.getNote(), item.getReviewNote(), item.getStatus(), item.getSubmittedAt(),
                item.getReviewedAt(), item.getReviewedBy(), item.getCreatedAt(), item.getUpdatedAt());
    }

    private User requireActiveTeacher(String id) {
        User teacher = users.getById(id);
        if (!"TEACHER".equals(teacher.getRole()) || !"ACTIVE".equals(teacher.getStatus())) {
            throw ApiException.badRequest("Giáo viên không tồn tại hoặc không hoạt động");
        }
        return teacher;
    }

    private static String normalizeGrade(String value) {
        if (value == null || value.isBlank()) throw ApiException.badRequest("Khối không được để trống");
        String result = value.trim().toUpperCase(Locale.ROOT).replace("KHỐI", "").trim();
        return result.startsWith("K") ? result : "K" + result;
    }

    private static List<String> normalizeGrades(List<String> values) {
        return values == null ? List.of() : values.stream()
                .map(WorkloadPlanningService::normalizeGrade).distinct().toList();
    }

    private static String clean(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String csv(List<String> values) {
        if (values == null || values.isEmpty()) return null;
        return String.join(",", new LinkedHashSet<>(values.stream().map(String::trim)
                .filter(value -> !value.isBlank()).toList()));
    }

    private static List<String> list(String value) {
        return value == null || value.isBlank() ? List.of()
                : Arrays.stream(value.split(",")).map(String::trim).filter(item -> !item.isBlank()).toList();
    }

    private static boolean containsCsv(String value, String expected) {
        return list(value).stream().anyMatch(expected::equalsIgnoreCase);
    }
}
