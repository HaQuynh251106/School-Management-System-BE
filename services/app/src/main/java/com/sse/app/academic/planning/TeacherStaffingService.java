package com.sse.app.academic.planning;

import com.sse.app.academic.planning.TeacherStaffingDtos.StaffingPolicyDto;
import com.sse.app.academic.planning.TeacherStaffingDtos.StaffingPolicyRequest;
import com.sse.app.academic.planning.TeacherStaffingDtos.SubjectStaffingRow;
import com.sse.app.academic.planning.TeacherStaffingDtos.TeacherStaffingAnalysis;
import com.sse.app.academic.structure.SchoolClass;
import com.sse.app.academic.structure.Semester;
import com.sse.app.academic.structure.StructureService;
import com.sse.app.academic.structure.Subject;
import com.sse.app.academic.teaching.TeacherClassSubject;
import com.sse.app.academic.teaching.TeachingAssignmentRepository;
import com.sse.app.common.ApiException;
import com.sse.app.common.Ids;
import com.sse.app.identity.UserDto;
import com.sse.app.identity.UserService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
public class TeacherStaffingService {
    private final TeacherStaffingPolicyRepository policies;
    private final TeacherSubjectCapabilityRepository capabilities;
    private final EducationProgramSubjectRepository programSubjects;
    private final TeachingAssignmentRepository assignments;
    private final AcademicPlanningService planning;
    private final EducationPlanningCatalogService catalogs;
    private final StructureService structure;
    private final UserService users;

    public TeacherStaffingService(
            TeacherStaffingPolicyRepository policies,
            TeacherSubjectCapabilityRepository capabilities,
            EducationProgramSubjectRepository programSubjects,
            TeachingAssignmentRepository assignments,
            AcademicPlanningService planning,
            EducationPlanningCatalogService catalogs,
            StructureService structure,
            UserService users) {
        this.policies = policies;
        this.capabilities = capabilities;
        this.programSubjects = programSubjects;
        this.assignments = assignments;
        this.planning = planning;
        this.catalogs = catalogs;
        this.structure = structure;
        this.users = users;
    }

    public StaffingPolicyDto policy(String academicYearId) {
        structure.getYear(academicYearId);
        return toPolicyDto(policyEntity(academicYearId));
    }

    @Transactional
    public StaffingPolicyDto savePolicy(
            String academicYearId, StaffingPolicyRequest request) {
        structure.getYear(academicYearId);
        String schoolType = TeacherStaffingRules.normalizeSchoolType(request.schoolType());
        Instant now = Instant.now();
        TeacherStaffingPolicy row = policies.findByAcademicYearId(academicYearId)
                .orElseGet(() -> TeacherStaffingPolicy.builder()
                        .id(Ids.gen("staffing-policy"))
                        .academicYearId(academicYearId)
                        .createdAt(now)
                        .build());
        row.setSchoolType(schoolType);
        row.setWeeklyTeachingNorm(request.weeklyTeachingNorm());
        row.setTeachingWeeks(request.teachingWeeks());
        row.setTeacherClassRatio(TeacherStaffingRules.ratioFor(schoolType));
        row.setUpdatedAt(now);
        return toPolicyDto(policies.save(row));
    }

    public TeacherStaffingAnalysis analyze(
            String academicYearId, String semesterId, String scopeGradeLevel) {
        structure.getYear(academicYearId);
        Semester selectedSemester = structure.getSemester(semesterId);
        if (!academicYearId.equals(selectedSemester.getAcademicYearId())) {
            throw ApiException.badRequest("Học kỳ không thuộc năm học đã chọn");
        }
        String grade = normalizeGrade(scopeGradeLevel);
        TeacherStaffingPolicy policy = policyEntity(academicYearId);
        List<SchoolClass> schoolClasses = activeClasses(
                structure.listClasses(academicYearId, null));
        List<SchoolClass> scopeClasses = schoolClasses.stream()
                .filter(item -> grade == null || grade.equals(item.getGradeLevel()))
                .toList();
        Set<String> scopeClassIds = scopeClasses.stream()
                .map(SchoolClass::getId).collect(java.util.stream.Collectors.toSet());

        List<UserDto> activeTeachers = users.list("TEACHER", null, null).stream()
                .filter(item -> "ACTIVE".equals(item.status()))
                .toList();
        Set<String> activeTeacherIds = activeTeachers.stream()
                .map(UserDto::id).collect(java.util.stream.Collectors.toSet());
        List<TeacherClassSubject> selectedAssignments = assignments
                .findBySemesterIdAndStatus(semesterId, "ACTIVE").stream()
                .filter(item -> scopeClassIds.contains(item.getClassId()))
                .toList();

        Map<String, Demand> demandBySubject = new LinkedHashMap<>();
        Set<String> scopeGrades = scopeClasses.stream().map(SchoolClass::getGradeLevel)
                .collect(java.util.stream.Collectors.toSet());
        for (String targetGrade : scopeGrades.stream().sorted().toList()) {
            AcademicTrainingPlan plan = planning.publishedPlan(academicYearId, targetGrade);
            List<AcademicTrainingPlanSubject> planRows = planning.listSubjects(plan.getId());
            Map<String, EducationProgramSubject> configBySubject = new HashMap<>();
            if (plan.getProgramId() != null && !plan.getProgramId().isBlank()) {
                programSubjects.findByProgramIdAndGradeLevelOrderBySubjectIdAsc(
                                plan.getProgramId(), targetGrade)
                        .forEach(item -> configBySubject.put(item.getSubjectId(), item));
            }
            for (SchoolClass schoolClass : scopeClasses.stream()
                    .filter(item -> targetGrade.equals(item.getGradeLevel())).toList()) {
                for (AcademicTrainingPlanSubject planRow : planRows) {
                    if (!catalogs.subjectAppliesToClass(plan.getProgramId(), targetGrade,
                            schoolClass.getId(), planRow.getSubjectId())) continue;
                    Subject subject = structure.getSubject(planRow.getSubjectId());
                    EducationProgramSubject config = configBySubject.get(planRow.getSubjectId());
                    String subjectType = config == null || config.getSubjectType() == null
                            ? normalizeSubjectType(subject.getSubjectType())
                            : normalizeSubjectType(config.getSubjectType());
                    Demand demand = demandBySubject.computeIfAbsent(subject.getId(), ignored ->
                            new Demand(subject.getId(), subject.getCode(), subject.getName(), subjectType));
                    demand.classIds.add(schoolClass.getId());
                    demand.annualPeriods += planRow.getTotalPeriods();
                    demand.weeklyBySemester.merge(planRow.getSemesterId(),
                            planRow.getWeeklyPeriods(), Integer::sum);
                    demand.periodsBySemester.merge(planRow.getSemesterId(),
                            planRow.getTotalPeriods(), Integer::sum);
                }
            }
        }

        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        List<SubjectStaffingRow> rows = demandBySubject.values().stream()
                .sorted(Comparator.comparing(Demand::subjectName,
                        Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)))
                .map(demand -> toSubjectRow(demand, semesterId, policy,
                        activeTeacherIds, selectedAssignments, errors))
                .toList();

        int minimumForSemester = rows.stream().filter(SubjectStaffingRow::countedAsSubjectTeacher)
                .mapToInt(SubjectStaffingRow::minimumTeachersForSemester).sum();
        int minimumForYear = rows.stream().filter(SubjectStaffingRow::countedAsSubjectTeacher)
                .mapToInt(SubjectStaffingRow::minimumTeachersForYear).sum();
        BigDecimal maximumFte = TeacherStaffingRules.maximumTeacherFte(
                schoolClasses.size(), policy.getTeacherClassRatio());
        int maximumWholeTeachers = TeacherStaffingRules.maximumWholeTeachers(
                schoolClasses.size(), policy.getTeacherClassRatio());
        boolean withinCeiling = activeTeachers.size() <= maximumWholeTeachers;
        if (!withinCeiling) {
            warnings.add("Hiện có " + activeTeachers.size() + " giáo viên, vượt trần nguyên người "
                    + maximumWholeTeachers + " theo tỷ lệ " + policy.getTeacherClassRatio()
                    + " giáo viên/lớp. Ban giám hiệu và nhân viên hỗ trợ không tính trong trần này.");
        }
        if (minimumForYear > maximumWholeTeachers) {
            errors.add("Kế hoạch cần tối thiểu " + minimumForYear
                    + " giáo viên bộ môn nhưng trần của loại trường chỉ là "
                    + maximumWholeTeachers + " giáo viên.");
        }

        return new TeacherStaffingAnalysis(
                academicYearId, semesterId, grade,
                schoolClasses.size(), scopeClasses.size(), activeTeachers.size(),
                minimumForSemester, minimumForYear, maximumFte, maximumWholeTeachers,
                withinCeiling, errors.isEmpty(),
                rows.stream().mapToInt(SubjectStaffingRow::annualPeriods).sum(),
                rows.stream().mapToInt(SubjectStaffingRow::selectedSemesterPeriods).sum(),
                rows.stream().mapToInt(SubjectStaffingRow::selectedWeeklyPeriods).sum(),
                toPolicyDto(policy), rows, List.copyOf(errors), List.copyOf(warnings));
    }

    private SubjectStaffingRow toSubjectRow(
            Demand demand, String semesterId, TeacherStaffingPolicy policy,
            Set<String> activeTeacherIds,
            List<TeacherClassSubject> selectedAssignments,
            List<String> errors) {
        int selectedWeekly = demand.weeklyBySemester.getOrDefault(semesterId, 0);
        int selectedPeriods = demand.periodsBySemester.getOrDefault(semesterId, 0);
        int minimumSemester = TeacherStaffingRules.minimumTeachers(
                selectedWeekly, policy.getWeeklyTeachingNorm());
        int minimumAnnualByCapacity = demand.annualPeriods <= 0 ? 0
                : (demand.annualPeriods + policy.getWeeklyTeachingNorm()
                * policy.getTeachingWeeks() - 1)
                / (policy.getWeeklyTeachingNorm() * policy.getTeachingWeeks());
        int minimumYear = Math.max(minimumAnnualByCapacity,
                demand.weeklyBySemester.values().stream()
                        .mapToInt(value -> TeacherStaffingRules.minimumTeachers(
                                value, policy.getWeeklyTeachingNorm()))
                        .max().orElse(0));
        Set<String> qualifiedIds = capabilities
                .findBySubjectIdAndActiveTrueOrderByTeacherIdAsc(demand.subjectId).stream()
                .map(TeacherSubjectCapability::getTeacherId)
                .filter(activeTeacherIds::contains)
                .collect(java.util.stream.Collectors.toSet());
        long assignedCount = selectedAssignments.stream()
                .filter(item -> demand.subjectId.equals(item.getSubjectId()))
                .map(TeacherClassSubject::getTeacherId).distinct().count();
        boolean counted = !"EDUCATIONAL_ACTIVITY".equals(demand.subjectType);
        int shortage = counted ? Math.max(0, minimumYear - qualifiedIds.size()) : 0;
        if (shortage > 0) {
            errors.add(demand.subjectName + " thiếu " + shortage + " giáo viên đúng chuyên môn (cần "
                    + minimumYear + ", hiện có " + qualifiedIds.size() + ").");
        }
        return new SubjectStaffingRow(
                demand.subjectId, demand.subjectCode, demand.subjectName, demand.subjectType,
                demand.classIds.size(), demand.annualPeriods, selectedPeriods, selectedWeekly,
                minimumSemester, minimumYear, qualifiedIds.size(), (int) assignedCount,
                shortage, counted);
    }

    private TeacherStaffingPolicy policyEntity(String academicYearId) {
        return policies.findByAcademicYearId(academicYearId).orElseGet(() ->
                TeacherStaffingPolicy.builder()
                        .id("staffing-" + academicYearId)
                        .academicYearId(academicYearId)
                        .schoolType("PUBLIC_REGULAR")
                        .weeklyTeachingNorm(17)
                        .teachingWeeks(35)
                        .teacherClassRatio(new BigDecimal("2.25"))
                        .createdAt(Instant.now())
                        .updatedAt(Instant.now())
                        .build());
    }

    private StaffingPolicyDto toPolicyDto(TeacherStaffingPolicy row) {
        return new StaffingPolicyDto(
                row.getAcademicYearId(), row.getSchoolType(),
                TeacherStaffingRules.labelFor(row.getSchoolType()),
                row.getWeeklyTeachingNorm(), row.getTeachingWeeks(),
                row.getTeacherClassRatio(), false);
    }

    private List<SchoolClass> activeClasses(List<SchoolClass> values) {
        return values.stream()
                .filter(item -> item.getStatus() == null || "ACTIVE".equals(item.getStatus()))
                .toList();
    }

    private String normalizeGrade(String value) {
        if (value == null || value.isBlank() || "ALL".equalsIgnoreCase(value)) return null;
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        if (normalized.matches("10|11|12")) normalized = "K" + normalized;
        if (!Set.of("K10", "K11", "K12").contains(normalized)) {
            throw ApiException.badRequest("Khối chỉ nhận K10, K11 hoặc K12");
        }
        return normalized;
    }

    private String normalizeSubjectType(String value) {
        return value == null || value.isBlank()
                ? "MANDATORY" : value.trim().toUpperCase(Locale.ROOT);
    }

    private static final class Demand {
        private final String subjectId;
        private final String subjectCode;
        private final String subjectName;
        private final String subjectType;
        private final Set<String> classIds = new HashSet<>();
        private final Map<String, Integer> weeklyBySemester = new HashMap<>();
        private final Map<String, Integer> periodsBySemester = new HashMap<>();
        private int annualPeriods;

        private Demand(String subjectId, String subjectCode,
                       String subjectName, String subjectType) {
            this.subjectId = subjectId;
            this.subjectCode = subjectCode;
            this.subjectName = subjectName;
            this.subjectType = subjectType;
        }

        private String subjectName() {
            return subjectName;
        }
    }
}

