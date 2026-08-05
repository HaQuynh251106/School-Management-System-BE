package com.sse.app.academic.planning;

import com.sse.app.academic.planning.AcademicPlanningDtos.CurriculumItemRequest;
import com.sse.app.academic.planning.AcademicPlanningDtos.ExamScheduleRequest;
import com.sse.app.academic.planning.AcademicPlanningDtos.NewVersionRequest;
import com.sse.app.academic.planning.AcademicPlanningDtos.PlanDetail;
import com.sse.app.academic.planning.AcademicPlanningDtos.PlanReadiness;
import com.sse.app.academic.planning.AcademicPlanningDtos.PlanRequest;
import com.sse.app.academic.planning.AcademicPlanningDtos.PlanStageRequest;
import com.sse.app.academic.planning.AcademicPlanningDtos.PlanSubjectDetail;
import com.sse.app.academic.planning.AcademicPlanningDtos.PlanSubjectRequest;
import com.sse.app.academic.planning.AcademicPlanningDtos.PlanUpdateRequest;
import com.sse.app.academic.planning.AcademicPlanningDtos.SpecialWeekRequest;
import com.sse.app.academic.structure.AcademicYear;
import com.sse.app.academic.structure.Room;
import com.sse.app.academic.structure.Semester;
import com.sse.app.academic.structure.StructureService;
import com.sse.app.academic.structure.Subject;
import com.sse.app.common.ApiException;
import com.sse.app.common.Ids;
import com.sse.app.identity.User;
import com.sse.app.identity.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
public class AcademicPlanningService {
    private final AcademicTrainingPlanRepository plans;
    private final AcademicTrainingPlanSubjectRepository planSubjects;
    private final AcademicExamScheduleRepository exams;
    private final AcademicTrainingPlanStageRepository stages;
    private final AcademicCurriculumItemRepository curriculum;
    private final AcademicTrainingPlanSpecialWeekRepository specialWeeks;
    private final StructureService structure;
    private final UserRepository users;

    public AcademicPlanningService(
            AcademicTrainingPlanRepository plans,
            AcademicTrainingPlanSubjectRepository planSubjects,
            AcademicExamScheduleRepository exams,
            AcademicTrainingPlanStageRepository stages,
            AcademicCurriculumItemRepository curriculum,
            AcademicTrainingPlanSpecialWeekRepository specialWeeks,
            StructureService structure,
            UserRepository users) {
        this.plans = plans;
        this.planSubjects = planSubjects;
        this.exams = exams;
        this.stages = stages;
        this.curriculum = curriculum;
        this.specialWeeks = specialWeeks;
        this.structure = structure;
        this.users = users;
    }

    public List<AcademicTrainingPlan> listPlans(
            String academicYearId, String gradeLevel) {
        List<AcademicTrainingPlan> result = academicYearId == null
                ? plans.findAll()
                : plans.findByAcademicYearIdOrderByGradeLevel(academicYearId);
        if (gradeLevel != null && !gradeLevel.isBlank()) {
            String grade = normalizeGrade(gradeLevel);
            result = result.stream()
                    .filter(plan -> grade.equals(plan.getGradeLevel()))
                    .toList();
        }
        return result.stream()
                .sorted(Comparator
                        .comparing(AcademicTrainingPlan::getAcademicYearId)
                        .thenComparing(AcademicTrainingPlan::getGradeLevel)
                        .thenComparing(
                                AcademicTrainingPlan::getVersionNumber,
                                Comparator.reverseOrder()))
                .toList();
    }

    public AcademicTrainingPlan getPlan(String id) {
        return plans.findById(id)
                .orElseThrow(() -> ApiException.notFound("Kế hoạch giáo dục năm học"));
    }

    public AcademicTrainingPlan publishedPlan(
            String academicYearId, String gradeLevel) {
        return plans.findByAcademicYearIdAndGradeLevelOrderByVersionNumberDesc(
                        academicYearId, normalizeGrade(gradeLevel)).stream()
                .filter(item -> Set.of("PUBLISHED", "LOCKED").contains(item.getStatus()))
                .findFirst()
                .orElseThrow(() -> ApiException.conflict(
                        "Khối chưa có kế hoạch giáo dục đã công bố hoặc đã khóa"));
    }

    public List<AcademicTrainingPlanSubject> publishedPlanSubjects(
            String planId, String semesterId) {
        AcademicTrainingPlan plan = getPlan(planId);
        if (!Set.of("PUBLISHED", "LOCKED").contains(plan.getStatus())) {
            throw ApiException.conflict("Kế hoạch nguồn chưa được công bố");
        }
        return planSubjects.findByPlanIdOrderByDisplayOrderAscSubjectIdAsc(planId)
                .stream().filter(item -> semesterId.equals(item.getSemesterId()))
                .toList();
    }

    public List<AcademicCurriculumItem> curriculumLessonsByPlan(
            String planId, String semesterId, String subjectId) {
        AcademicTrainingPlan plan = getPlan(planId);
        if (!Set.of("PUBLISHED", "LOCKED").contains(plan.getStatus())) {
            throw ApiException.conflict("Kế hoạch nguồn chưa được công bố");
        }
        AcademicTrainingPlanSubject planSubject = planSubjects
                .findByPlanIdAndSemesterIdAndSubjectId(planId, semesterId, subjectId)
                .orElseThrow(() -> ApiException.notFound(
                        "Môn học trong kế hoạch đã công bố"));
        return curriculum.findByPlanSubjectIdOrderBySequenceAsc(planSubject.getId())
                .stream().filter(item -> "LESSON".equals(item.getItemType()))
                .toList();
    }

    public com.sse.app.academic.structure.SchoolClass schoolClass(String id) {
        return structure.getClass(id);
    }

    public AcademicCurriculumItem getCurriculumItem(String id) {
        return curriculum.findById(id)
                .orElseThrow(() -> ApiException.notFound("Bài học trong chương trình"));
    }

    public AcademicTrainingPlanSubject getPlanSubject(String id) {
        return planSubjects.findById(id)
                .orElseThrow(() -> ApiException.notFound("Môn trong kế hoạch giáo dục năm học"));
    }

    public AcademicTrainingPlanStage getStage(String id) {
        return stages.findById(id).orElseThrow(() -> ApiException.notFound("Giai đoạn kế hoạch"));
    }

    public AcademicTrainingPlanSpecialWeek getSpecialWeek(String id) {
        return specialWeeks.findById(id)
                .orElseThrow(() -> ApiException.notFound("Tuần kiểm tra hoặc dự phòng"));
    }

    public List<AcademicCurriculumItem> curriculumLessons(
            String academicYearId, String gradeLevel,
            String semesterId, String subjectId) {
        AcademicTrainingPlan plan = plans
                .findByAcademicYearIdAndGradeLevelOrderByVersionNumberDesc(
                        academicYearId, normalizeGrade(gradeLevel)).stream()
                .filter(item -> Set.of("PUBLISHED", "LOCKED").contains(item.getStatus()))
                .findFirst()
                .orElseThrow(() -> ApiException.conflict(
                        "Khối chưa có kế hoạch giáo dục năm học đã công bố"));
        AcademicTrainingPlanSubject planSubject = planSubjects
                .findByPlanIdAndSemesterIdAndSubjectId(
                        plan.getId(), semesterId, subjectId)
                .orElseThrow(() -> ApiException.notFound(
                        "Môn học trong kế hoạch đã công bố"));
        return curriculum.findByPlanSubjectIdOrderBySequenceAsc(
                        planSubject.getId()).stream()
                .filter(item -> "LESSON".equals(item.getItemType()))
                .toList();
    }

    public PlanDetail detail(String id) {
        AcademicTrainingPlan plan = getPlan(id);
        List<PlanSubjectDetail> subjectDetails = listSubjects(id).stream()
                .map(row -> new PlanSubjectDetail(
                        row,
                        listStages(id, row.getId()),
                        listCurriculum(id, row.getId()),
                        listSpecialWeeks(id, row.getId())))
                .toList();
        return new PlanDetail(plan, subjectDetails, listExams(id), readiness(id));
    }

    @Transactional
    public AcademicTrainingPlan createPlan(PlanRequest request) {
        return createPlan(request, null);
    }

    @Transactional
    public AcademicTrainingPlan createPlan(PlanRequest request, String actorId) {
        AcademicYear year = structure.getYear(request.academicYearId());
        String grade = normalizeGrade(request.gradeLevel());
        List<AcademicTrainingPlan> existing =
                plans.findByAcademicYearIdAndGradeLevelOrderByVersionNumberDesc(
                        year.getId(), grade);
        if (!existing.isEmpty()) {
            throw ApiException.conflict(
                    "Khối này đã có kế hoạch; hãy tạo phiên bản mới từ kế hoạch hiện có");
        }
        Instant now = Instant.now();
        return plans.save(AcademicTrainingPlan.builder()
                .id(generatedId(request.id(), "plan"))
                .academicYearId(year.getId())
                .gradeLevel(grade)
                .name(request.name().trim())
                .programId(request.programId() == null || request.programId().isBlank()
                        ? "program-gdpt-2018" : request.programId())
                .description(blankToNull(request.description()))
                .status("DRAFT")
                .versionNumber(1)
                .maxProgressGapDays(normalizeMaxGap(
                        request.maxProgressGapDays()))
                .createdBy(actorId)
                .createdAt(now)
                .updatedAt(now)
                .build());
    }

    @Transactional
    public AcademicTrainingPlan createVersion(
            String sourceId, NewVersionRequest request) {
        AcademicTrainingPlan source = getPlan(sourceId);
        List<AcademicTrainingPlan> scope =
                plans.findByAcademicYearIdAndGradeLevelOrderByVersionNumberDesc(
                        source.getAcademicYearId(), source.getGradeLevel());
        if (scope.stream().anyMatch(plan -> Set.of(
                "DRAFT", "REVISION_REQUIRED", "SUBMITTED", "APPROVED")
                .contains(plan.getStatus()))) {
            throw ApiException.conflict(
                    "Khối này đang có một phiên bản nháp; hãy hoàn tất hoặc xóa bản nháp trước");
        }
        int nextVersion = scope.stream()
                .mapToInt(AcademicTrainingPlan::getVersionNumber)
                .max().orElse(0) + 1;
        Instant now = Instant.now();
        String requestedName = request == null ? null : request.name();
        AcademicTrainingPlan target = plans.save(AcademicTrainingPlan.builder()
                .id(Ids.gen("plan"))
                .academicYearId(source.getAcademicYearId())
                .gradeLevel(source.getGradeLevel())
                .name(requestedName == null || requestedName.isBlank()
                        ? source.getName() : requestedName.trim())
                .programId(source.getProgramId())
                .description(source.getDescription())
                .status("DRAFT")
                .versionNumber(nextVersion)
                .basedOnPlanId(source.getId())
                .maxProgressGapDays(source.getMaxProgressGapDays())
                .createdAt(now)
                .updatedAt(now)
                .build());
        copyPlanContent(source, target, now);
        return target;
    }

    @Transactional
    public AcademicTrainingPlan updatePlan(
            String id, PlanUpdateRequest request) {
        AcademicTrainingPlan plan = requireEditablePlan(id);
        plan.setName(request.name().trim());
        if (request.programId() != null && !request.programId().isBlank()) {
            plan.setProgramId(request.programId());
        }
        plan.setDescription(blankToNull(request.description()));
        plan.setMaxProgressGapDays(
                normalizeMaxGap(request.maxProgressGapDays()));
        plan.setUpdatedAt(Instant.now());
        return plans.save(plan);
    }

    @Transactional
    public AcademicTrainingPlan publishPlan(String id, String actorId) {
        AcademicTrainingPlan plan = getPlan(id);
        if (!"APPROVED".equals(plan.getStatus())) {
            throw ApiException.conflict(
                    "Kế hoạch phải được kiểm tra và phê duyệt trước khi công bố");
        }
        PlanReadiness readiness = readiness(id);
        if (!readiness.ready()) {
            throw ApiException.conflict(
                    "Chưa thể công bố: " + String.join("; ", readiness.issues()));
        }
        Instant now = Instant.now();
        plans.findByAcademicYearIdAndGradeLevelOrderByVersionNumberDesc(
                        plan.getAcademicYearId(), plan.getGradeLevel())
                .stream()
                .filter(other -> !other.getId().equals(plan.getId()))
                .filter(other -> "PUBLISHED".equals(other.getStatus()))
                .forEach(other -> {
                    other.setStatus("ARCHIVED");
                    other.setLockedAt(now);
                    other.setLockedBy(actorId);
                    other.setUpdatedAt(now);
                    plans.save(other);
                });
        plan.setStatus("PUBLISHED");
        plan.setPublishedAt(now);
        plan.setPublishedBy(actorId);
        plan.setLockedAt(null);
        plan.setLockedBy(null);
        plan.setUpdatedAt(now);
        return plans.save(plan);
    }

    @Transactional
    public AcademicTrainingPlan reopenPlan(String id) {
        AcademicTrainingPlan plan = getPlan(id);
        if ("LOCKED".equals(plan.getStatus())) {
            throw ApiException.conflict(
                    "Kế hoạch đã khóa; hãy tạo phiên bản mới để điều chỉnh");
        }
        if (!"PUBLISHED".equals(plan.getStatus())) {
            throw ApiException.conflict(
                    "Chỉ kế hoạch đang công bố mới có thể thu hồi về nháp");
        }
        boolean hasDraft = plans
                .findByAcademicYearIdAndGradeLevelOrderByVersionNumberDesc(
                        plan.getAcademicYearId(), plan.getGradeLevel())
                .stream()
                .anyMatch(other -> !other.getId().equals(id)
                        && "DRAFT".equals(other.getStatus()));
        if (hasDraft) {
            throw ApiException.conflict(
                    "Khối này đã có một phiên bản nháp khác");
        }
        plan.setStatus("REVISION_REQUIRED");
        plan.setPublishedAt(null);
        plan.setPublishedBy(null);
        plan.setUpdatedAt(Instant.now());
        return plans.save(plan);
    }

    @Transactional
    public AcademicTrainingPlan lockPlan(String id, String actorId) {
        AcademicTrainingPlan plan = getPlan(id);
        if (!"PUBLISHED".equals(plan.getStatus())) {
            throw ApiException.conflict(
                    "Chỉ kế hoạch đang công bố mới có thể khóa");
        }
        Instant now = Instant.now();
        plan.setStatus("LOCKED");
        plan.setLockedAt(now);
        plan.setLockedBy(actorId);
        plan.setUpdatedAt(now);
        return plans.save(plan);
    }

    @Transactional
    public void deletePlan(String id) {
        plans.delete(requireEditablePlan(id));
    }

    public List<AcademicTrainingPlanSubject> listSubjects(String planId) {
        getPlan(planId);
        return planSubjects.findByPlanIdOrderByDisplayOrderAscSubjectIdAsc(
                planId);
    }

    @Transactional
    public AcademicTrainingPlanSubject addSubject(
            String planId, PlanSubjectRequest request) {
        AcademicTrainingPlan plan = requireEditablePlan(planId);
        Semester semester = validatePlanSemester(plan, request.semesterId());
        structure.getSubject(request.subjectId());
        planSubjects.findByPlanIdAndSemesterIdAndSubjectId(
                        planId, semester.getId(), request.subjectId())
                .ifPresent(existing -> {
                    throw ApiException.conflict(
                            "Môn học đã có trong học kỳ của kế hoạch");
                });
        validatePlanSubjectDates(request, semester);
        Instant now = Instant.now();
        return planSubjects.save(AcademicTrainingPlanSubject.builder()
                .id(generatedId(request.id(), "plansj"))
                .planId(planId)
                .semesterId(semester.getId())
                .subjectId(request.subjectId())
                .weeklyPeriods(request.weeklyPeriods())
                .totalPeriods(request.totalPeriods())
                .startDate(request.startDate())
                .endDate(request.endDate())
                .examRequired(request.examRequired() == null
                        || request.examRequired())
                .displayOrder(request.displayOrder() == null
                        ? 0 : request.displayOrder())
                .createdAt(now)
                .updatedAt(now)
                .build());
    }

    @Transactional
    public AcademicTrainingPlanSubject updateSubject(
            String planId, String id, PlanSubjectRequest request) {
        AcademicTrainingPlan plan = requireEditablePlan(planId);
        AcademicTrainingPlanSubject row = requirePlanSubject(planId, id);
        Semester semester = validatePlanSemester(plan, request.semesterId());
        structure.getSubject(request.subjectId());
        planSubjects.findByPlanIdAndSemesterIdAndSubjectId(
                        planId, semester.getId(), request.subjectId())
                .filter(other -> !other.getId().equals(id))
                .ifPresent(other -> {
                    throw ApiException.conflict(
                            "Môn học đã có trong học kỳ của kế hoạch");
                });
        validatePlanSubjectDates(request, semester);
        int allocatedStages = listStages(planId, id).stream()
                .mapToInt(AcademicTrainingPlanStage::getTargetPeriods).sum();
        int allocatedLessons = listCurriculum(planId, id).stream()
                .filter(item -> "LESSON".equals(item.getItemType()))
                .mapToInt(AcademicCurriculumItem::getPlannedPeriods).sum();
        if (allocatedStages > request.totalPeriods()
                || allocatedLessons > request.totalPeriods()) {
            throw ApiException.conflict(
                    "Tổng tiết mới nhỏ hơn số tiết đã phân bổ cho giai đoạn hoặc bài học");
        }
        row.setSemesterId(semester.getId());
        row.setSubjectId(request.subjectId());
        row.setWeeklyPeriods(request.weeklyPeriods());
        row.setTotalPeriods(request.totalPeriods());
        row.setStartDate(request.startDate());
        row.setEndDate(request.endDate());
        row.setExamRequired(request.examRequired() == null
                || request.examRequired());
        row.setDisplayOrder(request.displayOrder() == null
                ? 0 : request.displayOrder());
        row.setUpdatedAt(Instant.now());
        return planSubjects.save(row);
    }

    @Transactional
    public void deleteSubject(String planId, String id) {
        requireEditablePlan(planId);
        planSubjects.delete(requirePlanSubject(planId, id));
    }

    public List<AcademicTrainingPlanStage> listStages(
            String planId, String planSubjectId) {
        requirePlanSubject(planId, planSubjectId);
        return stages.findByPlanSubjectIdOrderBySequenceAsc(planSubjectId);
    }

    @Transactional
    public AcademicTrainingPlanStage addStage(
            String planId, String planSubjectId, PlanStageRequest request) {
        requireEditablePlan(planId);
        AcademicTrainingPlanSubject subject =
                requirePlanSubject(planId, planSubjectId);
        validateStage(subject, null, request);
        Instant now = Instant.now();
        return stages.save(AcademicTrainingPlanStage.builder()
                .id(generatedId(request.id(), "stage"))
                .planSubjectId(planSubjectId)
                .code(normalizeCode(request.code()))
                .name(request.name().trim())
                .sequence(request.sequence())
                .startDate(request.startDate())
                .endDate(request.endDate())
                .targetPeriods(request.targetPeriods())
                .description(blankToNull(request.description()))
                .createdAt(now)
                .updatedAt(now)
                .build());
    }

    @Transactional
    public AcademicTrainingPlanStage updateStage(
            String planId, String id, PlanStageRequest request) {
        requireEditablePlan(planId);
        AcademicTrainingPlanStage stage = stages.findById(id)
                .orElseThrow(() -> ApiException.notFound("Giai đoạn"));
        AcademicTrainingPlanSubject subject =
                requirePlanSubject(planId, stage.getPlanSubjectId());
        validateStage(subject, id, request);
        stage.setCode(normalizeCode(request.code()));
        stage.setName(request.name().trim());
        stage.setSequence(request.sequence());
        stage.setStartDate(request.startDate());
        stage.setEndDate(request.endDate());
        stage.setTargetPeriods(request.targetPeriods());
        stage.setDescription(blankToNull(request.description()));
        stage.setUpdatedAt(Instant.now());
        return stages.save(stage);
    }

    @Transactional
    public void deleteStage(String planId, String id) {
        requireEditablePlan(planId);
        AcademicTrainingPlanStage stage = stages.findById(id)
                .orElseThrow(() -> ApiException.notFound("Giai đoạn"));
        requirePlanSubject(planId, stage.getPlanSubjectId());
        stages.delete(stage);
    }

    public List<AcademicCurriculumItem> listCurriculum(
            String planId, String planSubjectId) {
        requirePlanSubject(planId, planSubjectId);
        return curriculum.findByPlanSubjectIdOrderBySequenceAsc(planSubjectId);
    }

    @Transactional
    public AcademicCurriculumItem addCurriculumItem(
            String planId, String planSubjectId,
            CurriculumItemRequest request) {
        requireEditablePlan(planId);
        AcademicTrainingPlanSubject subject =
                requirePlanSubject(planId, planSubjectId);
        CurriculumParent parent =
                validateCurriculum(subject, null, request);
        Instant now = Instant.now();
        return curriculum.save(AcademicCurriculumItem.builder()
                .id(generatedId(request.id(), "curr"))
                .planSubjectId(planSubjectId)
                .parentId(parent.parentId())
                .itemType(parent.itemType())
                .code(normalizeCode(request.code()))
                .title(request.title().trim())
                .sequence(request.sequence())
                .plannedPeriods("LESSON".equals(parent.itemType())
                        ? request.plannedPeriods() : 0)
                .description(blankToNull(request.description()))
                .createdAt(now)
                .updatedAt(now)
                .build());
    }

    @Transactional
    public AcademicCurriculumItem updateCurriculumItem(
            String planId, String id, CurriculumItemRequest request) {
        requireEditablePlan(planId);
        AcademicCurriculumItem item = curriculum.findById(id)
                .orElseThrow(() -> ApiException.notFound("Nội dung chương trình"));
        AcademicTrainingPlanSubject subject =
                requirePlanSubject(planId, item.getPlanSubjectId());
        CurriculumParent parent = validateCurriculum(subject, id, request);
        if (!item.getItemType().equals(parent.itemType())
                && curriculum.countByParentId(id) > 0) {
            throw ApiException.conflict(
                    "Không thể đổi loại khi nội dung đang có mục con");
        }
        item.setParentId(parent.parentId());
        item.setItemType(parent.itemType());
        item.setCode(normalizeCode(request.code()));
        item.setTitle(request.title().trim());
        item.setSequence(request.sequence());
        item.setPlannedPeriods("LESSON".equals(parent.itemType())
                ? request.plannedPeriods() : 0);
        item.setDescription(blankToNull(request.description()));
        item.setUpdatedAt(Instant.now());
        return curriculum.save(item);
    }

    @Transactional
    public void deleteCurriculumItem(String planId, String id) {
        requireEditablePlan(planId);
        AcademicCurriculumItem item = curriculum.findById(id)
                .orElseThrow(() -> ApiException.notFound("Nội dung chương trình"));
        requirePlanSubject(planId, item.getPlanSubjectId());
        curriculum.delete(item);
    }

    public List<AcademicTrainingPlanSpecialWeek> listSpecialWeeks(
            String planId, String planSubjectId) {
        requirePlanSubject(planId, planSubjectId);
        return specialWeeks
                .findByPlanSubjectIdOrderByWeekNumberAscWeekTypeAsc(
                        planSubjectId);
    }

    @Transactional
    public AcademicTrainingPlanSpecialWeek addSpecialWeek(
            String planId, String planSubjectId,
            SpecialWeekRequest request) {
        requireEditablePlan(planId);
        AcademicTrainingPlanSubject subject =
                requirePlanSubject(planId, planSubjectId);
        String type = validateSpecialWeek(subject, request);
        Instant now = Instant.now();
        return specialWeeks.save(AcademicTrainingPlanSpecialWeek.builder()
                .id(generatedId(request.id(), "week"))
                .planSubjectId(planSubjectId)
                .weekType(type)
                .weekNumber(request.weekNumber())
                .name(request.name().trim())
                .description(blankToNull(request.description()))
                .createdAt(now)
                .updatedAt(now)
                .build());
    }

    @Transactional
    public AcademicTrainingPlanSpecialWeek updateSpecialWeek(
            String planId, String id, SpecialWeekRequest request) {
        requireEditablePlan(planId);
        AcademicTrainingPlanSpecialWeek week = specialWeeks.findById(id)
                .orElseThrow(() -> ApiException.notFound("Tuần kế hoạch"));
        AcademicTrainingPlanSubject subject =
                requirePlanSubject(planId, week.getPlanSubjectId());
        String type = validateSpecialWeek(subject, request);
        week.setWeekType(type);
        week.setWeekNumber(request.weekNumber());
        week.setName(request.name().trim());
        week.setDescription(blankToNull(request.description()));
        week.setUpdatedAt(Instant.now());
        return specialWeeks.save(week);
    }

    @Transactional
    public void deleteSpecialWeek(String planId, String id) {
        requireEditablePlan(planId);
        AcademicTrainingPlanSpecialWeek week = specialWeeks.findById(id)
                .orElseThrow(() -> ApiException.notFound("Tuần kế hoạch"));
        requirePlanSubject(planId, week.getPlanSubjectId());
        specialWeeks.delete(week);
    }

    public List<AcademicExamSchedule> listExams(String planId) {
        getPlan(planId);
        return exams.findByPlanIdOrderByExamDateAscStartTimeAsc(planId);
    }

    @Transactional
    public AcademicExamSchedule addExam(
            String planId, ExamScheduleRequest request) {
        throw legacyExamWriteDisabled();
    }

    @Transactional
    public AcademicExamSchedule updateExam(
            String planId, String id, ExamScheduleRequest request) {
        throw legacyExamWriteDisabled();
    }

    @Transactional
    public void deleteExam(String planId, String id) {
        throw legacyExamWriteDisabled();
    }

    public PlanReadiness readiness(String planId) {
        AcademicTrainingPlan plan = getPlan(planId);
        List<Semester> semesters =
                structure.listSemesters(plan.getAcademicYearId());
        List<AcademicTrainingPlanSubject> rows = listSubjects(planId);
        List<String> issues = new ArrayList<>();
        Map<String, String> semesterCodes = semesters.stream()
                .collect(java.util.stream.Collectors.toMap(
                        Semester::getId, Semester::getCode));
        int stageCount = 0;
        int curriculumCount = 0;
        int specialWeekCount = 0;
        if (semesters.size() != 2) {
            issues.add("Năm học phải có đúng 2 học kỳ");
        }
        for (Semester semester : semesters) {
            if (planSubjects.countByPlanIdAndSemesterId(
                    planId, semester.getId()) == 0) {
                issues.add(semester.getName() + " chưa có môn học");
            }
        }
        for (AcademicTrainingPlanSubject row : rows) {
            String subjectName = structure.subjectName(row.getSubjectId());
            String subjectLabel = subjectName + " · "
                    + semesterCodes.getOrDefault(
                            row.getSemesterId(), row.getSemesterId());
            com.sse.app.academic.structure.Subject subject =
                    structure.getSubject(row.getSubjectId());
            if (subject != null && "EDUCATIONAL_ACTIVITY".equals(subject.getSubjectType())) {
                continue;
            }
            List<AcademicTrainingPlanStage> subjectStages =
                    listStages(planId, row.getId());
            List<AcademicCurriculumItem> items =
                    listCurriculum(planId, row.getId());
            List<AcademicTrainingPlanSpecialWeek> weeks =
                    listSpecialWeeks(planId, row.getId());
            stageCount += subjectStages.size();
            curriculumCount += items.size();
            specialWeekCount += weeks.size();

            int stagePeriods = subjectStages.stream()
                    .mapToInt(AcademicTrainingPlanStage::getTargetPeriods)
                    .sum();
            if (subjectStages.isEmpty()) {
                issues.add(subjectLabel + " chưa chia giai đoạn");
            } else if (stagePeriods != row.getTotalPeriods()) {
                issues.add(subjectLabel + ": tổng tiết các giai đoạn "
                        + stagePeriods + "/" + row.getTotalPeriods());
            }

            Set<String> itemTypes = items.stream()
                    .map(AcademicCurriculumItem::getItemType)
                    .collect(java.util.stream.Collectors.toSet());
            if (!itemTypes.containsAll(
                    Set.of("CHAPTER", "TOPIC", "LESSON"))) {
                issues.add(subjectLabel
                        + " chưa đủ Chương, Chủ đề và Bài học");
            }
            int lessonPeriods = items.stream()
                    .filter(item -> "LESSON".equals(item.getItemType()))
                    .mapToInt(AcademicCurriculumItem::getPlannedPeriods)
                    .sum();
            if (lessonPeriods != row.getTotalPeriods()) {
                issues.add(subjectLabel + ": tổng tiết bài học "
                        + lessonPeriods + "/" + row.getTotalPeriods());
            }
            if (weeks.stream().noneMatch(
                    week -> "EXAM".equals(week.getWeekType()))) {
                issues.add(subjectLabel + " chưa có tuần kiểm tra");
            }
            if (weeks.stream().noneMatch(
                    week -> "BUFFER".equals(week.getWeekType()))) {
                issues.add(subjectLabel + " chưa có tuần dự phòng");
            }
        }
        // Lịch thi chi tiết thuộc GĐ5. GĐ3 chỉ yêu cầu kế hoạch đánh giá
        // theo tuần và tuần kiểm tra, được kiểm tra bởi completion.validate().
        return new PlanReadiness(
                issues.isEmpty(), semesters.size(), rows.size(),
                0, stageCount, curriculumCount,
                specialWeekCount, plan.getVersionNumber(),
                plan.getStatus(), issues);
    }

    private void copyPlanContent(
            AcademicTrainingPlan source, AcademicTrainingPlan target,
            Instant now) {
        Map<String, String> subjectIds = new HashMap<>();
        for (AcademicTrainingPlanSubject original : listSubjects(source.getId())) {
            AcademicTrainingPlanSubject copy =
                    planSubjects.save(AcademicTrainingPlanSubject.builder()
                            .id(Ids.gen("plansj"))
                            .planId(target.getId())
                            .semesterId(original.getSemesterId())
                            .subjectId(original.getSubjectId())
                            .weeklyPeriods(original.getWeeklyPeriods())
                            .totalPeriods(original.getTotalPeriods())
                            .startDate(original.getStartDate())
                            .endDate(original.getEndDate())
                            .examRequired(original.isExamRequired())
                            .displayOrder(original.getDisplayOrder())
                            .createdAt(now)
                            .updatedAt(now)
                            .build());
            subjectIds.put(original.getId(), copy.getId());
            copyStages(original.getId(), copy.getId(), now);
            copyCurriculum(original.getId(), copy.getId(), now);
            copySpecialWeeks(original.getId(), copy.getId(), now);
        }
    }

    private ApiException legacyExamWriteDisabled() {
        return ApiException.conflict(
                "GĐ3 chỉ quản lý kế hoạch kiểm tra theo tuần. Ngày giờ, phòng và giám thị được lập duy nhất tại Khảo thí & lịch thi (GĐ5).");
    }

    private void copyStages(String sourceId, String targetId, Instant now) {
        stages.findByPlanSubjectIdOrderBySequenceAsc(sourceId)
                .forEach(original -> stages.save(
                        AcademicTrainingPlanStage.builder()
                                .id(Ids.gen("stage"))
                                .planSubjectId(targetId)
                                .code(original.getCode())
                                .name(original.getName())
                                .sequence(original.getSequence())
                                .startDate(original.getStartDate())
                                .endDate(original.getEndDate())
                                .targetPeriods(original.getTargetPeriods())
                                .description(original.getDescription())
                                .createdAt(now)
                                .updatedAt(now)
                                .build()));
    }

    private void copyCurriculum(
            String sourceId, String targetId, Instant now) {
        Map<String, String> ids = new HashMap<>();
        List<AcademicCurriculumItem> originals =
                curriculum.findByPlanSubjectIdOrderBySequenceAsc(sourceId);
        for (String type : List.of("CHAPTER", "TOPIC", "LESSON")) {
            originals.stream()
                    .filter(item -> type.equals(item.getItemType()))
                    .forEach(original -> {
                        String id = Ids.gen("curr");
                        ids.put(original.getId(), id);
                        curriculum.save(AcademicCurriculumItem.builder()
                                .id(id)
                                .planSubjectId(targetId)
                                .parentId(original.getParentId() == null
                                        ? null : ids.get(original.getParentId()))
                                .itemType(original.getItemType())
                                .code(original.getCode())
                                .title(original.getTitle())
                                .sequence(original.getSequence())
                                .plannedPeriods(original.getPlannedPeriods())
                                .description(original.getDescription())
                                .createdAt(now)
                                .updatedAt(now)
                                .build());
                    });
        }
    }

    private void copySpecialWeeks(
            String sourceId, String targetId, Instant now) {
        specialWeeks.findByPlanSubjectIdOrderByWeekNumberAscWeekTypeAsc(
                        sourceId)
                .forEach(original -> specialWeeks.save(
                        AcademicTrainingPlanSpecialWeek.builder()
                                .id(Ids.gen("week"))
                                .planSubjectId(targetId)
                                .weekType(original.getWeekType())
                                .weekNumber(original.getWeekNumber())
                                .name(original.getName())
                                .description(original.getDescription())
                                .createdAt(now)
                                .updatedAt(now)
                                .build()));
    }

    private AcademicTrainingPlan requireEditablePlan(String id) {
        AcademicTrainingPlan plan = getPlan(id);
        if (!Set.of("DRAFT", "REVISION_REQUIRED").contains(plan.getStatus())) {
            throw ApiException.conflict(
                    "Chỉ phiên bản nháp được chỉnh sửa; hãy thu hồi hoặc tạo phiên bản mới");
        }
        return plan;
    }

    private AcademicTrainingPlanSubject requirePlanSubject(
            String planId, String id) {
        return planSubjects.findById(id)
                .filter(item -> planId.equals(item.getPlanId()))
                .orElseThrow(() -> ApiException.notFound("Môn trong kế hoạch"));
    }

    private Semester validatePlanSemester(
            AcademicTrainingPlan plan, String semesterId) {
        Semester semester = structure.getSemester(semesterId);
        if (!plan.getAcademicYearId().equals(semester.getAcademicYearId())) {
            throw ApiException.badRequest(
                    "Học kỳ không thuộc năm học của kế hoạch");
        }
        return semester;
    }

    private void validatePlanSubjectDates(
            PlanSubjectRequest request, Semester semester) {
        if (request.endDate().isBefore(request.startDate())) {
            throw ApiException.badRequest(
                    "Ngày kết thúc môn phải từ ngày bắt đầu trở đi");
        }
        if (request.startDate().isBefore(semester.getStartDate())
                || request.endDate().isAfter(semester.getEndDate())) {
            throw ApiException.badRequest(
                    "Thời gian môn học phải nằm trong học kỳ");
        }
    }

    private void validateStage(
            AcademicTrainingPlanSubject subject, String currentId,
            PlanStageRequest request) {
        String code = normalizeCode(request.code());
        stages.findByPlanSubjectIdAndCodeIgnoreCase(subject.getId(), code)
                .filter(other -> !other.getId().equals(currentId))
                .ifPresent(other -> {
                    throw ApiException.conflict(
                            "Mã giai đoạn đã tồn tại trong môn học");
                });
        if (request.endDate().isBefore(request.startDate())
                || request.startDate().isBefore(subject.getStartDate())
                || request.endDate().isAfter(subject.getEndDate())) {
            throw ApiException.badRequest(
                    "Thời gian giai đoạn phải nằm trong thời gian môn học");
        }
        int allocated = stages
                .findByPlanSubjectIdOrderBySequenceAsc(subject.getId())
                .stream()
                .filter(stage -> !stage.getId().equals(currentId))
                .mapToInt(AcademicTrainingPlanStage::getTargetPeriods)
                .sum();
        if (allocated + request.targetPeriods() > subject.getTotalPeriods()) {
            throw ApiException.conflict(
                    "Tổng số tiết các giai đoạn vượt quá tổng số tiết của môn");
        }
        for (AcademicTrainingPlanStage existing : stages
                .findByPlanSubjectIdOrderBySequenceAsc(subject.getId())) {
            if (existing.getId().equals(currentId)) continue;
            if (existing.getSequence() == request.sequence()) {
                throw ApiException.conflict("Thứ tự giai đoạn đã được sử dụng");
            }
            boolean overlaps = !request.endDate().isBefore(existing.getStartDate())
                    && !request.startDate().isAfter(existing.getEndDate());
            if (overlaps) {
                throw ApiException.conflict("Thời gian giai đoạn bị chồng lấn với "
                        + existing.getCode() + " · " + existing.getName());
            }
        }
    }

    private CurriculumParent validateCurriculum(
            AcademicTrainingPlanSubject subject, String currentId,
            CurriculumItemRequest request) {
        String type = request.itemType().trim().toUpperCase(Locale.ROOT);
        if (!Set.of("CHAPTER", "TOPIC", "LESSON").contains(type)) {
            throw ApiException.badRequest(
                    "Loại nội dung phải là CHAPTER, TOPIC hoặc LESSON");
        }
        String code = normalizeCode(request.code());
        curriculum.findByPlanSubjectIdAndCodeIgnoreCase(subject.getId(), code)
                .filter(other -> !other.getId().equals(currentId))
                .ifPresent(other -> {
                    throw ApiException.conflict(
                            "Mã nội dung đã tồn tại trong môn học");
                });
        String parentId = blankToNull(request.parentId());
        if ("CHAPTER".equals(type)) {
            if (parentId != null) {
                throw ApiException.badRequest("Chương không có mục cha");
            }
        } else {
            if (parentId == null) {
                throw ApiException.badRequest(
                        "Chủ đề phải thuộc Chương và Bài học phải thuộc Chủ đề");
            }
            AcademicCurriculumItem parent = curriculum.findById(parentId)
                    .filter(item -> subject.getId().equals(
                            item.getPlanSubjectId()))
                    .orElseThrow(() -> ApiException.badRequest(
                            "Mục cha không thuộc môn học"));
            String requiredParent = "TOPIC".equals(type)
                    ? "CHAPTER" : "TOPIC";
            if (!requiredParent.equals(parent.getItemType())) {
                throw ApiException.badRequest(
                        "Chủ đề phải thuộc Chương và Bài học phải thuộc Chủ đề");
            }
            if (parent.getId().equals(currentId)) {
                throw ApiException.badRequest(
                        "Nội dung không thể là mục cha của chính nó");
            }
        }
        int allocated = curriculum
                .findByPlanSubjectIdOrderBySequenceAsc(subject.getId())
                .stream()
                .filter(item -> "LESSON".equals(item.getItemType()))
                .filter(item -> !item.getId().equals(currentId))
                .mapToInt(AcademicCurriculumItem::getPlannedPeriods)
                .sum();
        int requestedPeriods = "LESSON".equals(type)
                ? request.plannedPeriods() : 0;
        if (allocated + requestedPeriods > subject.getTotalPeriods()) {
            throw ApiException.conflict(
                    "Tổng số tiết bài học vượt quá tổng số tiết của môn");
        }
        return new CurriculumParent(parentId, type);
    }

    private String validateSpecialWeek(
            AcademicTrainingPlanSubject subject,
            SpecialWeekRequest request) {
        String type = request.weekType().trim().toUpperCase(Locale.ROOT);
        if (!Set.of("EXAM", "BUFFER").contains(type)) {
            throw ApiException.badRequest(
                    "Loại tuần phải là EXAM hoặc BUFFER");
        }
        long teachingWeeks = ChronoUnit.WEEKS.between(
                subject.getStartDate(), subject.getEndDate()) + 1;
        if (request.weekNumber() > Math.min(30, teachingWeeks)) {
            throw ApiException.badRequest(
                    "Tuần đã chọn nằm ngoài thời gian môn học");
        }
        specialWeeks.findByPlanSubjectIdAndWeekNumber(subject.getId(), request.weekNumber())
                .filter(existing -> request.id() == null || !existing.getId().equals(request.id()))
                .ifPresent(existing -> {
                    throw ApiException.conflict("Tuần " + request.weekNumber()
                            + " đã được đánh dấu là "
                            + ("EXAM".equals(existing.getWeekType())
                            ? "tuần kiểm tra" : "tuần dự phòng"));
                });
        return type;
    }

    private void validateExam(
            AcademicTrainingPlan plan, String currentId,
            ExamScheduleRequest request) {
        Semester semester =
                validatePlanSemester(plan, request.semesterId());
        Subject subject = structure.getSubject(request.subjectId());
        if (!subject.isActive()) {
            throw ApiException.badRequest("Môn học đã ngừng sử dụng");
        }
        if (request.examDate().isBefore(semester.getStartDate())
                || request.examDate().isAfter(semester.getEndDate())) {
            throw ApiException.badRequest(
                    "Ngày thi phải nằm trong học kỳ");
        }
        planSubjects.findByPlanIdAndSemesterIdAndSubjectId(
                        plan.getId(), semester.getId(), subject.getId())
                .orElseThrow(() -> ApiException.badRequest(
                        "Môn thi chưa có trong kế hoạch học kỳ"));
        if (request.roomId() != null && !request.roomId().isBlank()) {
            Room room = structure.getRoom(request.roomId());
            if (!room.isActive()) {
                throw ApiException.badRequest(
                        "Phòng học đã ngừng sử dụng");
            }
        }
        if (request.proctorTeacherId() != null
                && !request.proctorTeacherId().isBlank()) {
            User proctor = users.findById(request.proctorTeacherId())
                    .orElseThrow(() -> ApiException.notFound("Giám thị"));
            if (!"TEACHER".equals(proctor.getRole())
                    || !"ACTIVE".equals(proctor.getStatus())) {
                throw ApiException.badRequest(
                        "Giám thị phải là giáo viên đang hoạt động");
            }
        }
        LocalTime requestedEnd = request.startTime()
                .plusMinutes(request.durationMinutes());
        for (AcademicExamSchedule existing
                : exams.findByExamDate(request.examDate())) {
            if (existing.getId().equals(currentId)
                    || "CANCELLED".equals(existing.getStatus())) continue;
            LocalTime existingEnd = existing.getStartTime()
                    .plusMinutes(existing.getDurationMinutes());
            boolean overlaps = request.startTime().isBefore(existingEnd)
                    && existing.getStartTime().isBefore(requestedEnd);
            if (!overlaps) continue;
            if (request.roomId() != null
                    && request.roomId().equals(existing.getRoomId())) {
                throw ApiException.conflict(
                        "Phòng thi đã được sử dụng trong khung giờ này");
            }
            if (request.proctorTeacherId() != null
                    && request.proctorTeacherId().equals(
                    existing.getProctorTeacherId())) {
                throw ApiException.conflict(
                        "Giám thị đã có lịch coi thi trong khung giờ này");
            }
        }
    }

    private int normalizeMaxGap(Integer value) {
        return value == null ? 2 : value;
    }

    private String normalizeGrade(String value) {
        String grade = value == null ? ""
                : value.trim().toUpperCase(Locale.ROOT);
        if (grade.matches("10|11|12")) grade = "K" + grade;
        if (!Set.of("K10", "K11", "K12").contains(grade)) {
            throw ApiException.badRequest(
                    "Khối chỉ được là K10, K11 hoặc K12");
        }
        return grade;
    }

    private String normalizeExamStatus(String value) {
        String status = value == null || value.isBlank()
                ? "PLANNED" : value.trim().toUpperCase(Locale.ROOT);
        if (!Set.of("PLANNED", "CONFIRMED", "CANCELLED")
                .contains(status)) {
            throw ApiException.badRequest(
                    "Trạng thái lịch thi không hợp lệ");
        }
        return status;
    }

    private String normalizeCode(String value) {
        return value.trim().toUpperCase(Locale.ROOT);
    }

    private String generatedId(String requested, String prefix) {
        return requested == null || requested.isBlank()
                ? Ids.gen(prefix) : requested;
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private record CurriculumParent(String parentId, String itemType) {}
}
