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
    private final CurriculumRequirementRepository requirements;
    private final CurriculumRequirementHistoryRepository requirementHistory;
    private final TeacherLoadRegistrationRepository registrations;
    private final TeacherScheduleRestrictionRequestRepository restrictionRequests;
    private final TeacherWorkloadPolicyService workloadPolicies;
    private final TeachingAssignmentRepository assignments;
    private final TeachingAssignmentService teachingAssignments;
    private final TeachingAssignmentVersionService assignmentVersions;
    private final StructureService structure;
    private final UserService users;

    public WorkloadPlanningService(CurriculumRequirementRepository requirements,
                                   CurriculumRequirementHistoryRepository requirementHistory,
                                   TeacherLoadRegistrationRepository registrations,
                                   TeacherScheduleRestrictionRequestRepository restrictionRequests,
                                   TeacherWorkloadPolicyService workloadPolicies,
                                   TeachingAssignmentRepository assignments,
                                   TeachingAssignmentService teachingAssignments,
                                   TeachingAssignmentVersionService assignmentVersions,
                                   StructureService structure, UserService users) {
        this.requirements = requirements;
        this.requirementHistory = requirementHistory;
        this.registrations = registrations;
        this.restrictionRequests = restrictionRequests;
        this.workloadPolicies = workloadPolicies;
        this.assignments = assignments;
        this.teachingAssignments = teachingAssignments;
        this.assignmentVersions = assignmentVersions;
        this.structure = structure;
        this.users = users;
    }

    public List<CurriculumRequirement> listRequirements(String semesterId) {
        return requirements.findBySemesterId(semesterId).stream()
                .sorted(Comparator.comparing(CurriculumRequirement::getGradeLevel)
                        .thenComparing(CurriculumRequirement::getSubjectName)).toList();
    }

    public CurriculumReadiness curriculumReadiness(String semesterId) {
        Semester semester = structure.getSemester(semesterId);
        List<Subject> subjects = structure.listSubjects().stream()
                .sorted(Comparator.comparing(Subject::getName)).toList();
        List<CurriculumRequirement> configured = listRequirements(semesterId);
        List<String> operationalGrades = structure.listClasses(semester.getAcademicYearId(), null).stream()
                .map(SchoolClass::getGradeLevel).map(WorkloadPlanningService::normalizeGrade)
                .distinct().sorted().toList();
        // A curriculum may be prepared before its first class is created.  Keep those
        // grades visible in readiness so the UI can report 13/13 and 25/25 correctly.
        List<String> grades = java.util.stream.Stream.concat(
                        operationalGrades.stream(),
                        configured.stream().map(CurriculumRequirement::getGradeLevel)
                                .map(WorkloadPlanningService::normalizeGrade))
                .distinct().sorted().toList();
        List<GradeCurriculumReadiness> gradeResults = grades.stream().map(grade -> {
            List<CurriculumRequirement> rows = configured.stream()
                    .filter(item -> grade.equals(item.getGradeLevel())).toList();
            Set<String> configuredIds = rows.stream().map(CurriculumRequirement::getSubjectId)
                    .collect(java.util.stream.Collectors.toSet());
            List<MissingCurriculumSubject> missing = subjects.stream()
                    .filter(subject -> !configuredIds.contains(subject.getId()))
                    .map(subject -> new MissingCurriculumSubject(subject.getId(), subject.getName())).toList();
            int periods = rows.stream().mapToInt(CurriculumRequirement::getWeeklyPeriods).sum();
            return new GradeCurriculumReadiness(grade, subjects.size(), rows.size(), periods,
                    missing.isEmpty() && periods == TimetableRulePolicy.PERIODS_PER_WEEK, missing);
        }).toList();
        int totalPeriods = configured.stream().mapToInt(CurriculumRequirement::getWeeklyPeriods).sum();
        boolean complete = !operationalGrades.isEmpty()
                && gradeResults.stream()
                .filter(item -> operationalGrades.contains(item.gradeLevel()))
                .allMatch(GradeCurriculumReadiness::complete);
        return new CurriculumReadiness(semesterId, subjects.size(), configured.size(), totalPeriods,
                complete, gradeResults);
    }

    public List<CurriculumRequirementHistoryResponse> curriculumHistory(String semesterId) {
        structure.getSemester(semesterId);
        return requirementHistory.findTop100BySemesterIdOrderByCreatedAtDesc(semesterId).stream()
                .map(item -> new CurriculumRequirementHistoryResponse(item.getId(), item.getSemesterId(),
                        item.getGradeLevel(), item.getSubjectId(), item.getSubjectName(), item.getAction(),
                        item.getPreviousWeeklyPeriods(), item.getNewWeeklyPeriods(), item.getActorId(),
                        item.getCreatedAt())).toList();
    }

    @Transactional
    public CurriculumRequirement saveRequirement(SaveCurriculumRequirementRequest request, String actorId) {
        structure.assertSemesterWritable(request.semesterId());
        structure.getSemester(request.semesterId());
        String grade = normalizeGrade(request.gradeLevel());
        Subject subject = structure.listSubjects().stream()
                .filter(item -> item.getId().equals(request.subjectId())).findFirst()
                .orElseThrow(() -> ApiException.notFound("Môn học"));
        Instant now = Instant.now();
        Optional<CurriculumRequirement> existing = requirements
                .findBySemesterIdAndGradeLevelAndSubjectId(request.semesterId(), grade, request.subjectId());
        Integer previousPeriods = existing.map(CurriculumRequirement::getWeeklyPeriods).orElse(null);
        int currentTotal = requirements.findBySemesterId(request.semesterId()).stream()
                .filter(row -> grade.equals(row.getGradeLevel()))
                .mapToInt(CurriculumRequirement::getWeeklyPeriods).sum();
        int nextTotal = currentTotal - (previousPeriods == null ? 0 : previousPeriods) + request.weeklyPeriods();
        if (nextTotal > TimetableRulePolicy.PERIODS_PER_WEEK && nextTotal >= currentTotal) {
            throw ApiException.badRequest("Tổng định mức mỗi khối chỉ được tối đa 25 tiết/tuần");
        }
        CurriculumRequirement item = existing
                .orElseGet(() -> CurriculumRequirement.builder().id(Ids.gen("cr"))
                        .semesterId(request.semesterId()).gradeLevel(grade)
                        .subjectId(subject.getId()).subjectName(subject.getName()).createdAt(now).build());
        item.setSubjectName(subject.getName());
        item.setWeeklyPeriods(request.weeklyPeriods());
        item.setUpdatedAt(now);
        CurriculumRequirement saved = requirements.save(item);
        recordHistory(saved, previousPeriods == null ? "CREATED" : "UPDATED",
                previousPeriods, saved.getWeeklyPeriods(), actorId);
        return saved;
    }

    @Transactional
    public List<CurriculumRequirement> copyRequirements(CopyCurriculumRequirementsRequest request, String actorId) {
        structure.assertSemesterWritable(request.targetSemesterId());
        structure.getSemester(request.sourceSemesterId());
        structure.getSemester(request.targetSemesterId());
        String sourceGrade = normalizeGrade(request.sourceGradeLevel());
        String targetGrade = normalizeGrade(request.targetGradeLevel());
        List<CurriculumRequirement> sourceRows = listRequirements(request.sourceSemesterId()).stream()
                .filter(item -> sourceGrade.equals(item.getGradeLevel())).toList();
        if (sourceRows.isEmpty()) throw ApiException.badRequest("Khối nguồn chưa có định mức để sao chép");
        for (CurriculumRequirement source : sourceRows) {
            Optional<CurriculumRequirement> targetExisting = requirements
                    .findBySemesterIdAndGradeLevelAndSubjectId(request.targetSemesterId(), targetGrade,
                            source.getSubjectId());
            if (targetExisting.isPresent() && !Boolean.TRUE.equals(request.overwrite())) continue;
            Instant now = Instant.now();
            CurriculumRequirement target = targetExisting.orElseGet(() -> CurriculumRequirement.builder()
                    .id(Ids.gen("cr")).semesterId(request.targetSemesterId()).gradeLevel(targetGrade)
                    .subjectId(source.getSubjectId()).subjectName(source.getSubjectName()).createdAt(now).build());
            Integer previous = targetExisting.map(CurriculumRequirement::getWeeklyPeriods).orElse(null);
            target.setSubjectName(source.getSubjectName());
            target.setWeeklyPeriods(source.getWeeklyPeriods());
            target.setUpdatedAt(now);
            CurriculumRequirement saved = requirements.save(target);
            recordHistory(saved, "COPIED", previous, saved.getWeeklyPeriods(), actorId);
        }
        return listRequirements(request.targetSemesterId()).stream()
                .filter(item -> targetGrade.equals(item.getGradeLevel())).toList();
    }

    @Transactional
    public void deleteRequirement(String id, String actorId) {
        CurriculumRequirement item = requirements.findById(id)
                .orElseThrow(() -> ApiException.notFound("Định mức môn học"));
        structure.assertSemesterWritable(item.getSemesterId());
        requirements.delete(item);
        recordHistory(item, "DELETED", item.getWeeklyPeriods(), null, actorId);
    }

    @Transactional
    public TeacherLoadResponse mine(String teacherId, String semesterId) {
        return response(ensureRegistration(teacherId, semesterId));
    }

    @Transactional
    public List<TeacherLoadResponse> listRegistrations(String semesterId) {
        structure.getSemester(semesterId);
        users.activeUserIdsByRole("TEACHER").forEach(id -> ensureRegistration(id, semesterId));
        return registrations.findBySemesterId(semesterId).stream()
                .sorted(Comparator.comparing(TeacherLoadRegistration::getTeacherName))
                .map(this::response).toList();
    }

    @Transactional
    public SchedulingReadinessResponse schedulingReadiness(String semesterId) {
        Semester semester = structure.getSemester(semesterId);
        List<SchoolClass> classes = structure.listClasses(semester.getAcademicYearId(), null);
        List<TeacherLoadResponse> teacherLoads = listRegistrations(semesterId);
        CurriculumReadiness curriculum = curriculumReadiness(semesterId);
        List<TeachingAssignment> semesterAssignments = assignments.findAll().stream()
                .filter(item -> semesterId.equals(item.getSemesterId())).toList();
        int expectedAssignments = classes.stream().mapToInt(schoolClass -> (int) listRequirements(semesterId).stream()
                .filter(item -> normalizeGrade(schoolClass.getGradeLevel()).equals(item.getGradeLevel()))
                .filter(item -> !isSchoolWideActivity(item.getSubjectId())).count()).sum();
        int missingAssignments = Math.max(0, expectedAssignments - semesterAssignments.size());
        int expectedSlots = classes.size() * TimetableRulePolicy.PERIODS_PER_WEEK;
        int slotCount = registrations.countTimetableSlots(semesterId);
        int roomCount = structure.listRooms().size();
        int missingSpecialization = (int) teacherLoads.stream()
                .filter(item -> item.mainSubject() == null || item.mainSubject().isBlank()).count();
        int overLimit = (int) teacherLoads.stream().filter(item -> "OVER_LIMIT".equals(item.workloadStatus())).count();
        long pendingRestrictions = restrictionRequests.countBySemesterIdAndStatus(semesterId, "PENDING")
                + restrictionRequests.countBySemesterIdAndStatus(semesterId, "NEEDS_INFO");
        long approvedRestrictions = restrictionRequests.countBySemesterIdAndStatus(semesterId, "APPROVED");
        List<String> blocking = new ArrayList<>();
        List<String> advisory = new ArrayList<>();
        if (!curriculum.complete()) blocking.add("Định mức môn học chưa đầy đủ cho tất cả các khối");
        if (missingSpecialization > 0) blocking.add(missingSpecialization + " giáo viên chưa được chuẩn hóa chuyên môn");
        if (overLimit > 0) blocking.add(overLimit + " giáo viên đang vượt giới hạn tải được phê duyệt");
        if (missingAssignments > 0) blocking.add(missingAssignments + " môn–lớp chưa có giáo viên phụ trách");
        if (classes.isEmpty()) blocking.add("Học kỳ chưa có lớp để lập kế hoạch");
        if (roomCount == 0) blocking.add("Chưa có phòng học sẵn sàng");
        if (pendingRestrictions > 0) advisory.add(pendingRestrictions
                + " đề nghị hạn chế lịch đang chờ xử lý; các đề nghị này chưa ràng buộc thuật toán");
        if (approvedRestrictions > 0) advisory.add(approvedRestrictions
                + " ngoại lệ lịch đã duyệt sẽ được áp dụng như ràng buộc cứng");
        if (slotCount > 0 && slotCount < expectedSlots) advisory.add("Thời khóa biểu hiện có " + slotCount
                + "/" + expectedSlots + " tiết; cần tạo bổ sung hoặc xây dựng lại");
        return new SchedulingReadinessResponse(semesterId, curriculum.complete(), classes.size(), roomCount,
                teacherLoads.size(), missingSpecialization, overLimit, pendingRestrictions, approvedRestrictions,
                expectedAssignments, semesterAssignments.size(), missingAssignments, slotCount, expectedSlots,
                curriculum.complete() && missingSpecialization == 0 && overLimit == 0 && missingAssignments == 0,
                slotCount == expectedSlots, List.copyOf(blocking), List.copyOf(advisory));
    }

    @Transactional
    public AutoAssignmentPlan plan(AutoAssignmentRequest request, String actorId) {
        structure.assertSemesterWritable(request.semesterId());
        Semester semester = structure.getSemester(request.semesterId());
        List<SchoolClass> classes = structure.listClasses(semester.getAcademicYearId(), null);
        List<CurriculumRequirement> required = listRequirements(request.semesterId());
        CurriculumReadiness readiness = curriculumReadiness(request.semesterId());
        if (Boolean.TRUE.equals(request.apply()) && !readiness.complete()) {
            String details = readiness.grades().stream().filter(item -> !item.complete())
                    .map(item -> item.gradeLevel() + " thiếu " + item.missingSubjects().stream()
                            .map(MissingCurriculumSubject::subjectName)
                            .collect(java.util.stream.Collectors.joining(", ")))
                    .collect(java.util.stream.Collectors.joining("; "));
            throw ApiException.badRequest("Chưa thể tạo phương án vì định mức môn học chưa đầy đủ. "
                    + details + ". Hãy hoàn thiện bước 1 trước khi tiếp tục.");
        }
        if (required.isEmpty()) throw ApiException.badRequest("Chưa có định mức môn học cho học kỳ đã chọn");
        users.activeUserIdsByRole("TEACHER").forEach(id -> ensureRegistration(id, request.semesterId()));
        List<TeacherLoadRegistration> available = registrations.findBySemesterId(request.semesterId());
        if (available.isEmpty()) throw ApiException.badRequest("Chưa có giáo viên đang hoạt động để phân công");
        available.forEach(workloadPolicies::apply);
        registrations.saveAll(available);

        Map<String, User> teacherById = new HashMap<>();
        available.forEach(item -> teacherById.put(item.getTeacherId(), requireActiveTeacher(item.getTeacherId())));
        Map<String, Integer> projected = new HashMap<>();
        available.forEach(item -> projected.put(item.getTeacherId(),
                assignments.findByTeacherId(item.getTeacherId()).stream()
                        .filter(a -> request.semesterId().equals(a.getSemesterId()))
                        .filter(a -> !isClassMeeting(a.getSubjectId()))
                        .mapToInt(TeachingAssignment::getWeeklyPeriods).sum()));

        List<AutoAssignmentItem> items = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        if (!readiness.complete()) {
            String details = readiness.grades().stream().filter(item -> !item.complete())
                    .map(item -> item.gradeLevel() + " " + item.totalWeeklyPeriods() + "/"
                            + TimetableRulePolicy.PERIODS_PER_WEEK + " tiết/tuần")
                    .collect(java.util.stream.Collectors.joining("; "));
            warnings.add("Bản xem trước chưa thể phát hành do định mức chưa đủ: " + details
                    + ". Hãy hoàn thiện đủ 25 tiết/tuần cho mỗi khối.");
        }
        Map<String, TeacherLoadRegistration> approvedByTeacher = available.stream()
                .collect(java.util.stream.Collectors.toMap(TeacherLoadRegistration::getTeacherId, item -> item));
        projected.forEach((teacherId, assignedPeriods) -> {
            TeacherLoadRegistration load = approvedByTeacher.get(teacherId);
            if (load != null && assignedPeriods > load.getMaxWeeklyPeriods()) {
                warnings.add(load.getTeacherName() + " đang được phân công " + assignedPeriods
                        + "/" + load.getMaxWeeklyPeriods()
                        + " tiết. Cần điều chỉnh phân công hoặc phê duyệt dạy vượt trước khi áp dụng phương án mới.");
            }
        });
        int existingCount = 0, proposedCount = 0, unassignedCount = 0;
        for (SchoolClass schoolClass : classes) {
            for (CurriculumRequirement requirement : required.stream()
                    .filter(item -> normalizeGrade(schoolClass.getGradeLevel()).equals(item.getGradeLevel())).toList()) {
                if (isSchoolWideActivity(requirement.getSubjectId())) {
                    items.add(new AutoAssignmentItem(schoolClass.getId(), schoolClass.getCode(),
                            schoolClass.getGradeLevel(), requirement.getSubjectId(), requirement.getSubjectName(),
                            requirement.getWeeklyPeriods(), null, null, 0, "SCHOOL_WIDE",
                            "Hoạt động toàn trường: không phân công giáo viên, không tính tải dạy"));
                    continue;
                }
                var existing = assignments.findByClassIdAndSubjectIdAndSemesterId(
                        schoolClass.getId(), requirement.getSubjectId(), request.semesterId());
                if (isClassMeeting(requirement.getSubjectId())) {
                    if (schoolClass.getHomeroomTeacherId() == null || schoolClass.getHomeroomTeacherId().isBlank()) {
                        String message = "Lớp chưa có giáo viên chủ nhiệm để phụ trách Sinh hoạt lớp";
                        items.add(new AutoAssignmentItem(schoolClass.getId(), schoolClass.getCode(),
                                schoolClass.getGradeLevel(), requirement.getSubjectId(), requirement.getSubjectName(),
                                requirement.getWeeklyPeriods(), null, null, 0, "UNASSIGNED", message));
                        warnings.add(schoolClass.getCode() + " · " + requirement.getSubjectName() + ": " + message);
                        unassignedCount++;
                        continue;
                    }
                    if (existing.isPresent()) {
                        TeachingAssignment assignment = existing.get();
                        items.add(new AutoAssignmentItem(schoolClass.getId(), schoolClass.getCode(),
                                schoolClass.getGradeLevel(), requirement.getSubjectId(), requirement.getSubjectName(),
                                assignment.getWeeklyPeriods(), assignment.getTeacherId(), assignment.getTeacherName(),
                                0, "HOMEROOM", "Tự gắn giáo viên chủ nhiệm; không tính tải dạy"));
                        existingCount++;
                    } else {
                        items.add(new AutoAssignmentItem(schoolClass.getId(), schoolClass.getCode(),
                                schoolClass.getGradeLevel(), requirement.getSubjectId(), requirement.getSubjectName(),
                                requirement.getWeeklyPeriods(), schoolClass.getHomeroomTeacherId(),
                                schoolClass.getHomeroomTeacherName(), 0, "HOMEROOM",
                                "Sẽ tự gắn giáo viên chủ nhiệm; không tính tải dạy"));
                        proposedCount++;
                    }
                    continue;
                }
                if (existing.isPresent()) {
                    TeachingAssignment a = existing.get();
                    TeacherLoadRegistration existingLoad = approvedByTeacher.get(a.getTeacherId());
                    boolean overloaded = existingLoad == null
                            || projected.getOrDefault(a.getTeacherId(), a.getWeeklyPeriods())
                            > existingLoad.getMaxWeeklyPeriods();
                    items.add(new AutoAssignmentItem(schoolClass.getId(), schoolClass.getCode(),
                            schoolClass.getGradeLevel(), requirement.getSubjectId(), requirement.getSubjectName(),
                            a.getWeeklyPeriods(), a.getTeacherId(), a.getTeacherName(),
                            projected.getOrDefault(a.getTeacherId(), a.getWeeklyPeriods()),
                            overloaded ? "OVERLOAD" : "EXISTING",
                            overloaded ? "Phân công hiện tại vượt chỉ tiêu đã được phê duyệt"
                                    : "Giữ nguyên phân công hiện tại"));
                    existingCount++;
                    continue;
                }
                TeacherLoadRegistration selected = available.stream()
                        .filter(item -> teachingAssignments.teacherSupportsSubject(
                                teacherById.get(item.getTeacherId()), requirement.getSubjectId()))
                        .filter(item -> projected.getOrDefault(item.getTeacherId(), 0)
                                + requirement.getWeeklyPeriods() <= item.getMaxWeeklyPeriods())
                        .min(Comparator.comparingDouble((TeacherLoadRegistration item) ->
                                        projected.getOrDefault(item.getTeacherId(), 0)
                                        / (double) item.getMaxWeeklyPeriods())
                                .thenComparing(TeacherLoadRegistration::getTeacherName)).orElse(null);
                if (selected == null) {
                    String message = "Thiếu giáo viên đúng chuyên môn hoặc không còn chỉ tiêu theo định mức";
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
                        nextLoad, "PROPOSED", "Theo chuyên môn và tải dạy hợp lệ"));
                proposedCount++;
            }
        }
        boolean apply = Boolean.TRUE.equals(request.apply());
        boolean hasExistingOverload = items.stream().anyMatch(item -> "OVERLOAD".equals(item.status()));
        if (apply && hasExistingOverload) {
            throw ApiException.conflict("Không thể áp dụng vì còn giáo viên đang vượt chỉ tiêu được phê duyệt. "
                    + "Hãy điều chỉnh phân công hoặc phê duyệt dạy vượt trước.");
        }
        if (apply && unassignedCount > 0 && !Boolean.TRUE.equals(request.allowPartial())) {
            throw ApiException.conflict("Còn " + unassignedCount
                    + " môn/lớp chưa tìm được giáo viên. Hãy bổ sung tải dạy trước khi áp dụng.");
        }
        AssignmentVersionResponse publishedVersion = null;
        if (apply) {
            for (AutoAssignmentItem item : items) if ("PROPOSED".equals(item.status())
                    || "HOMEROOM".equals(item.status())) {
                teachingAssignments.create(new SaveTeachingAssignmentRequest(item.classId(), item.subjectId(),
                        item.teacherId(), request.semesterId(), item.weeklyPeriods()), actorId);
            }
            publishedVersion = assignmentVersions.publishCurrent(request.semesterId(),
                    "Phân công tự động " + Instant.now(), warnings, actorId);
        }
        return new AutoAssignmentPlan(request.semesterId(), items.size(), existingCount,
                proposedCount, unassignedCount, apply, items, warnings,
                publishedVersion == null ? null : publishedVersion.id(),
                publishedVersion == null ? null : publishedVersion.versionNo());
    }

    private void recordHistory(CurriculumRequirement item, String action, Integer previousPeriods,
                               Integer newPeriods, String actorId) {
        requirementHistory.save(CurriculumRequirementHistory.builder().id(Ids.gen("crh"))
                .semesterId(item.getSemesterId()).gradeLevel(item.getGradeLevel())
                .subjectId(item.getSubjectId()).subjectName(item.getSubjectName()).action(action)
                .previousWeeklyPeriods(previousPeriods).newWeeklyPeriods(newPeriods)
                .actorId(actorId).createdAt(Instant.now()).build());
    }

    private boolean isClassMeeting(String subjectId) {
        return structure.listSubjects().stream()
                .anyMatch(subject -> subject.getId().equals(subjectId) && "SHL".equalsIgnoreCase(subject.getCode()));
    }

    private boolean isSchoolWideActivity(String subjectId) {
        return structure.listSubjects().stream()
                .anyMatch(subject -> subject.getId().equals(subjectId) && "SHTT".equalsIgnoreCase(subject.getCode()));
    }

    private TeacherLoadResponse response(TeacherLoadRegistration item) {
        User teacher = users.getById(item.getTeacherId());
        workloadPolicies.apply(item);
        var snapshot = workloadPolicies.snapshot(item.getTeacherId(), item.getSemesterId());
        List<TeachingAssignment> teacherAssignments = assignments.findByTeacherId(item.getTeacherId()).stream()
                .filter(a -> item.getSemesterId().equals(a.getSemesterId())).toList();
        int assigned = teacherAssignments.stream().mapToInt(TeachingAssignment::getWeeklyPeriods).sum();
        String academicYearId = structure.getSemester(item.getSemesterId()).getAcademicYearId();
        long actualInSemester = registrations.countActualTaughtPeriods(item.getTeacherId(), item.getSemesterId());
        long actualInYear = registrations.countActualTaughtPeriodsInYear(item.getTeacherId(), academicYearId);
        int targetBalance = assigned - snapshot.targetDirectWeeklyPeriods();
        int overload = Math.max(0, assigned - item.getMaxWeeklyPeriods());
        String workloadStatus = overload > 0 ? "OVER_LIMIT"
                : targetBalance < 0 ? "UNDER_TARGET"
                : targetBalance == 0 ? "ON_TARGET" : "APPROVED_OVERTIME";
        return new TeacherLoadResponse(item.getId(), item.getTeacherId(), teacher.getTeacherCode(),
                item.getTeacherName(), teacher.getMainSubject(), item.getSemesterId(),
                snapshot.baseWeeklyPeriods(), snapshot.reductionWeeklyPeriods(),
                snapshot.convertedWeeklyPeriods(), snapshot.targetDirectWeeklyPeriods(),
                snapshot.approvedOvertimeWeeklyPeriods(), snapshot.legalWeeklyCap(),
                snapshot.annualTargetPeriods(), snapshot.teachingWeeks(), snapshot.homeroomTeacher(),
                snapshot.sourceDocument(),
                item.getStandardWeeklyPeriods(), item.getMinWeeklyPeriods(), item.getMaxWeeklyPeriods(),
                item.getMaxDailyPeriods(), item.getMaxConsecutivePeriods(),
                assigned, Math.max(0, item.getMaxWeeklyPeriods() - assigned),
                targetBalance, overload, workloadStatus,
                actualInSemester, actualInYear, Math.max(0, snapshot.annualTargetPeriods() - actualInYear),
                teacherAssignments.stream().map(TeachingAssignment::getClassCode).distinct().sorted().toList(),
                teacherAssignments.stream().map(TeachingAssignment::getSubjectName).distinct().sorted().toList(),
                restrictionRequests.countByTeacherIdAndSemesterIdAndStatus(item.getTeacherId(), item.getSemesterId(), "APPROVED"),
                restrictionRequests.countByTeacherIdAndSemesterIdAndStatus(item.getTeacherId(), item.getSemesterId(), "PENDING")
                        + restrictionRequests.countByTeacherIdAndSemesterIdAndStatus(item.getTeacherId(), item.getSemesterId(), "NEEDS_INFO"),
                list(item.getUnavailableSlots()), list(item.getPreferredGradeLevels()), list(item.getPreferredDaysOff()),
                item.getNote(), item.getReviewNote(), item.getStatus(), item.getSubmittedAt(),
                item.getReviewedAt(), item.getReviewedBy(), item.getExtendedClosesOn(),
                item.getCreatedAt(), item.getUpdatedAt());
    }

    public WorkloadPolicyResponse workloadPolicy(String academicYearId) {
        return policyResponse(workloadPolicies.policyFor(academicYearId));
    }

    @Transactional
    public WorkloadPolicyResponse saveWorkloadPolicy(SaveWorkloadPolicyRequest request, String actorId) {
        TeacherWorkloadPolicy saved = workloadPolicies.savePolicy(request.academicYearId(), request.teachingWeeks(),
                request.effectiveFrom(), request.effectiveTo(), actorId);
        refreshYearRegistrations(request.academicYearId());
        return policyResponse(saved);
    }

    public List<WorkloadAdjustmentResponse> workloadAdjustments(String academicYearId, String teacherId) {
        return workloadPolicies.listAdjustments(academicYearId, teacherId).stream()
                .map(WorkloadPlanningService::adjustmentResponse).toList();
    }

    @Transactional
    public WorkloadAdjustmentResponse saveWorkloadAdjustment(SaveWorkloadAdjustmentRequest request,
                                                             String actorId) {
        requireActiveTeacher(request.teacherId());
        TeacherWorkloadAdjustment saved = workloadPolicies.saveAdjustment(request.teacherId(),
                request.academicYearId(), request.category(), request.dutyType(), request.title(),
                request.weeklyPeriods(), request.effectiveFrom(), request.effectiveTo(),
                request.reason(), actorId);
        refreshYearRegistrations(request.academicYearId());
        return adjustmentResponse(saved);
    }

    @Transactional
    public WorkloadAdjustmentResponse revokeWorkloadAdjustment(String id,
                                                               RevokeWorkloadAdjustmentRequest request,
                                                               String actorId) {
        TeacherWorkloadAdjustment saved = workloadPolicies.revokeAdjustment(id, request.reason(), actorId);
        refreshYearRegistrations(saved.getAcademicYearId());
        return adjustmentResponse(saved);
    }

    private TeacherLoadRegistration ensureRegistration(String teacherId, String semesterId) {
        structure.assertSemesterWritable(semesterId);
        User teacher = requireActiveTeacher(teacherId);
        return workloadPolicies.ensureRegistration(teacherId, teacher.getFullName(), semesterId);
    }

    private void refreshYearRegistrations(String academicYearId) {
        Set<String> semesterIds = structure.listSemesters(academicYearId).stream()
                .map(Semester::getId).collect(java.util.stream.Collectors.toSet());
        List<TeacherLoadRegistration> rows = registrations.findAll().stream()
                .filter(item -> semesterIds.contains(item.getSemesterId())).toList();
        rows.forEach(workloadPolicies::apply);
        registrations.saveAll(rows);
    }

    private static WorkloadPolicyResponse policyResponse(TeacherWorkloadPolicy item) {
        return new WorkloadPolicyResponse(item.getId(), item.getAcademicYearId(), item.getSchoolLevel(),
                item.getBaseWeeklyPeriods(), item.getTeachingWeeks(), item.getMaxOvertimePercent(),
                item.getHomeroomReductionPeriods(), item.getEffectiveFrom(), item.getEffectiveTo(),
                item.getSourceDocument(), item.isActive(), item.getConfiguredBy(), item.getUpdatedAt());
    }

    private static WorkloadAdjustmentResponse adjustmentResponse(TeacherWorkloadAdjustment item) {
        return new WorkloadAdjustmentResponse(item.getId(), item.getTeacherId(), item.getAcademicYearId(),
                item.getCategory(), item.getDutyType(), item.getTitle(), item.getWeeklyPeriods(),
                item.getEffectiveFrom(), item.getEffectiveTo(), item.getReason(), item.getStatus(),
                item.getApprovedBy(), item.getApprovedAt(), item.getRevokedBy(), item.getRevokedAt(),
                item.getRevokeReason(), item.getCreatedAt(), item.getUpdatedAt());
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


    private static List<String> list(String value) {
        return value == null || value.isBlank() ? List.of()
                : Arrays.stream(value.split(",")).map(String::trim).filter(item -> !item.isBlank()).toList();
    }

    private static boolean containsCsv(String value, String expected) {
        return list(value).stream().anyMatch(expected::equalsIgnoreCase);
    }
}
