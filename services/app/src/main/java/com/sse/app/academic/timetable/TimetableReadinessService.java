package com.sse.app.academic.timetable;

import com.sse.app.academic.structure.SchoolClass;
import com.sse.app.academic.structure.Semester;
import com.sse.app.academic.structure.StructureService;
import com.sse.app.academic.structure.Subject;
import com.sse.app.academic.timetable.TimetableDtos.TimetableReadiness;
import com.sse.app.academic.timetable.TimetableDtos.TimetableReadinessIssue;
import com.sse.app.identity.User;
import com.sse.app.identity.UserService;
import org.springframework.stereotype.Service;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class TimetableReadinessService {
    private final StructureService structure;
    private final EducationPlanService educationPlans;
    private final TeachingAssignmentRepository assignments;
    private final TeacherLoadRegistrationRepository teacherLoads;
    private final UserService users;
    private final TeacherSubjectQualificationRepository qualifications;

    public TimetableReadinessService(StructureService structure, EducationPlanService educationPlans,
                                      TeachingAssignmentRepository assignments,
                                      TeacherLoadRegistrationRepository teacherLoads,
                                      UserService users,
                                      TeacherSubjectQualificationRepository qualifications) {
        this.structure = structure;
        this.educationPlans = educationPlans;
        this.assignments = assignments;
        this.teacherLoads = teacherLoads;
        this.users = users;
        this.qualifications = qualifications;
    }

    public TimetableReadiness check(String semesterId, String scopeGradeLevel, List<String> requestedDays) {
        Semester semester = structure.getSemester(semesterId);
        String scope = scopeGradeLevel == null || scopeGradeLevel.isBlank()
                ? null : EducationPlanService.normalizeGrade(scopeGradeLevel);
        List<String> allowedDays = AutomaticTimetableService.normalizeAllowedDays(requestedDays);
        List<TimetableReadinessIssue> issues = new ArrayList<>();
        if ("CLOSED".equals(semester.getStatus())
                || "CLOSED".equals(structure.getYear(semester.getAcademicYearId()).getStatus())) {
            error(issues, "PERIOD_CLOSED", "SEMESTER", semesterId,
                    "Năm học hoặc học kỳ đã đóng, không thể tạo thời khóa biểu");
        }
        List<SchoolClass> classes = structure.listClasses(semester.getAcademicYearId(), scope).stream()
                .filter(item -> item.getStatus() == null || "ACTIVE".equals(item.getStatus()))
                .sorted(Comparator.comparing(SchoolClass::getCode)).toList();
        if (classes.isEmpty()) {
            error(issues, "MISSING_ACTIVE_CLASS", "SCOPE", scope,
                    "Phạm vi đã chọn chưa có lớp hoạt động");
        }
        Set<String> grades = classes.stream().map(SchoolClass::getGradeLevel)
                .map(EducationPlanService::normalizeGrade)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        List<String> sourceIds = new ArrayList<>();
        Map<String, List<CurriculumRequirement>> requirementsByGrade = new HashMap<>();
        for (String grade : grades) {
            EducationPlan plan = educationPlans.published(semester.getAcademicYearId(), grade);
            if (plan == null) {
                error(issues, "MISSING_PUBLISHED_GD3", "GRADE", grade,
                        grade + " chưa có kế hoạch GĐ3 đã công bố");
                continue;
            }
            sourceIds.add(plan.getId());
            List<CurriculumRequirement> requirements = educationPlans.requirements(plan.getId()).stream()
                    .filter(item -> semesterId.equals(item.getSemesterId())).toList();
            requirementsByGrade.put(grade, requirements);
            if (requirements.isEmpty()) {
                error(issues, "MISSING_SEMESTER_PLAN", "EDUCATION_PLAN", plan.getId(),
                        plan.getName() + " chưa có môn học cho " + semester.getName());
            }
        }
        Map<String, Subject> subjects = structure.listSubjects().stream()
                .collect(Collectors.toMap(Subject::getId, Function.identity()));
        Map<String, TeacherLoadRegistration> approvedLoads = teacherLoads.findBySemesterId(semesterId).stream()
                .filter(item -> Set.of("APPROVED", "LOCKED").contains(item.getStatus()))
                .collect(Collectors.toMap(TeacherLoadRegistration::getTeacherId, Function.identity(),
                        (left, right) -> left));
        Map<String, Integer> teacherRequired = new HashMap<>();
        Set<String> activeTeachers = new HashSet<>();
        int requiredPeriods = 0;
        int assignmentCount = 0;
        for (SchoolClass schoolClass : classes) {
            String grade = EducationPlanService.normalizeGrade(schoolClass.getGradeLevel());
            for (CurriculumRequirement requirement : requirementsByGrade.getOrDefault(grade, List.of())) {
                requiredPeriods += requirement.getWeeklyPeriods();
                TeachingAssignment assignment = assignments.findByClassIdAndSubjectIdAndSemesterId(
                        schoolClass.getId(), requirement.getSubjectId(), semesterId).orElse(null);
                if (assignment == null) {
                    error(issues, "MISSING_ASSIGNMENT", "CLASS_SUBJECT",
                            schoolClass.getId() + ":" + requirement.getSubjectId(),
                            schoolClass.getCode() + " thiếu phân công môn " + requirement.getSubjectName());
                    continue;
                }
                assignmentCount++;
                User teacher = users.getById(assignment.getTeacherId());
                if (!"TEACHER".equals(teacher.getRole()) || !"ACTIVE".equals(teacher.getStatus())) {
                    error(issues, "TEACHER_INACTIVE", "TEACHER", teacher.getId(),
                            assignment.getTeacherName() + " không phải giáo viên đang hoạt động");
                } else if (!supports(teacher, subjects.get(requirement.getSubjectId()))) {
                    error(issues, "TEACHER_EXPERTISE_MISMATCH", "TEACHER", teacher.getId(),
                            assignment.getTeacherName() + " không đúng chuyên môn "
                                    + requirement.getSubjectName() + " - " + schoolClass.getCode());
                }
                activeTeachers.add(teacher.getId());
                teacherRequired.merge(teacher.getId(), requirement.getWeeklyPeriods(), Integer::sum);
                if (!approvedLoads.containsKey(teacher.getId())) {
                    error(issues, "MISSING_APPROVED_TEACHER_LOAD", "TEACHER", teacher.getId(),
                            assignment.getTeacherName() + " chưa có tải dạy được duyệt");
                }
                if (schoolClass.getRoomCode() == null || schoolClass.getRoomCode().isBlank()) {
                    error(issues, "MISSING_MAIN_ROOM", "CLASS", schoolClass.getId(),
                            schoolClass.getCode() + " chưa được gán phòng học chính");
                }
            }
        }
        teacherRequired.forEach((teacherId, periods) -> {
            TeacherLoadRegistration load = approvedLoads.get(teacherId);
            if (load != null && periods > load.getMaxWeeklyPeriods()) {
                error(issues, "TEACHER_OVERLOAD", "TEACHER", teacherId,
                        load.getTeacherName() + " được xếp " + periods + "/"
                                + load.getMaxWeeklyPeriods() + " tiết/tuần");
            }
        });
        int availableSlots = classes.size() * allowedDays.size() * 6;
        if (requiredPeriods > availableSlots) {
            error(issues, "INSUFFICIENT_TIME_CAPACITY", "SCOPE", scope,
                    "Cần " + requiredPeriods + " tiết nhưng phạm vi chỉ có "
                            + availableSlots + " ô thời gian");
        }
        return new TimetableReadiness(semester.getAcademicYearId(), semesterId, scope,
                issues.stream().noneMatch(item -> "ERROR".equals(item.severity())),
                classes.size(), activeTeachers.size(), assignmentCount,
                requiredPeriods, availableSlots, sourceIds, issues);
    }

    private boolean supports(User teacher, Subject subject) {
        if (teacher != null && subject != null
                && qualifications.existsByTeacherIdAndSubjectId(teacher.getId(), subject.getId())) {
            return true;
        }
        if (teacher == null || subject == null || teacher.getMainSubject() == null) return false;
        Set<String> targets = new HashSet<>();
        targets.add(comparable(subject.getId()));
        targets.add(comparable(subject.getCode()));
        targets.add(comparable(subject.getName()));
        return java.util.Arrays.stream(teacher.getMainSubject().split("[,;/|]"))
                .map(TimetableReadinessService::comparable).anyMatch(targets::contains);
    }

    private static String comparable(String value) {
        if (value == null) return "";
        return Normalizer.normalize(value, Normalizer.Form.NFD).replaceAll("\\p{M}", "")
                .replace("đ", "d").replace("Đ", "D")
                .replaceAll("[^A-Za-z0-9]", "").toUpperCase(Locale.ROOT);
    }

    private static void error(List<TimetableReadinessIssue> issues, String code,
                              String type, String id, String message) {
        issues.add(new TimetableReadinessIssue("ERROR", code, type, id, message));
    }
}
