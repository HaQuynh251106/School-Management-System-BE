package com.sse.app.academic.timetable;

import com.sse.app.academic.structure.AcademicYear;
import com.sse.app.academic.structure.SchoolClass;
import com.sse.app.academic.structure.Semester;
import com.sse.app.academic.structure.StructureService;
import com.sse.app.academic.structure.Subject;
import com.sse.app.academic.timetable.EducationPlanDtos.CreateEducationPlanRequest;
import com.sse.app.academic.timetable.EducationPlanDtos.EducationPlanIssue;
import com.sse.app.academic.timetable.EducationPlanDtos.EducationPlanValidation;
import com.sse.app.common.ApiException;
import com.sse.app.common.Ids;
import com.sse.app.identity.User;
import com.sse.app.identity.UserService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.text.Normalizer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class EducationPlanService {
    private static final Set<String> EDITABLE = Set.of("DRAFT", "REVISION_REQUESTED");

    private final EducationPlanRepository plans;
    private final CurriculumRequirementRepository requirements;
    private final TeachingAssignmentRepository assignments;
    private final TeacherLoadRegistrationRepository teacherLoads;
    private final StructureService structure;
    private final UserService users;
    private final TeacherSubjectQualificationRepository qualifications;

    public EducationPlanService(EducationPlanRepository plans,
                                CurriculumRequirementRepository requirements,
                                TeachingAssignmentRepository assignments,
                                TeacherLoadRegistrationRepository teacherLoads,
                                StructureService structure, UserService users,
                                TeacherSubjectQualificationRepository qualifications) {
        this.plans = plans;
        this.requirements = requirements;
        this.assignments = assignments;
        this.teacherLoads = teacherLoads;
        this.structure = structure;
        this.users = users;
        this.qualifications = qualifications;
    }

    public List<EducationPlan> list(String academicYearId, String gradeLevel) {
        structure.getYear(academicYearId);
        List<EducationPlan> result = gradeLevel == null || gradeLevel.isBlank()
                ? plans.findByAcademicYearIdOrderByGradeLevelAscVersionNoDesc(academicYearId)
                : plans.findByAcademicYearIdAndGradeLevelOrderByVersionNoDesc(
                        academicYearId, normalizeGrade(gradeLevel));
        return result;
    }

    public EducationPlan get(String id) {
        return plans.findById(id).orElseThrow(() -> ApiException.notFound("Kế hoạch giáo dục GĐ3"));
    }

    public List<CurriculumRequirement> requirements(String planId) {
        get(planId);
        return requirements.findByPlanId(planId).stream()
                .sorted(Comparator.comparing(CurriculumRequirement::getSemesterId)
                        .thenComparing(CurriculumRequirement::getSubjectName))
                .toList();
    }

    @Transactional
    public EducationPlan create(CreateEducationPlanRequest request, String actorId) {
        AcademicYear year = structure.getYear(request.academicYearId());
        if ("CLOSED".equals(year.getStatus())) {
            throw ApiException.conflict("Không thể tạo kế hoạch GĐ3 cho năm học đã đóng");
        }
        String grade = normalizeGrade(request.gradeLevel());
        boolean gradeExists = activeClasses(year.getId(), grade).stream().findAny().isPresent();
        if (!gradeExists) throw ApiException.badRequest("Năm học chưa có lớp hoạt động thuộc " + grade);

        EducationPlan source = null;
        if (request.sourcePlanId() != null && !request.sourcePlanId().isBlank()) {
            source = get(request.sourcePlanId());
            if (!year.getId().equals(source.getAcademicYearId()) || !grade.equals(source.getGradeLevel())) {
                throw ApiException.badRequest("Phiên bản nguồn không cùng năm học và khối");
            }
            if (Set.of("PUBLISHED", "LOCKED").contains(source.getStatus())
                    && clean(request.revisionReason()) == null) {
                throw ApiException.badRequest("Cần nhập lý do khi tạo phiên bản điều chỉnh");
            }
        }
        int nextVersion = plans.findByAcademicYearIdAndGradeLevelOrderByVersionNoDesc(year.getId(), grade)
                .stream().mapToInt(EducationPlan::getVersionNo).max().orElse(0) + 1;
        Instant now = Instant.now();
        EducationPlan created = plans.save(EducationPlan.builder()
                .id(Ids.gen("ep")).academicYearId(year.getId()).gradeLevel(grade)
                .name(request.name().trim()).versionNo(nextVersion).status("DRAFT")
                .description(clean(request.description()))
                .sourcePlanId(source == null ? null : source.getId())
                .revisionReason(clean(request.revisionReason()))
                .createdBy(actorId).createdAt(now).updatedAt(now).build());
        if (source != null) cloneRequirements(source.getId(), created.getId());
        return created;
    }

    @Transactional
    public EducationPlan editablePlanForLegacy(String academicYearId, String gradeLevel, String actorId) {
        String grade = normalizeGrade(gradeLevel);
        List<EducationPlan> scoped = plans.findByAcademicYearIdAndGradeLevelOrderByVersionNoDesc(
                academicYearId, grade);
        EducationPlan editable = scoped.stream().filter(item -> EDITABLE.contains(item.getStatus()))
                .findFirst().orElse(null);
        if (editable != null) return editable;
        EducationPlan published = scoped.stream()
                .filter(item -> Set.of("PUBLISHED", "LOCKED").contains(item.getStatus()))
                .findFirst().orElse(null);
        return create(new CreateEducationPlanRequest(academicYearId, grade,
                "Kế hoạch giáo dục " + grade,
                published == null ? null : "Phiên bản điều chỉnh từ kế hoạch đã công bố",
                published == null ? null : published.getId(),
                published == null ? null : "Điều chỉnh nội dung kế hoạch giáo dục"), actorId);
    }

    public void assertEditable(String planId) {
        if (!EDITABLE.contains(get(planId).getStatus())) {
            throw ApiException.conflict(
                    "Kế hoạch đã gửi duyệt hoặc công bố không được sửa trực tiếp; hãy tạo phiên bản điều chỉnh");
        }
    }

    @Transactional
    public EducationPlan submit(String id, String actorId) {
        EducationPlan plan = get(id);
        if (!EDITABLE.contains(plan.getStatus())) {
            throw ApiException.conflict("Chỉ kế hoạch nháp hoặc được yêu cầu chỉnh sửa mới có thể gửi duyệt");
        }
        EducationPlanValidation validation = validate(id);
        if (!validation.valid()) throw ApiException.conflict(summary(validation));
        plan.setStatus("SUBMITTED");
        plan.setSubmittedBy(actorId);
        plan.setSubmittedAt(Instant.now());
        plan.setUpdatedAt(Instant.now());
        return plans.save(plan);
    }

    @Transactional
    public EducationPlan approve(String id, String actorId) {
        EducationPlan plan = get(id);
        if (!"SUBMITTED".equals(plan.getStatus())) {
            throw ApiException.conflict("Chỉ kế hoạch đã gửi duyệt mới có thể phê duyệt");
        }
        EducationPlanValidation validation = validate(id);
        if (!validation.valid()) throw ApiException.conflict(summary(validation));
        plan.setStatus("APPROVED");
        plan.setApprovedBy(actorId);
        plan.setApprovedAt(Instant.now());
        plan.setUpdatedAt(Instant.now());
        return plans.save(plan);
    }

    @Transactional
    public EducationPlan requestRevision(String id, String comment, String actorId) {
        EducationPlan plan = get(id);
        if (!Set.of("SUBMITTED", "APPROVED").contains(plan.getStatus())) {
            throw ApiException.conflict("Kế hoạch hiện không ở trạng thái có thể yêu cầu chỉnh sửa");
        }
        plan.setStatus("REVISION_REQUESTED");
        plan.setRevisionReason(clean(comment));
        plan.setApprovedBy(actorId);
        plan.setApprovedAt(Instant.now());
        plan.setUpdatedAt(Instant.now());
        return plans.save(plan);
    }

    @Transactional
    public EducationPlan publish(String id, String actorId) {
        EducationPlan plan = get(id);
        if (!"APPROVED".equals(plan.getStatus())) {
            throw ApiException.conflict("Kế hoạch phải được phê duyệt trước khi công bố");
        }
        EducationPlanValidation validation = validate(id);
        if (!validation.valid()) throw ApiException.conflict(summary(validation));
        plans.findByAcademicYearIdAndGradeLevelOrderByVersionNoDesc(
                        plan.getAcademicYearId(), plan.getGradeLevel()).stream()
                .filter(item -> "PUBLISHED".equals(item.getStatus()))
                .forEach(item -> {
                    item.setStatus("SUPERSEDED");
                    item.setUpdatedAt(Instant.now());
                    plans.save(item);
                });
        plan.setStatus("PUBLISHED");
        plan.setPublishedBy(actorId);
        plan.setPublishedAt(Instant.now());
        plan.setUpdatedAt(Instant.now());
        return plans.save(plan);
    }

    @Transactional
    public EducationPlan lock(String id, String actorId) {
        EducationPlan plan = get(id);
        if (!"PUBLISHED".equals(plan.getStatus())) {
            throw ApiException.conflict("Chỉ kế hoạch đang công bố mới có thể khóa");
        }
        plan.setStatus("LOCKED");
        plan.setUpdatedAt(Instant.now());
        return plans.save(plan);
    }

    public EducationPlanValidation validate(String planId) {
        EducationPlan plan = get(planId);
        List<EducationPlanIssue> issues = new ArrayList<>();
        List<CurriculumRequirement> planRequirements = requirements.findByPlanId(planId);
        List<Semester> semesters = structure.listSemesters(plan.getAcademicYearId());
        List<SchoolClass> classes = activeClasses(plan.getAcademicYearId(), plan.getGradeLevel());
        if (classes.isEmpty()) {
            error(issues, "MISSING_ACTIVE_CLASS", "GRADE", plan.getGradeLevel(),
                    "Không có lớp hoạt động thuộc " + plan.getGradeLevel());
        }
        for (Semester semester : semesters) {
            if (planRequirements.stream().noneMatch(item -> semester.getId().equals(item.getSemesterId()))) {
                error(issues, "MISSING_SEMESTER_PLAN", "SEMESTER", semester.getId(),
                        semester.getName() + " chưa có môn học trong kế hoạch");
            }
        }
        Map<String, Subject> subjects = structure.listSubjects().stream()
                .collect(Collectors.toMap(Subject::getId, Function.identity()));
        for (CurriculumRequirement requirement : planRequirements) {
            Semester semester;
            try {
                semester = structure.getSemester(requirement.getSemesterId());
            } catch (ApiException exception) {
                error(issues, "INVALID_SEMESTER", "REQUIREMENT", requirement.getId(), exception.getMessage());
                continue;
            }
            if (!plan.getAcademicYearId().equals(semester.getAcademicYearId())) {
                error(issues, "SEMESTER_OUTSIDE_PLAN_YEAR", "REQUIREMENT", requirement.getId(),
                        "Học kỳ không thuộc năm học của kế hoạch");
            }
            if (!plan.getGradeLevel().equals(requirement.getGradeLevel())) {
                error(issues, "GRADE_MISMATCH", "REQUIREMENT", requirement.getId(),
                        "Môn học không cùng khối với kế hoạch");
            }
            if (!subjects.containsKey(requirement.getSubjectId())) {
                error(issues, "SUBJECT_NOT_FOUND", "SUBJECT", requirement.getSubjectId(),
                        "Môn học không còn tồn tại trong danh mục");
            }
            if (requirement.getWeeklyPeriods() < 1 || requirement.getTotalPeriods() < 1) {
                error(issues, "INVALID_PERIODS", "REQUIREMENT", requirement.getId(),
                        "Số tiết tuần và tổng số tiết phải lớn hơn 0");
            }
            for (SchoolClass schoolClass : classes) {
                TeachingAssignment assignment = assignments.findByClassIdAndSubjectIdAndSemesterId(
                        schoolClass.getId(), requirement.getSubjectId(), requirement.getSemesterId()).orElse(null);
                if (assignment == null) {
                    error(issues, "MISSING_ASSIGNMENT", "CLASS_SUBJECT",
                            schoolClass.getId() + ":" + requirement.getSubjectId(),
                            schoolClass.getCode() + " thiếu phân công môn " + requirement.getSubjectName()
                                    + " trong " + semester.getName());
                    continue;
                }
                User teacher = users.getById(assignment.getTeacherId());
                if (!"ACTIVE".equals(teacher.getStatus()) || !supports(teacher, subjects.get(requirement.getSubjectId()))) {
                    error(issues, "TEACHER_EXPERTISE_MISMATCH", "TEACHER", teacher.getId(),
                            assignment.getTeacherName() + " không đúng chuyên môn hoặc không hoạt động cho môn "
                                    + requirement.getSubjectName() + " - " + schoolClass.getCode());
                }
                if (assignment.getWeeklyPeriods() != requirement.getWeeklyPeriods()) {
                    warning(issues, "ASSIGNMENT_PERIODS_IGNORED", "ASSIGNMENT", assignment.getId(),
                            "Số tiết trong phân công khác GĐ3; hệ thống sẽ dùng "
                                    + requirement.getWeeklyPeriods() + " tiết/tuần từ GĐ3");
                }
            }
        }
        int errors = (int) issues.stream().filter(item -> "ERROR".equals(item.severity())).count();
        int warnings = issues.size() - errors;
        return new EducationPlanValidation(planId, errors == 0, errors, warnings, issues);
    }

    public List<CurriculumRequirement> publishedRequirements(String semesterId) {
        Semester semester = structure.getSemester(semesterId);
        return activeClasses(semester.getAcademicYearId(), null).stream()
                .map(SchoolClass::getGradeLevel).map(EducationPlanService::normalizeGrade).distinct()
                .map(grade -> published(semester.getAcademicYearId(), grade))
                .filter(Objects::nonNull)
                .flatMap(plan -> requirements.findByPlanIdAndSemesterId(plan.getId(), semesterId).stream())
                .sorted(Comparator.comparing(CurriculumRequirement::getGradeLevel)
                        .thenComparing(CurriculumRequirement::getSubjectName))
                .toList();
    }

    public List<CurriculumRequirement> effectiveRequirements(String semesterId) {
        Semester semester = structure.getSemester(semesterId);
        List<CurriculumRequirement> result = new ArrayList<>();
        for (String grade : activeClasses(semester.getAcademicYearId(), null).stream()
                .map(SchoolClass::getGradeLevel).map(EducationPlanService::normalizeGrade).distinct().toList()) {
            List<EducationPlan> scoped = plans.findByAcademicYearIdAndGradeLevelOrderByVersionNoDesc(
                    semester.getAcademicYearId(), grade);
            EducationPlan effective = scoped.stream().filter(item -> EDITABLE.contains(item.getStatus()))
                    .findFirst().orElseGet(() -> scoped.stream()
                            .filter(item -> Set.of("PUBLISHED", "LOCKED").contains(item.getStatus()))
                            .findFirst().orElse(null));
            if (effective != null) result.addAll(requirements.findByPlanIdAndSemesterId(effective.getId(), semesterId));
        }
        return result.stream().sorted(Comparator.comparing(CurriculumRequirement::getGradeLevel)
                .thenComparing(CurriculumRequirement::getSubjectName)).toList();
    }

    public List<String> publishedPlanIds(String semesterId, String scopeGradeLevel) {
        Semester semester = structure.getSemester(semesterId);
        Set<String> grades = activeClasses(semester.getAcademicYearId(), scopeGradeLevel).stream()
                .map(SchoolClass::getGradeLevel).map(EducationPlanService::normalizeGrade)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        return grades.stream().map(grade -> published(semester.getAcademicYearId(), grade))
                .filter(Objects::nonNull).map(EducationPlan::getId).toList();
    }

    public EducationPlan published(String academicYearId, String gradeLevel) {
        EducationPlan published = plans
                .findFirstByAcademicYearIdAndGradeLevelAndStatusOrderByVersionNoDesc(
                        academicYearId, normalizeGrade(gradeLevel), "PUBLISHED")
                .orElse(null);
        if (published != null) return published;
        return plans.findFirstByAcademicYearIdAndGradeLevelAndStatusOrderByVersionNoDesc(
                academicYearId, normalizeGrade(gradeLevel), "LOCKED").orElse(null);
    }

    @Transactional
    public void bootstrapDemoPlans() {
        Map<String, List<TeachingAssignment>> grouped = assignments.findAll().stream()
                .collect(Collectors.groupingBy(item -> {
                    SchoolClass schoolClass = structure.getClass(item.getClassId());
                    Semester semester = structure.getSemester(item.getSemesterId());
                    return semester.getAcademicYearId() + "|" + normalizeGrade(schoolClass.getGradeLevel());
                }, LinkedHashMap::new, Collectors.toList()));
        for (Map.Entry<String, List<TeachingAssignment>> entry : grouped.entrySet()) {
            String[] scope = entry.getKey().split("\\|", 2);
            if (published(scope[0], scope[1]) != null) continue;
            Instant now = Instant.now();
            EducationPlan plan = plans.save(EducationPlan.builder().id(Ids.gen("ep"))
                    .academicYearId(scope[0]).gradeLevel(scope[1])
                    .name("Kế hoạch giáo dục demo " + scope[1]).versionNo(1).status("PUBLISHED")
                    .description("Kế hoạch demo được khởi tạo từ dữ liệu phân công mẫu")
                    .createdBy("SYSTEM").createdAt(now).updatedAt(now)
                    .publishedBy("SYSTEM").publishedAt(now).build());
            Map<String, TeachingAssignment> unique = entry.getValue().stream().collect(Collectors.toMap(
                    TeachingAssignment::getSubjectId, Function.identity(),
                    (left, right) -> left, LinkedHashMap::new));
            for (Semester semester : structure.listSemesters(scope[0])) {
                for (TeachingAssignment assignment : unique.values()) {
                    requirements.save(CurriculumRequirement.builder().id(Ids.gen("cr")).planId(plan.getId())
                            .semesterId(semester.getId()).gradeLevel(scope[1])
                            .subjectId(assignment.getSubjectId()).subjectName(assignment.getSubjectName())
                            .weeklyPeriods(assignment.getWeeklyPeriods())
                            .totalPeriods(Math.max(assignment.getWeeklyPeriods(), assignment.getWeeklyPeriods() * 18))
                            .startDate(semester.getStartDate()).endDate(semester.getEndDate())
                            .createdAt(now).updatedAt(now).build());
                }
            }
        }
        for (TeachingAssignment assignment : assignments.findAll()) {
            if (teacherLoads.findByTeacherIdAndSemesterId(
                    assignment.getTeacherId(), assignment.getSemesterId()).isPresent()) continue;
            Instant now = Instant.now();
            teacherLoads.save(TeacherLoadRegistration.builder().id(Ids.gen("tlr"))
                    .teacherId(assignment.getTeacherId()).teacherName(assignment.getTeacherName())
                    .semesterId(assignment.getSemesterId()).maxWeeklyPeriods(60)
                    .status("APPROVED").submittedAt(now).reviewedAt(now).reviewedBy("SYSTEM")
                    .createdAt(now).updatedAt(now).build());
        }
    }

    private List<SchoolClass> activeClasses(String academicYearId, String gradeLevel) {
        return structure.listClasses(academicYearId, gradeLevel).stream()
                .filter(item -> item.getStatus() == null || "ACTIVE".equals(item.getStatus())).toList();
    }

    private void cloneRequirements(String sourcePlanId, String targetPlanId) {
        Instant now = Instant.now();
        for (CurriculumRequirement source : requirements.findByPlanId(sourcePlanId)) {
            requirements.save(CurriculumRequirement.builder().id(Ids.gen("cr")).planId(targetPlanId)
                    .semesterId(source.getSemesterId()).gradeLevel(source.getGradeLevel())
                    .subjectId(source.getSubjectId()).subjectName(source.getSubjectName())
                    .weeklyPeriods(source.getWeeklyPeriods()).totalPeriods(source.getTotalPeriods())
                    .startDate(source.getStartDate()).endDate(source.getEndDate())
                    .examWindowStart(source.getExamWindowStart()).examWindowEnd(source.getExamWindowEnd())
                    .milestone(source.getMilestone()).createdAt(now).updatedAt(now).build());
        }
    }

    private boolean supports(User teacher, Subject subject) {
        if (teacher != null && subject != null
                && qualifications.existsByTeacherIdAndSubjectId(teacher.getId(), subject.getId())) {
            return true;
        }
        if (teacher == null || subject == null || teacher.getMainSubject() == null) return false;
        String code = comparable(subject.getCode());
        String name = comparable(subject.getName());
        String id = comparable(subject.getId());
        return java.util.Arrays.stream(teacher.getMainSubject().split("[,;/|]"))
                .map(EducationPlanService::comparable)
                .anyMatch(value -> value.equals(code) || value.equals(name) || value.equals(id));
    }

    private static String comparable(String value) {
        if (value == null) return "";
        return Normalizer.normalize(value, Normalizer.Form.NFD).replaceAll("\\p{M}", "")
                .replace("đ", "d").replace("Đ", "D")
                .replaceAll("[^A-Za-z0-9]", "").toUpperCase(Locale.ROOT);
    }

    static String normalizeGrade(String value) {
        if (value == null || value.isBlank()) throw ApiException.badRequest("Khối không được để trống");
        String result = value.trim().toUpperCase(Locale.ROOT).replace("KHỐI", "").trim();
        return result.startsWith("K") ? result : "K" + result;
    }

    private static String clean(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String summary(EducationPlanValidation validation) {
        return "Kế hoạch còn " + validation.errorCount() + " lỗi bắt buộc: "
                + validation.issues().stream().filter(item -> "ERROR".equals(item.severity()))
                .limit(3).map(EducationPlanIssue::message).collect(Collectors.joining("; "));
    }

    private static void error(List<EducationPlanIssue> issues, String code, String type,
                              String id, String message) {
        issues.add(new EducationPlanIssue("ERROR", code, type, id, message));
    }

    private static void warning(List<EducationPlanIssue> issues, String code, String type,
                                String id, String message) {
        issues.add(new EducationPlanIssue("WARNING", code, type, id, message));
    }
}
