package com.sse.app.academic.planning;

import com.sse.app.academic.planning.EducationPlanningCatalogDtos.AssignCombinationRequest;
import com.sse.app.academic.planning.EducationPlanningCatalogDtos.CombinationDetail;
import com.sse.app.academic.planning.EducationPlanningCatalogDtos.CombinationRequest;
import com.sse.app.academic.planning.EducationPlanningCatalogDtos.ProgramRequest;
import com.sse.app.academic.planning.EducationPlanningCatalogDtos.ProgramSubjectRequest;
import com.sse.app.academic.planning.EducationPlanningCatalogDtos.TeacherCapabilityRequest;
import com.sse.app.academic.planning.EducationPlanningCatalogDtos.AutoConfigureProgramResult;
import com.sse.app.academic.structure.SchoolClass;
import com.sse.app.academic.structure.Subject;
import com.sse.app.academic.teaching.TeachingAssignmentService;
import com.sse.app.academic.structure.StructureService;
import com.sse.app.common.ApiException;
import com.sse.app.common.Ids;
import com.sse.app.event.DomainEventPublisher;
import com.sse.app.identity.User;
import com.sse.app.identity.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
public class EducationPlanningCatalogService {
    private static final Set<String> PROGRAM_STATUSES = Set.of("DRAFT", "ACTIVE", "ARCHIVED");
    private static final Set<String> SUBJECT_TYPES = Set.of(
            "MANDATORY", "OPTIONAL", "SPECIALIZED", "EDUCATIONAL_ACTIVITY");

    private final EducationProgramRepository programs;
    private final EducationProgramSubjectRepository programSubjects;
    private final SubjectCombinationRepository combinations;
    private final SubjectCombinationSubjectRepository combinationSubjects;
    private final ClassSubjectCombinationRepository classCombinations;
    private final TeacherSubjectCapabilityRepository capabilities;
    private final StructureService structure;
    private final UserRepository users;
    private final DomainEventPublisher events;
    private final TeachingAssignmentService teachingAssignments;

    public EducationPlanningCatalogService(
            EducationProgramRepository programs,
            EducationProgramSubjectRepository programSubjects,
            SubjectCombinationRepository combinations,
            SubjectCombinationSubjectRepository combinationSubjects,
            ClassSubjectCombinationRepository classCombinations,
            TeacherSubjectCapabilityRepository capabilities,
            StructureService structure,
            UserRepository users,
            DomainEventPublisher events,
            TeachingAssignmentService teachingAssignments) {
        this.programs = programs;
        this.programSubjects = programSubjects;
        this.combinations = combinations;
        this.combinationSubjects = combinationSubjects;
        this.classCombinations = classCombinations;
        this.capabilities = capabilities;
        this.structure = structure;
        this.users = users;
        this.events = events;
        this.teachingAssignments = teachingAssignments;
    }

    public List<EducationProgram> listPrograms() {
        return programs.findAllByOrderByStartYearDescCodeAsc();
    }

    public EducationProgram getProgram(String id) {
        return programs.findById(id).orElseThrow(() -> ApiException.notFound("Chương trình giáo dục"));
    }

    @Transactional
    public EducationProgram saveProgram(String id, ProgramRequest request) {
        String code = request.code().trim().toUpperCase(Locale.ROOT);
        programs.findByCodeIgnoreCase(code).filter(row -> !row.getId().equals(id))
                .ifPresent(row -> { throw ApiException.conflict("Mã chương trình đã tồn tại"); });
        String status = normalize(request.status(), "DRAFT", PROGRAM_STATUSES, "Trạng thái chương trình");
        Instant now = Instant.now();
        EducationProgram row = id == null ? EducationProgram.builder()
                .id(request.id() == null || request.id().isBlank() ? Ids.gen("program") : request.id())
                .createdAt(now).build() : getProgram(id);
        List<EducationProgram> previousActive = "ACTIVE".equals(status)
                ? programs.findByStatus("ACTIVE").stream()
                        .filter(active -> !active.getId().equals(row.getId()))
                        .toList()
                : List.of();
        row.setCode(code);
        row.setName(request.name().trim());
        row.setStartYear(request.startYear());
        row.setDescription(blankToNull(request.description()));
        row.setUpdatedAt(now);
        if ("ACTIVE".equals(status)) {
            List<String> missingGrades = List.of("K10", "K11", "K12").stream()
                    .filter(grade -> programSubjects
                            .findByProgramIdAndGradeLevelOrderBySubjectIdAsc(row.getId(), grade)
                            .isEmpty())
                    .toList();
            if (!missingGrades.isEmpty()) {
                throw ApiException.conflict("Chương trình chưa cấu hình môn và số tiết cho "
                        + String.join(", ", missingGrades)
                        + ". Hãy dùng Tự động cấu hình trước khi áp dụng.");
            }
            if (!previousActive.isEmpty()) {
                previousActive.forEach(active -> {
                    active.setStatus("ARCHIVED");
                    active.setUpdatedAt(now);
                });
                programs.saveAll(previousActive);
                // Flush the archived program before writing the new ACTIVE row. PostgreSQL
                // enforces that only one education program can be active at a time.
                programs.flush();
            }
        }
        row.setStatus(status);
        return programs.save(row);
    }

    public List<EducationProgramSubject> listProgramSubjects(String programId, String gradeLevel) {
        getProgram(programId);
        return programSubjects.findByProgramIdAndGradeLevelOrderBySubjectIdAsc(
                programId, normalizeGrade(gradeLevel));
    }

    @Transactional
    public EducationProgramSubject saveProgramSubject(
            String programId, String id, ProgramSubjectRequest request) {
        getProgram(programId);
        String grade = normalizeGrade(request.gradeLevel());
        structure.getSubject(request.subjectId());
        int annualPeriods = request.semester1Periods() + request.semester2Periods();
        if (annualPeriods <= 0) {
            throw ApiException.badRequest("Tổng số tiết của hai học kỳ phải lớn hơn 0");
        }
        String type = normalize(request.subjectType(), null, SUBJECT_TYPES, "Loại môn");
        EducationProgramSubject duplicate = programSubjects
                .findByProgramIdAndGradeLevelAndSubjectId(programId, grade, request.subjectId())
                .orElse(null);
        if (duplicate != null && (id == null || !duplicate.getId().equals(id))) {
            throw ApiException.conflict("Môn đã tồn tại trong chương trình của khối này");
        }
        EducationProgramSubject row = id == null ? EducationProgramSubject.builder()
                .id(request.id() == null || request.id().isBlank() ? Ids.gen("program-subject") : request.id())
                .programId(programId).build()
                : programSubjects.findById(id).filter(item -> programId.equals(item.getProgramId()))
                .orElseThrow(() -> ApiException.notFound("Môn trong chương trình"));
        row.setGradeLevel(grade);
        row.setSubjectId(request.subjectId());
        row.setSubjectType(type);
        row.setAnnualPeriods(annualPeriods);
        row.setSemester1Periods(request.semester1Periods());
        row.setSemester2Periods(request.semester2Periods());
        row.setWeeklyPeriods(request.weeklyPeriods());
        row.setRequired(request.required());
        row.setNotes(blankToNull(request.notes()));
        return programSubjects.save(row);
    }

    @Transactional
    public void deleteProgramSubject(String programId, String id) {
        EducationProgram program = getProgram(programId);
        if (!"DRAFT".equals(program.getStatus())) {
            throw ApiException.conflict("Chỉ chương trình bản nháp mới được xóa môn");
        }
        EducationProgramSubject row = programSubjects.findById(id)
                .filter(item -> programId.equals(item.getProgramId()))
                .orElseThrow(() -> ApiException.notFound("Môn trong chương trình"));
        programSubjects.delete(row);
    }

    @Transactional
    public AutoConfigureProgramResult autoConfigureProgram(String programId, String requestedGrade) {
        EducationProgram program = getProgram(programId);
        if (!Set.of("DRAFT", "ACTIVE").contains(program.getStatus())) {
            throw ApiException.conflict("Chương trình đã lưu trữ không thể tự động cấu hình");
        }
        List<String> grades = requestedGrade == null || requestedGrade.isBlank()
                ? List.of("K10", "K11", "K12")
                : List.of(normalizeGrade(requestedGrade));
        int created = 0;
        for (String grade : grades) {
            for (Subject subject : structure.listSubjects()) {
                if (!subject.isActive() || "OPTIONAL".equals(subject.getSubjectType())
                        || "SPECIALIZED".equals(subject.getSubjectType())) continue;
                if (programSubjects.findByProgramIdAndGradeLevelAndSubjectId(
                        programId, grade, subject.getId()).isPresent()) continue;
                boolean activity = "EDUCATIONAL_ACTIVITY".equals(subject.getSubjectType());
                int semester1 = activity ? 18 : 35;
                int semester2 = activity ? 17 : 35;
                programSubjects.save(EducationProgramSubject.builder()
                        .id(Ids.gen("program-subject"))
                        .programId(programId)
                        .gradeLevel(grade)
                        .subjectId(subject.getId())
                        .subjectType(activity ? "EDUCATIONAL_ACTIVITY" : "MANDATORY")
                        .semester1Periods(semester1)
                        .semester2Periods(semester2)
                        .annualPeriods(semester1 + semester2)
                        .weeklyPeriods(activity ? 1 : 2)
                        .required(true)
                        .notes("Hệ thống đề xuất; nhà trường có thể điều chỉnh trước khi áp dụng")
                        .build());
                created++;
            }
        }
        return new AutoConfigureProgramResult(created, grades);
    }

    @Transactional
    public EducationPlanningCatalogDtos.AutoConfigureTeachersResult autoConfigureTeachers() {
        int configured = 0;
        List<Subject> schoolSubjects = structure.listSubjects();
        for (User teacher : users.findByRoleAndStatusNot("TEACHER", "DELETED").stream()
                .filter(item -> "ACTIVE".equals(item.getStatus())).toList()) {
            Subject match = schoolSubjects.stream()
                    .filter(subject -> specialtyMatches(teacher.getMainSubject(), subject))
                    .findFirst().orElse(null);
            if (match == null) continue;
            capabilities.findByTeacherIdAndActiveTrueOrderBySubjectIdAsc(teacher.getId())
                    .forEach(row -> {
                        if (!row.getSubjectId().equals(match.getId())) {
                            row.setActive(false);
                            capabilities.save(row);
                        }
                    });
            TeacherSubjectCapability capability = capabilities
                    .findByTeacherIdAndSubjectId(teacher.getId(), match.getId())
                    .orElseGet(() -> TeacherSubjectCapability.builder()
                            .id(Ids.gen("teacher-subject"))
                            .teacherId(teacher.getId()).subjectId(match.getId())
                            .createdAt(Instant.now()).build());
            capability.setActive(true);
            capability.setPrimarySubject(true);
            capabilities.save(capability);
            configured++;
        }
        int homeroomAdjusted = structure.autoAssignHomeroomTeachers()
                + teachingAssignments.rebalanceHomeroomTeachersForFixedPeriods();
        int rebalanced = teachingAssignments.rebalanceActiveAssignmentsBySpecialty();
        return new EducationPlanningCatalogDtos.AutoConfigureTeachersResult(
                configured, homeroomAdjusted, rebalanced);
    }

    private boolean specialtyMatches(String specialtyValue, Subject subject) {
        String specialty = normalizeForMatch(specialtyValue);
        String id = normalizeForMatch(subject.getId());
        String code = normalizeForMatch(subject.getCode());
        String name = normalizeForMatch(subject.getName());
        return !specialty.isBlank() && (specialty.equals(id) || specialty.equals(code)
                || specialty.equals(name) || (specialty.length() >= 3 && name.contains(specialty))
                || (name.length() >= 3 && specialty.contains(name)));
    }

    private String normalizeForMatch(String value) {
        if (value == null) return "";
        return java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "").replace('đ', 'd').replace('Đ', 'D')
                .toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "").trim();
    }

    public List<CombinationDetail> listCombinations(String academicYearId, String gradeLevel) {
        structure.getYear(academicYearId);
        return combinations.findByAcademicYearIdAndGradeLevelOrderByCodeAsc(
                        academicYearId, normalizeGrade(gradeLevel)).stream()
                .map(this::detail).toList();
    }

    @Transactional
    public CombinationDetail saveCombination(String id, CombinationRequest request) {
        structure.getYear(request.academicYearId());
        String grade = normalizeGrade(request.gradeLevel());
        String code = request.code().trim().toUpperCase(Locale.ROOT);
        Set<String> subjectIds = new HashSet<>(request.subjectIds());
        if (subjectIds.size() != request.subjectIds().size()) {
            throw ApiException.badRequest("Tổ hợp không được chứa môn trùng nhau");
        }
        subjectIds.forEach(structure::getSubject);
        combinations.findByAcademicYearIdAndGradeLevelAndCodeIgnoreCase(
                        request.academicYearId(), grade, code)
                .filter(row -> id == null || !row.getId().equals(id))
                .ifPresent(row -> { throw ApiException.conflict("Mã tổ hợp đã tồn tại trong khối"); });
        Instant now = Instant.now();
        SubjectCombination row = id == null ? SubjectCombination.builder()
                .id(request.id() == null || request.id().isBlank() ? Ids.gen("combination") : request.id())
                .createdAt(now).build()
                : combinations.findById(id).orElseThrow(() -> ApiException.notFound("Tổ hợp môn"));
        row.setCode(code);
        row.setName(request.name().trim());
        row.setAcademicYearId(request.academicYearId());
        row.setGradeLevel(grade);
        row.setExpectedClassCount(request.expectedClassCount());
        row.setMaxStudents(request.maxStudents());
        row.setStatus(normalize(request.status(), "ACTIVE", PROGRAM_STATUSES, "Trạng thái tổ hợp"));
        row.setUpdatedAt(now);
        SubjectCombination saved = combinations.save(row);
        combinationSubjects.deleteByCombinationId(saved.getId());
        subjectIds.forEach(subjectId -> combinationSubjects.save(SubjectCombinationSubject.builder()
                .id(Ids.gen("combination-subject"))
                .combinationId(saved.getId()).subjectId(subjectId).build()));
        return detail(saved);
    }

    @Transactional
    public List<ClassSubjectCombination> assignCombination(
            AssignCombinationRequest request, String actorId) {
        SubjectCombination combination = combinations.findById(request.combinationId())
                .orElseThrow(() -> ApiException.notFound("Tổ hợp môn"));
        Set<String> selectedClassIds = new HashSet<>(request.classIds());
        if (selectedClassIds.size() != request.classIds().size()) {
            throw ApiException.badRequest("Danh sách lớp không được trùng nhau");
        }
        selectedClassIds.forEach(classId -> {
            SchoolClass schoolClass = structure.getClass(classId);
            if (!combination.getAcademicYearId().equals(schoolClass.getAcademicYearId())
                    || !combination.getGradeLevel().equals(schoolClass.getGradeLevel())) {
                throw ApiException.badRequest("Lớp " + schoolClass.getCode()
                        + " không thuộc năm học và khối của tổ hợp");
            }
        });

        List<ClassSubjectCombination> removed = classCombinations
                .findByCombinationId(combination.getId()).stream()
                .filter(link -> !selectedClassIds.contains(link.getClassId()))
                .toList();
        classCombinations.deleteAll(removed);

        Instant now = Instant.now();
        return selectedClassIds.stream().map(classId -> {
            return classCombinations.save(ClassSubjectCombination.builder()
                    .classId(classId).combinationId(combination.getId())
                    .assignedAt(now).assignedBy(actorId).build());
        }).sorted((left, right) -> left.getClassId().compareTo(right.getClassId())).toList();
    }

    public List<TeacherSubjectCapability> teacherCapabilities(String teacherId) {
        requireTeacher(teacherId);
        return capabilities.findByTeacherIdAndActiveTrueOrderBySubjectIdAsc(teacherId);
    }

    @Transactional
    public List<TeacherSubjectCapability> saveTeacherCapabilities(
            TeacherCapabilityRequest request, String actorId) {
        requireTeacher(request.teacherId());
        List<TeacherSubjectCapability> previousRows = capabilities
                .findByTeacherIdAndActiveTrueOrderBySubjectIdAsc(request.teacherId());
        Set<String> previous = previousRows.stream()
                .map(TeacherSubjectCapability::getSubjectId).collect(java.util.stream.Collectors.toSet());
        String previousPrimary = previousRows.stream().filter(TeacherSubjectCapability::isPrimarySubject)
                .map(TeacherSubjectCapability::getSubjectId).findFirst().orElse(null);
        Set<String> selected = new HashSet<>(request.subjectIds());
        selected.forEach(structure::getSubject);
        capabilities.findByTeacherIdAndActiveTrueOrderBySubjectIdAsc(request.teacherId())
                .forEach(row -> {
                    if (!selected.contains(row.getSubjectId())) {
                        row.setActive(false);
                        capabilities.save(row);
                    }
                });
        List<TeacherSubjectCapability> saved = selected.stream().map(subjectId -> {
            TeacherSubjectCapability row = capabilities
                    .findByTeacherIdAndSubjectId(request.teacherId(), subjectId)
                    .orElseGet(() -> TeacherSubjectCapability.builder()
                            .id(Ids.gen("teacher-subject"))
                            .teacherId(request.teacherId()).subjectId(subjectId)
                            .createdAt(Instant.now()).build());
            row.setActive(true);
            row.setPrimarySubject(subjectId.equals(request.primarySubjectId()));
            return capabilities.save(row);
        }).toList();
        if (!previous.equals(selected)
                || !java.util.Objects.equals(previousPrimary, request.primarySubjectId())) {
            Set<String> added = new HashSet<>(selected);
            added.removeAll(previous);
            Set<String> removed = new HashSet<>(previous);
            removed.removeAll(selected);
            String currentNames = selected.stream().map(structure::subjectName).sorted()
                    .collect(java.util.stream.Collectors.joining(", "));
            String primaryName = request.primarySubjectId() == null
                    ? "chưa chọn" : structure.subjectName(request.primarySubjectId());
            String message = "Chuyên môn giảng dạy của bạn đã được cập nhật. Môn chính: "
                    + primaryName + ". Các môn có thể giảng dạy: " + currentNames + ".";
            events.publish("academic.teacher_specialty.changed", actorId,
                    "teacher_subject_capability", request.teacherId(), Map.of(
                            "teacherId", request.teacherId(),
                            "message", message,
                            "addedSubjectNames", added.stream().map(structure::subjectName).sorted().toList(),
                            "removedSubjectNames", removed.stream().map(structure::subjectName).sorted().toList()));
        }
        return saved;
    }

    public boolean classCombinationContains(String classId, String subjectId) {
        return classCombinations.findById(classId)
                .map(link -> combinationSubjects.findByCombinationIdOrderBySubjectIdAsc(link.getCombinationId())
                        .stream().anyMatch(item -> item.getSubjectId().equals(subjectId)))
                .orElse(false);
    }

    public boolean subjectAppliesToClass(
            String programId, String gradeLevel, String classId, String subjectId) {
        if (programId == null || programId.isBlank()) {
            return true;
        }
        return programSubjects.findByProgramIdAndGradeLevelAndSubjectId(
                        programId, normalizeGrade(gradeLevel), subjectId)
                .map(config -> config.isRequired()
                        || "MANDATORY".equals(config.getSubjectType())
                        || "EDUCATIONAL_ACTIVITY".equals(config.getSubjectType())
                        || classCombinationContains(classId, subjectId))
                .orElse(true);
    }

    public boolean teacherCanTeach(String teacherId, String subjectId) {
        return capabilities.findByTeacherIdAndSubjectId(teacherId, subjectId)
                .map(TeacherSubjectCapability::isActive).orElse(false);
    }

    private CombinationDetail detail(SubjectCombination row) {
        return new CombinationDetail(row,
                combinationSubjects.findByCombinationIdOrderBySubjectIdAsc(row.getId())
                        .stream().map(SubjectCombinationSubject::getSubjectId).toList(),
                classCombinations.findByCombinationId(row.getId())
                        .stream().map(ClassSubjectCombination::getClassId).toList());
    }

    private User requireTeacher(String teacherId) {
        User user = users.findById(teacherId).orElseThrow(() -> ApiException.notFound("Giáo viên"));
        if (!"TEACHER".equals(user.getRole()) || !"ACTIVE".equals(user.getStatus())) {
            throw ApiException.badRequest("Tài khoản được chọn không phải giáo viên đang hoạt động");
        }
        return user;
    }

    private String normalizeGrade(String value) {
        String grade = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (grade.matches("10|11|12")) grade = "K" + grade;
        if (!Set.of("K10", "K11", "K12").contains(grade)) {
            throw ApiException.badRequest("Khối chỉ nhận K10, K11 hoặc K12");
        }
        return grade;
    }

    private String normalize(String value, String fallback, Set<String> allowed, String label) {
        String normalized = value == null || value.isBlank() ? fallback
                : value.trim().toUpperCase(Locale.ROOT);
        if (normalized == null || !allowed.contains(normalized)) {
            throw ApiException.badRequest(label + " không hợp lệ");
        }
        return normalized;
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
