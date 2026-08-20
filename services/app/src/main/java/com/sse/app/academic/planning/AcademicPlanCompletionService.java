package com.sse.app.academic.planning;

import com.sse.app.academic.planning.AcademicPlanningDtos.AnnualSubjectSummary;
import com.sse.app.academic.planning.AcademicPlanningDtos.AssessmentPlanRequest;
import com.sse.app.academic.planning.AcademicPlanningDtos.CurriculumDistributionRequest;
import com.sse.app.academic.planning.AcademicPlanningDtos.PlanValidationReport;
import com.sse.app.academic.planning.AcademicPlanningDtos.PlanInitializationResult;
import com.sse.app.academic.planning.AcademicPlanningDtos.ValidationIssue;
import com.sse.app.academic.structure.SchoolClass;
import com.sse.app.academic.structure.Semester;
import com.sse.app.academic.structure.StructureService;
import com.sse.app.academic.teaching.TeachingAssignmentService;
import com.sse.app.common.ApiException;
import com.sse.app.common.Ids;
import com.sse.app.event.DomainEventPublisher;
import com.sse.app.identity.User;
import com.sse.app.identity.UserRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class AcademicPlanCompletionService {
    private static final Set<String> EDITABLE = Set.of("DRAFT", "REVISION_REQUIRED");
    private static final Set<String> CONTENT_TYPES = Set.of(
            "THEORY", "PRACTICE", "REVIEW", "ASSESSMENT", "PROJECT", "EXPERIENCE", "BUFFER");
    private static final Set<String> ASSESSMENT_TYPES = Set.of(
            "REGULAR", "MIDTERM", "FINAL", "MAKEUP", "PRACTICE", "PROJECT");
    private static final Set<String> ASSESSMENT_FORMS = Set.of(
            "WRITTEN", "MULTIPLE_CHOICE", "ESSAY", "MIXED", "COMPUTER",
            "PRACTICAL", "PRESENTATION", "PROJECT", "PRODUCT", "COMMENT");
    private static final Set<String> RESULT_METHODS = Set.of("SCORE", "COMMENT", "BOTH");

    private final AcademicTrainingPlanRepository plans;
    private final AcademicTrainingPlanSubjectRepository planSubjects;
    private final AcademicTrainingPlanStageRepository stages;
    private final AcademicCurriculumItemRepository curriculum;
    private final AcademicTrainingPlanSpecialWeekRepository specialWeeks;
    private final AcademicCurriculumDistributionRepository distributions;
    private final AcademicAssessmentPlanRepository assessments;
    private final AcademicPlanApprovalHistoryRepository approvalHistory;
    private final EducationProgramRepository programs;
    private final EducationProgramSubjectRepository programSubjects;
    private final ClassSubjectCombinationRepository classCombinations;
    private final SubjectCombinationSubjectRepository combinationSubjects;
    private final TeacherSubjectCapabilityRepository capabilities;
    private final StructureService structure;
    private final TeachingAssignmentService teaching;
    private final DomainEventPublisher events;
    private final UserRepository users;
    private final ObjectMapper objectMapper;

    public AcademicPlanCompletionService(
            AcademicTrainingPlanRepository plans,
            AcademicTrainingPlanSubjectRepository planSubjects,
            AcademicTrainingPlanStageRepository stages,
            AcademicCurriculumItemRepository curriculum,
            AcademicTrainingPlanSpecialWeekRepository specialWeeks,
            AcademicCurriculumDistributionRepository distributions,
            AcademicAssessmentPlanRepository assessments,
            AcademicPlanApprovalHistoryRepository approvalHistory,
            EducationProgramRepository programs,
            EducationProgramSubjectRepository programSubjects,
            ClassSubjectCombinationRepository classCombinations,
            SubjectCombinationSubjectRepository combinationSubjects,
            TeacherSubjectCapabilityRepository capabilities,
            StructureService structure,
            TeachingAssignmentService teaching,
            DomainEventPublisher events,
            UserRepository users,
            ObjectMapper objectMapper) {
        this.plans = plans;
        this.planSubjects = planSubjects;
        this.stages = stages;
        this.curriculum = curriculum;
        this.specialWeeks = specialWeeks;
        this.distributions = distributions;
        this.assessments = assessments;
        this.approvalHistory = approvalHistory;
        this.programs = programs;
        this.programSubjects = programSubjects;
        this.classCombinations = classCombinations;
        this.combinationSubjects = combinationSubjects;
        this.capabilities = capabilities;
        this.structure = structure;
        this.teaching = teaching;
        this.events = events;
        this.users = users;
        this.objectMapper = objectMapper;
    }

    public List<AnnualSubjectSummary> annualSummary(String planId) {
        AcademicTrainingPlan plan = getPlan(planId);
        Map<String, Integer> semesterSequence = structure.listSemesters(plan.getAcademicYearId())
                .stream().collect(Collectors.toMap(Semester::getId, Semester::getSequence));
        Map<String, List<AcademicTrainingPlanSubject>> grouped = planSubjects
                .findByPlanIdOrderByDisplayOrderAscSubjectIdAsc(planId).stream()
                .collect(Collectors.groupingBy(AcademicTrainingPlanSubject::getSubjectId));
        Map<String, EducationProgramSubject> configured = programSubjects
                .findByProgramIdAndGradeLevelOrderBySubjectIdAsc(
                        requireProgramId(plan), plan.getGradeLevel()).stream()
                .collect(Collectors.toMap(EducationProgramSubject::getSubjectId, item -> item));
        return grouped.entrySet().stream().map(entry -> {
            int hk1 = periodsForSemester(entry.getValue(), semesterSequence, 1);
            int hk2 = periodsForSemester(entry.getValue(), semesterSequence, 2);
            EducationProgramSubject target = configured.get(entry.getKey());
            int configuredAnnual = target == null ? 0 : target.getAnnualPeriods();
            String type = target == null ? structure.getSubject(entry.getKey()).getSubjectType()
                    : target.getSubjectType();
            return new AnnualSubjectSummary(entry.getKey(), structure.subjectName(entry.getKey()),
                    type, hk1, hk2, hk1 + hk2, configuredAnnual,
                    configuredAnnual > 0 && hk1 + hk2 == configuredAnnual);
        }).filter(item -> !"EDUCATIONAL_ACTIVITY".equals(item.subjectType()))
                .sorted(java.util.Comparator.comparing(AnnualSubjectSummary::subjectName)).toList();
    }

    @Transactional
    public PlanInitializationResult initializeFromProgram(String planId) {
        AcademicTrainingPlan plan = requireEditable(planId);
        List<Semester> semesters = structure.listSemesters(plan.getAcademicYearId()).stream()
                .sorted(java.util.Comparator.comparingInt(Semester::getSequence)).toList();
        if (semesters.size() != 2) {
            throw ApiException.conflict("Nam hoc phai co dung hai hoc ky truoc khi khoi tao ke hoach");
        }
        List<EducationProgramSubject> configured = programSubjects
                .findByProgramIdAndGradeLevelOrderBySubjectIdAsc(
                        requireProgramId(plan), plan.getGradeLevel()).stream()
                .filter(this::isInstructionalSubject).toList();
        if (configured.isEmpty()) {
            throw ApiException.conflict("Chuong trinh chua cau hinh mon va so tiet cho khoi "
                    + plan.getGradeLevel());
        }

        int subjectCount = 0;
        int subjectUpdatedCount = 0;
        int stageCount = 0;
        int curriculumCount = 0;
        int distributionCount = 0;
        int specialWeekCount = 0;
        int assessmentCount = 0;
        int displayOrder = planSubjects.findByPlanIdOrderByDisplayOrderAscSubjectIdAsc(planId)
                .stream().mapToInt(AcademicTrainingPlanSubject::getDisplayOrder).max().orElse(0);
        Instant now = Instant.now();

        for (EducationProgramSubject config : configured) {
            for (Semester semester : semesters) {
                int totalPeriods = semester.getSequence() == 1
                        ? config.getSemester1Periods() : config.getSemester2Periods();
                if (totalPeriods <= 0) continue;
                AcademicTrainingPlanSubject row = planSubjects
                        .findByPlanIdAndSemesterIdAndSubjectId(
                                planId, semester.getId(), config.getSubjectId())
                        .orElse(null);
                if (row == null) {
                    row = planSubjects.save(AcademicTrainingPlanSubject.builder()
                            .id(Ids.gen("plansj")).planId(planId)
                            .semesterId(semester.getId()).subjectId(config.getSubjectId())
                            .weeklyPeriods(Math.max(1, config.getWeeklyPeriods()))
                            .totalPeriods(totalPeriods)
                            .startDate(semester.getStartDate()).endDate(semester.getEndDate())
                            .examRequired(isExamRequired(config)).displayOrder(++displayOrder)
                            .createdAt(now).updatedAt(now).build());
                    subjectCount++;
                } else {
                    int weeklyPeriods = Math.max(1, config.getWeeklyPeriods());
                    boolean changed = row.getWeeklyPeriods() != weeklyPeriods
                            || row.getTotalPeriods() != totalPeriods
                            || !java.util.Objects.equals(row.getStartDate(), semester.getStartDate())
                            || !java.util.Objects.equals(row.getEndDate(), semester.getEndDate())
                            || row.isExamRequired() != isExamRequired(config);
                    if (changed) {
                        row.setWeeklyPeriods(weeklyPeriods);
                        row.setTotalPeriods(totalPeriods);
                        row.setStartDate(semester.getStartDate());
                        row.setEndDate(semester.getEndDate());
                        row.setExamRequired(isExamRequired(config));
                        row.setUpdatedAt(now);
                        row = planSubjects.save(row);
                        subjectUpdatedCount++;
                    }
                }
                if (stages.countByPlanSubjectId(row.getId()) == 0) {
                    stages.save(AcademicTrainingPlanStage.builder()
                            .id(Ids.gen("planstage")).planSubjectId(row.getId())
                            .code("SEMESTER").name("Ke hoach hoc ky " + semester.getSequence())
                            .sequence(1).startDate(row.getStartDate()).endDate(row.getEndDate())
                            .targetPeriods(row.getTotalPeriods())
                            .description("Khung khoi tao tu chuong trinh; giao vien bo sung chi tiet neu can")
                            .createdAt(now).updatedAt(now).build());
                    stageCount++;
                } else {
                    AcademicTrainingPlanSubject synchronizedRow = row;
                    stages.findByPlanSubjectIdOrderBySequenceAsc(row.getId()).stream()
                            .filter(stage -> "SEMESTER".equals(stage.getCode()))
                            .forEach(stage -> {
                                stage.setStartDate(synchronizedRow.getStartDate());
                                stage.setEndDate(synchronizedRow.getEndDate());
                                stage.setTargetPeriods(synchronizedRow.getTotalPeriods());
                                stage.setUpdatedAt(now);
                                stages.save(stage);
                            });
                }
                String lessonId = curriculum.findByPlanSubjectIdOrderBySequenceAsc(row.getId())
                        .stream().filter(item -> "LESSON".equals(item.getItemType()))
                        .map(AcademicCurriculumItem::getId).findFirst().orElse(null);
                if (lessonId == null) {
                    AcademicCurriculumItem chapter = curriculum.save(AcademicCurriculumItem.builder()
                            .id(Ids.gen("curriculum")).planSubjectId(row.getId())
                            .itemType("CHAPTER").code("C1").title("Noi dung hoc ky")
                            .sequence(1).plannedPeriods(0).createdAt(now).updatedAt(now).build());
                    AcademicCurriculumItem topic = curriculum.save(AcademicCurriculumItem.builder()
                            .id(Ids.gen("curriculum")).planSubjectId(row.getId())
                            .parentId(chapter.getId()).itemType("TOPIC").code("T1")
                            .title("Chu de tong hop").sequence(2).plannedPeriods(0)
                            .createdAt(now).updatedAt(now).build());
                    AcademicCurriculumItem lesson = curriculum.save(AcademicCurriculumItem.builder()
                            .id(Ids.gen("curriculum")).planSubjectId(row.getId())
                            .parentId(topic.getId()).itemType("LESSON").code("L1")
                            .title("Noi dung giang day theo phan phoi tuan").sequence(3)
                            .plannedPeriods(row.getTotalPeriods()).createdAt(now).updatedAt(now).build());
                    lessonId = lesson.getId();
                    curriculumCount += 3;
                }
                if (distributions.countByPlanSubjectId(row.getId()) == 0) {
                    distributionCount += seedDistributions(row, lessonId, now);
                }
                int teachingWeeks = teachingWeeks(row.getStartDate(), row.getEndDate());
                if (!specialWeeks.existsByPlanSubjectIdAndWeekType(row.getId(), "BUFFER")) {
                    specialWeeks.save(AcademicTrainingPlanSpecialWeek.builder()
                            .id(Ids.gen("specialweek")).planSubjectId(row.getId())
                            .weekType("BUFFER").weekNumber(teachingWeeks)
                            .name("Tuan du phong").description("Dieu chinh khi cham tien do hoac nghi dot xuat")
                            .createdAt(now).updatedAt(now).build());
                    specialWeekCount++;
                }
                if (row.isExamRequired() && !specialWeeks.existsByPlanSubjectIdAndWeekType(row.getId(), "EXAM")) {
                    int examWeek = Math.max(1, teachingWeeks - 1);
                    specialWeeks.save(AcademicTrainingPlanSpecialWeek.builder()
                            .id(Ids.gen("specialweek")).planSubjectId(row.getId())
                            .weekType("EXAM").weekNumber(examWeek)
                            .name("Tuan kiem tra cuoi ky")
                            .createdAt(now).updatedAt(now).build());
                    specialWeekCount++;
                }
                if (row.isExamRequired()) {
                    for (String assessmentType : List.of("MIDTERM", "FINAL")) {
                        boolean exists = assessments
                                .findByPlanIdOrderBySemesterIdAscWeekNumberAscSubjectIdAsc(planId).stream()
                                .anyMatch(item -> semester.getId().equals(item.getSemesterId())
                                        && config.getSubjectId().equals(item.getSubjectId())
                                        && assessmentType.equals(item.getAssessmentType()));
                        if (exists) continue;
                        int assessmentWeek = "MIDTERM".equals(assessmentType)
                                ? Math.max(2, teachingWeeks / 2)
                                : Math.max(1, teachingWeeks - 1);
                        assessments.save(AcademicAssessmentPlan.builder()
                                .id(Ids.gen("assessment")).planId(planId).semesterId(semester.getId())
                                .subjectId(config.getSubjectId()).assessmentType(assessmentType)
                                .name(assessmentTypeName(assessmentType)).assessmentForm("WRITTEN")
                                .curriculumItemIds(lessonId).resultMethod("SCORE")
                                .weekNumber(assessmentWeek).durationMinutes(45)
                                .notes("Kế hoạch dự kiến; phòng thi, giám thị và điểm không thuộc phạm vi này")
                                .createdAt(now).updatedAt(now).build());
                        syncAssessmentWeek(row, assessmentWeek, assessmentType, now);
                        assessmentCount++;
                    }
                }
            }
        }
        return new PlanInitializationResult(subjectCount, subjectUpdatedCount, stageCount, curriculumCount,
                distributionCount, specialWeekCount, assessmentCount);
    }

    private int seedDistributions(AcademicTrainingPlanSubject row, String lessonId, Instant now) {
        int weeks = teachingWeeks(row.getStartDate(), row.getEndDate());
        int remaining = row.getTotalPeriods();
        int count = 0;
        for (int week = 1; week <= weeks && remaining > 0; week++) {
            int periods = Math.min(Math.max(1, row.getWeeklyPeriods()), remaining);
            distributions.save(AcademicCurriculumDistribution.builder()
                    .id(Ids.gen("distribution")).planSubjectId(row.getId())
                    .curriculumItemId(lessonId).weekNumber(week).contentType("THEORY")
                    .title("Tuan " + week).periods(periods)
                    .notes("Khung phan phoi tu dong; co the sua ten bai va loai noi dung")
                    .createdAt(now).updatedAt(now).build());
            remaining -= periods;
            count++;
        }
        int week = 1;
        int supplement = 1;
        while (remaining > 0) {
            int periods = Math.min(20, remaining);
            distributions.save(AcademicCurriculumDistribution.builder()
                    .id(Ids.gen("distribution")).planSubjectId(row.getId())
                    .curriculumItemId(lessonId).weekNumber(Math.min(30, week++))
                    .contentType("THEORY").title("Tiet bo sung " + supplement++)
                    .periods(periods).createdAt(now).updatedAt(now).build());
            remaining -= periods;
            count++;
        }
        return count;
    }

    private int teachingWeeks(LocalDate start, LocalDate end) {
        return (int) Math.max(1, Math.min(30, ChronoUnit.WEEKS.between(start, end) + 1));
    }

    private boolean isExamRequired(EducationProgramSubject config) {
        return isInstructionalSubject(config);
    }

    private boolean isInstructionalSubject(EducationProgramSubject config) {
        return !"EDUCATIONAL_ACTIVITY".equals(config.getSubjectType());
    }

    private boolean isInstructionalSubject(String subjectId) {
        com.sse.app.academic.structure.Subject subject = structure.getSubject(subjectId);
        return subject == null || !"EDUCATIONAL_ACTIVITY".equals(subject.getSubjectType());
    }

    public List<AcademicCurriculumDistribution> listDistributions(
            String planId, String planSubjectId) {
        requirePlanSubject(planId, planSubjectId);
        return distributions.findByPlanSubjectIdOrderByWeekNumberAscIdAsc(planSubjectId);
    }

    public AcademicCurriculumDistribution getDistribution(String planId, String id) {
        AcademicCurriculumDistribution row = distributions.findById(id)
                .orElseThrow(() -> ApiException.notFound("Nội dung phân phối theo tuần"));
        requirePlanSubject(planId, row.getPlanSubjectId());
        return row;
    }

    @Transactional
    public AcademicCurriculumDistribution saveDistribution(
            String planId, String planSubjectId, String id,
            CurriculumDistributionRequest request) {
        requireEditable(planId);
        AcademicTrainingPlanSubject subject = requirePlanSubject(planId, planSubjectId);
        String type = normalize(request.contentType(), CONTENT_TYPES, "Loại nội dung phân phối");
        if (request.curriculumItemId() != null && !request.curriculumItemId().isBlank()) {
            curriculum.findById(request.curriculumItemId())
                    .filter(item -> planSubjectId.equals(item.getPlanSubjectId()))
                    .orElseThrow(() -> ApiException.badRequest("Bài học không thuộc môn đang phân phối"));
        }
        long teachingWeeks = ChronoUnit.WEEKS.between(subject.getStartDate(), subject.getEndDate()) + 1;
        if (request.weekNumber() > Math.min(30, teachingWeeks)) {
            throw ApiException.badRequest("Tuần phân phối nằm ngoài thời gian học của môn");
        }
        int otherPeriods = distributions.findByPlanSubjectIdOrderByWeekNumberAscIdAsc(planSubjectId)
                .stream().filter(item -> id == null || !item.getId().equals(id))
                .mapToInt(AcademicCurriculumDistribution::getPeriods).sum();
        if (otherPeriods + request.periods() > subject.getTotalPeriods()) {
            throw ApiException.conflict("Tổng số tiết phân phối vượt tổng tiết của môn trong học kỳ");
        }
        Instant now = Instant.now();
        AcademicCurriculumDistribution row = id == null
                ? AcademicCurriculumDistribution.builder()
                .id(request.id() == null || request.id().isBlank() ? Ids.gen("distribution") : request.id())
                .planSubjectId(planSubjectId).createdAt(now).build()
                : distributions.findById(id)
                .filter(item -> planSubjectId.equals(item.getPlanSubjectId()))
                .orElseThrow(() -> ApiException.notFound("Nội dung phân phối theo tuần"));
        row.setCurriculumItemId(blankToNull(request.curriculumItemId()));
        row.setWeekNumber(request.weekNumber());
        row.setContentType(type);
        row.setTitle(request.title().trim());
        row.setPeriods(request.periods());
        row.setNotes(blankToNull(request.notes()));
        row.setUpdatedAt(now);
        return distributions.save(row);
    }

    @Transactional
    public AcademicCurriculumDistribution updateDistribution(
            String planId, String id, CurriculumDistributionRequest request) {
        AcademicCurriculumDistribution row = distributions.findById(id)
                .orElseThrow(() -> ApiException.notFound("Nội dung phân phối theo tuần"));
        return saveDistribution(planId, row.getPlanSubjectId(), id, request);
    }

    @Transactional
    public void deleteDistribution(String planId, String id) {
        requireEditable(planId);
        AcademicCurriculumDistribution row = distributions.findById(id)
                .orElseThrow(() -> ApiException.notFound("Nội dung phân phối theo tuần"));
        requirePlanSubject(planId, row.getPlanSubjectId());
        distributions.delete(row);
    }

    public List<AcademicAssessmentPlan> listAssessments(String planId) {
        getPlan(planId);
        return assessments.findByPlanIdOrderBySemesterIdAscWeekNumberAscSubjectIdAsc(planId);
    }

    public AcademicAssessmentPlan getAssessment(String planId, String id) {
        return assessments.findById(id).filter(item -> planId.equals(item.getPlanId()))
                .orElseThrow(() -> ApiException.notFound("Kế hoạch kiểm tra"));
    }

    @Transactional
    public AcademicAssessmentPlan saveAssessment(
            String planId, String id, AssessmentPlanRequest request) {
        AcademicTrainingPlan plan = requireEditable(planId);
        Semester semester = structure.getSemester(request.semesterId());
        if (!plan.getAcademicYearId().equals(semester.getAcademicYearId())) {
            throw ApiException.badRequest("Học kỳ không thuộc năm học của kế hoạch");
        }
        planSubjects.findByPlanIdAndSemesterIdAndSubjectId(
                        planId, request.semesterId(), request.subjectId())
                .orElseThrow(() -> ApiException.badRequest("Môn chưa có trong kế hoạch học kỳ"));
        if (request.classId() != null && !request.classId().isBlank()) {
            SchoolClass schoolClass = structure.getClass(request.classId());
            if (!plan.getAcademicYearId().equals(schoolClass.getAcademicYearId())
                    || !plan.getGradeLevel().equals(schoolClass.getGradeLevel())) {
                throw ApiException.badRequest("Lớp không thuộc phạm vi của kế hoạch");
            }
        }
        if (request.teacherId() != null && !request.teacherId().isBlank()
                && request.classId() != null && !request.classId().isBlank()
                && teaching.list(request.teacherId(), request.classId(), request.subjectId(),
                request.semesterId(), "ACTIVE").isEmpty()) {
            throw ApiException.badRequest("Giáo viên chưa được phân công đúng lớp, môn và học kỳ");
        }
        long weeks = ChronoUnit.WEEKS.between(semester.getStartDate(), semester.getEndDate()) + 1;
        if (request.weekNumber() > Math.min(30, weeks)) {
            throw ApiException.badRequest("Tuần kiểm tra nằm ngoài học kỳ");
        }
        String type = normalize(request.assessmentType(), ASSESSMENT_TYPES, "Loại đánh giá");
        String form = normalize(defaultValue(request.assessmentForm(), "WRITTEN"),
                ASSESSMENT_FORMS, "Hình thức đánh giá");
        String resultMethod = normalize(defaultValue(request.resultMethod(), "SCORE"),
                RESULT_METHODS, "Phương thức ghi nhận kết quả");
        AcademicTrainingPlanSubject planSubject = planSubjects
                .findByPlanIdAndSemesterIdAndSubjectId(planId, request.semesterId(), request.subjectId())
                .orElseThrow(() -> ApiException.badRequest("Môn chưa có trong kế hoạch học kỳ"));
        List<String> curriculumIds = request.curriculumItemIds() == null ? List.of()
                : request.curriculumItemIds().stream().filter(idValue -> idValue != null && !idValue.isBlank())
                .distinct().toList();
        for (String curriculumId : curriculumIds) {
            curriculum.findById(curriculumId)
                    .filter(item -> planSubject.getId().equals(item.getPlanSubjectId()))
                    .orElseThrow(() -> ApiException.badRequest(
                            "Nội dung đánh giá không thuộc môn và học kỳ đã chọn"));
        }
        Instant now = Instant.now();
        AcademicAssessmentPlan row = id == null ? AcademicAssessmentPlan.builder()
                .id(request.id() == null || request.id().isBlank() ? Ids.gen("assessment") : request.id())
                .planId(planId).createdAt(now).build()
                : assessments.findById(id).filter(item -> planId.equals(item.getPlanId()))
                .orElseThrow(() -> ApiException.notFound("Kế hoạch kiểm tra"));
        String previousSemesterId = row.getSemesterId();
        String previousSubjectId = row.getSubjectId();
        int previousWeekNumber = row.getWeekNumber();
        row.setSemesterId(request.semesterId());
        row.setClassId(blankToNull(request.classId()));
        row.setSubjectId(request.subjectId());
        row.setAssessmentType(type);
        row.setName(defaultValue(request.name(), assessmentTypeName(type)).trim());
        row.setAssessmentForm(form);
        row.setCurriculumItemIds(curriculumIds.isEmpty() ? null : String.join(",", curriculumIds));
        row.setResultMethod(resultMethod);
        row.setWeekNumber(request.weekNumber());
        row.setDurationMinutes(request.durationMinutes());
        row.setTeacherId(blankToNull(request.teacherId()));
        row.setNotes(blankToNull(request.notes()));
        row.setUpdatedAt(now);
        AcademicAssessmentPlan saved = assessments.save(row);
        syncAssessmentWeek(planSubject, request.weekNumber(), type, now);
        if (id != null && (!request.semesterId().equals(previousSemesterId)
                || !request.subjectId().equals(previousSubjectId)
                || request.weekNumber() != previousWeekNumber)) {
            assessments.flush();
            cleanupAssessmentWeek(planId, previousSemesterId, previousSubjectId, previousWeekNumber);
        }
        return saved;
    }

    @Transactional
    public void deleteAssessment(String planId, String id) {
        requireEditable(planId);
        AcademicAssessmentPlan row = assessments.findById(id)
                .filter(item -> planId.equals(item.getPlanId()))
                .orElseThrow(() -> ApiException.notFound("Kế hoạch kiểm tra"));
        assessments.delete(row);
        assessments.flush();
        cleanupAssessmentWeek(planId, row.getSemesterId(), row.getSubjectId(), row.getWeekNumber());
    }

    public List<AcademicPlanningDtos.ApprovalHistoryView> history(String planId) {
        getPlan(planId);
        return approvalHistory.findByPlanIdOrderByCreatedAtAsc(planId).stream().map(item -> {
            User actor = users.findById(item.getActorId()).orElse(null);
            return new AcademicPlanningDtos.ApprovalHistoryView(
                    item.getId(), item.getPlanId(), item.getAction(), item.getFromStatus(),
                    item.getToStatus(), item.getActorId(),
                    actor == null ? item.getActorId() : actor.getFullName(),
                    actor == null ? null : actor.getRole(), item.getComment(), item.getCreatedAt());
        }).toList();
    }

    public PlanValidationReport validate(String planId) {
        AcademicTrainingPlan plan = getPlan(planId);
        if (Set.of("PUBLISHED", "LOCKED", "ARCHIVED").contains(plan.getStatus())
                && plan.getValidationSnapshot() != null
                && !plan.getValidationSnapshot().isBlank()) {
            try {
                return objectMapper.readValue(
                        plan.getValidationSnapshot(), PlanValidationReport.class);
            } catch (JsonProcessingException ignored) {
                // Legacy/corrupt snapshots are recalculated once and replaced on republish.
            }
        }
        List<ValidationIssue> issues = new ArrayList<>();
        List<Semester> semesters = structure.listSemesters(plan.getAcademicYearId());
        List<AcademicTrainingPlanSubject> rows = planSubjects
                .findByPlanIdOrderByDisplayOrderAscSubjectIdAsc(planId);
        String programId = requireProgramId(plan);
        EducationProgram program = programs.findById(programId).orElse(null);
        if (program == null || !"ACTIVE".equals(program.getStatus())) {
            error(issues, "PROGRAM", "Chưa chọn chương trình giáo dục đang hoạt động", planId);
        }
        if (semesters.size() != 2) {
            error(issues, "SEMESTERS", "Năm học phải có đúng hai học kỳ", plan.getAcademicYearId());
        }
        Map<String, Integer> semesterSequence = semesters.stream()
                .collect(Collectors.toMap(Semester::getId, Semester::getSequence));
        Map<String, List<AcademicTrainingPlanSubject>> bySubject = rows.stream()
                .collect(Collectors.groupingBy(AcademicTrainingPlanSubject::getSubjectId));
        List<EducationProgramSubject> configured = program == null ? List.of()
                : programSubjects.findByProgramIdAndGradeLevelOrderBySubjectIdAsc(
                programId, plan.getGradeLevel());
        for (EducationProgramSubject target : configured) {
            if (!isInstructionalSubject(target)) continue;
            List<AcademicTrainingPlanSubject> subjectRows = bySubject.getOrDefault(target.getSubjectId(), List.of());
            if (target.isRequired() && subjectRows.size() != 2) {
                error(issues, "REQUIRED_SUBJECT", "Môn bắt buộc "
                        + structure.subjectName(target.getSubjectId()) + " phải có ở cả HK1 và HK2",
                        target.getSubjectId());
                continue;
            }
            if (!subjectRows.isEmpty()) {
                int hk1 = periodsForSemester(subjectRows, semesterSequence, 1);
                int hk2 = periodsForSemester(subjectRows, semesterSequence, 2);
                if (hk1 != target.getSemester1Periods() || hk2 != target.getSemester2Periods()
                        || hk1 + hk2 != target.getAnnualPeriods()) {
                    error(issues, "PERIOD_TOTAL", structure.subjectName(target.getSubjectId())
                            + ": kế hoạch " + hk1 + "+" + hk2 + " tiết, chương trình yêu cầu "
                            + target.getSemester1Periods() + "+" + target.getSemester2Periods(),
                            target.getSubjectId());
                }
            }
        }
        Set<String> configuredSubjects = configured.stream()
                .map(EducationProgramSubject::getSubjectId).collect(Collectors.toSet());
        rows.stream().filter(row -> !configuredSubjects.contains(row.getSubjectId()))
                .forEach(row -> error(issues, "OUTSIDE_PROGRAM", "Môn "
                        + structure.subjectName(row.getSubjectId())
                        + " chưa được cấu hình trong chương trình", row.getId()));

        List<AcademicAssessmentPlan> assessmentRows = listAssessments(planId);
        for (AcademicTrainingPlanSubject row : rows) {
            if (!isInstructionalSubject(row.getSubjectId())) continue;
            String label = structure.subjectName(row.getSubjectId()) + " · "
                    + semesterSequence.getOrDefault(row.getSemesterId(), 0);
            int stagePeriods = stages.findByPlanSubjectIdOrderBySequenceAsc(row.getId())
                    .stream().mapToInt(AcademicTrainingPlanStage::getTargetPeriods).sum();
            int lessonPeriods = curriculum.findByPlanSubjectIdOrderBySequenceAsc(row.getId())
                    .stream().filter(item -> "LESSON".equals(item.getItemType()))
                    .mapToInt(AcademicCurriculumItem::getPlannedPeriods).sum();
            int distributedPeriods = distributions.findByPlanSubjectIdOrderByWeekNumberAscIdAsc(row.getId())
                    .stream().mapToInt(AcademicCurriculumDistribution::getPeriods).sum();
            int teachingWeeks = teachingWeeks(row.getStartDate(), row.getEndDate());
            int expectedByWeeklyRate = teachingWeeks * row.getWeeklyPeriods();
            int tolerance = Math.max(row.getWeeklyPeriods() * 2, 4);
            if (Math.abs(expectedByWeeklyRate - row.getTotalPeriods()) > tolerance) {
                warning(issues, "WEEKLY_RATE", label + ": " + row.getWeeklyPeriods()
                        + " tiết/tuần trong " + teachingWeeks + " tuần tương đương khoảng "
                        + expectedByWeeklyRate + " tiết, khác nhiều so với tổng "
                        + row.getTotalPeriods() + " tiết", row.getId());
            }
            if (stagePeriods != row.getTotalPeriods()) {
                error(issues, "STAGE_PERIODS", label + ": tổng tiết giai đoạn "
                        + stagePeriods + "/" + row.getTotalPeriods(), row.getId());
            }
            if (lessonPeriods != row.getTotalPeriods()) {
                error(issues, "LESSON_PERIODS", label + ": tổng tiết bài học "
                        + lessonPeriods + "/" + row.getTotalPeriods(), row.getId());
            }
            if (distributedPeriods != row.getTotalPeriods()) {
                error(issues, "WEEKLY_DISTRIBUTION", label + ": đã phân phối "
                        + distributedPeriods + "/" + row.getTotalPeriods() + " tiết theo tuần", row.getId());
            }
            List<AcademicTrainingPlanSpecialWeek> weeks = specialWeeks
                    .findByPlanSubjectIdOrderByWeekNumberAscWeekTypeAsc(row.getId());
            if (weeks.stream().noneMatch(item -> "BUFFER".equals(item.getWeekType()))) {
                warning(issues, "BUFFER_WEEK", label + ": chưa có tuần dự phòng", row.getId());
            }
            if (row.isExamRequired()) {
                for (String requiredType : List.of("MIDTERM", "FINAL")) {
                    if (assessmentRows.stream().noneMatch(item ->
                            row.getSemesterId().equals(item.getSemesterId())
                                    && row.getSubjectId().equals(item.getSubjectId())
                                    && requiredType.equals(item.getAssessmentType()))) {
                        error(issues, "ASSESSMENT", label + ": thiếu kế hoạch "
                                + ("MIDTERM".equals(requiredType) ? "giữa kỳ" : "cuối kỳ"), row.getId());
                    }
                }
            }
        }

        for (AcademicAssessmentPlan assessment : assessmentRows) {
            if (!isInstructionalSubject(assessment.getSubjectId())) continue;
            if (assessment.getCurriculumItemIds() == null || assessment.getCurriculumItemIds().isBlank()) {
                warning(issues, "ASSESSMENT_CONTENT", assessment.getName()
                        + ": chưa liên kết chủ đề hoặc bài học được đánh giá", assessment.getId());
                continue;
            }
            AcademicTrainingPlanSubject row = planSubjects
                    .findByPlanIdAndSemesterIdAndSubjectId(
                            planId, assessment.getSemesterId(), assessment.getSubjectId()).orElse(null);
            if (row == null) continue;
            Map<String, Integer> completionWeek = distributions
                    .findByPlanSubjectIdOrderByWeekNumberAscIdAsc(row.getId()).stream()
                    .filter(item -> item.getCurriculumItemId() != null)
                    .collect(Collectors.toMap(AcademicCurriculumDistribution::getCurriculumItemId,
                            AcademicCurriculumDistribution::getWeekNumber, Math::max));
            for (String curriculumId : assessment.getCurriculumItemIds().split(",")) {
                Integer week = completionWeek.get(curriculumId);
                if (week == null || week >= assessment.getWeekNumber()) {
                    warning(issues, "ASSESSMENT_SEQUENCE", assessment.getName()
                            + ": có nội dung chưa được phân phối xong trước tuần kiểm tra",
                            assessment.getId());
                    break;
                }
            }
        }

        validateClassesAndAssignments(plan, rows, configured, semesters, issues);
        long errors = issues.stream().filter(item -> "ERROR".equals(item.level())).count();
        long warnings = issues.stream().filter(item -> "WARNING".equals(item.level())).count();
        PlanValidationReport report = new PlanValidationReport(errors == 0, errors, warnings, issues);
        if (Set.of("PUBLISHED", "LOCKED", "ARCHIVED").contains(plan.getStatus())
                && (plan.getValidationSnapshot() == null || plan.getValidationSnapshot().isBlank())) {
            try {
                plan.setValidationSnapshot(objectMapper.writeValueAsString(report));
                plan.setValidatedAt(Instant.now());
                plans.save(plan);
            } catch (JsonProcessingException ignored) {
                // Validation still succeeds; a future read can retry persisting the snapshot.
            }
        }
        return report;
    }

    @Transactional
    public AcademicTrainingPlan submit(String planId, String actorId, String comment) {
        AcademicTrainingPlan plan = requireEditable(planId);
        PlanValidationReport report = validate(planId);
        if (!report.valid()) {
            throw ApiException.conflict("Kế hoạch còn " + report.errorCount()
                    + " lỗi bắt buộc; hãy mở phần kiểm tra để xử lý");
        }
        String from = plan.getStatus();
        Instant now = Instant.now();
        plan.setStatus("SUBMITTED");
        plan.setSubmittedAt(now);
        plan.setSubmittedBy(actorId);
        plan.setWorkflowComment(comment.trim());
        plan.setUpdatedAt(now);
        AcademicTrainingPlan saved = plans.save(plan);
        record(saved, "SUBMIT", from, "SUBMITTED", actorId, comment);
        events.publish("academic.education_plan.submitted", actorId,
                "education_plan", planId, Map.of(
                        "message", "Kế hoạch giáo dục đang chờ rà soát.",
                        "targetUserId", plan.getCreatedBy() == null ? actorId : plan.getCreatedBy()));
        return saved;
    }

    @Transactional
    public AcademicTrainingPlan review(String planId, String actorId, String comment) {
        AcademicTrainingPlan plan = requireStatus(planId, Set.of("SUBMITTED"),
                "Chỉ kế hoạch đã gửi duyệt mới được kiểm tra");
        plan.setReviewedAt(Instant.now());
        plan.setReviewedBy(actorId);
        plan.setWorkflowComment(comment.trim());
        plan.setUpdatedAt(Instant.now());
        AcademicTrainingPlan saved = plans.save(plan);
        record(saved, "REVIEW", "SUBMITTED", "SUBMITTED", actorId, comment);
        return saved;
    }

    @Transactional
    public AcademicTrainingPlan requestRevision(String planId, String actorId, String comment) {
        AcademicTrainingPlan plan = requireStatus(planId, Set.of("SUBMITTED", "APPROVED"),
                "Kế hoạch không ở bước có thể yêu cầu chỉnh sửa");
        String from = plan.getStatus();
        plan.setStatus("REVISION_REQUIRED");
        plan.setWorkflowComment(comment.trim());
        plan.setApprovedAt(null);
        plan.setApprovedBy(null);
        plan.setUpdatedAt(Instant.now());
        AcademicTrainingPlan saved = plans.save(plan);
        record(saved, "REQUEST_REVISION", from, "REVISION_REQUIRED", actorId, comment);
        events.publish("academic.education_plan.revision_required", actorId,
                "education_plan", planId, Map.of(
                        "message", comment,
                        "targetUserId", plan.getSubmittedBy() == null
                                ? (plan.getCreatedBy() == null ? actorId : plan.getCreatedBy())
                                : plan.getSubmittedBy()));
        return saved;
    }

    @Transactional
    public AcademicTrainingPlan approve(String planId, String actorId, String comment) {
        AcademicTrainingPlan plan = requireStatus(planId, Set.of("SUBMITTED"),
                "Chỉ kế hoạch đã gửi duyệt mới được phê duyệt");
        if (plan.getReviewedAt() == null) {
            throw ApiException.conflict("Kế hoạch phải được kiểm tra trước khi phê duyệt");
        }
        PlanValidationReport report = validate(planId);
        if (!report.valid()) {
            throw ApiException.conflict("Kế hoạch phát sinh " + report.errorCount()
                    + " lỗi bắt buộc và chưa thể phê duyệt");
        }
        Instant now = Instant.now();
        plan.setStatus("APPROVED");
        plan.setApprovedAt(now);
        plan.setApprovedBy(actorId);
        plan.setWorkflowComment(comment.trim());
        plan.setUpdatedAt(now);
        AcademicTrainingPlan saved = plans.save(plan);
        record(saved, "APPROVE", "SUBMITTED", "APPROVED", actorId, comment);
        events.publish("academic.education_plan.approved", actorId,
                "education_plan", planId, Map.of(
                        "message", "Kế hoạch giáo dục đã được phê duyệt.",
                        "targetUserId", plan.getSubmittedBy() == null
                                ? (plan.getCreatedBy() == null ? actorId : plan.getCreatedBy())
                                : plan.getSubmittedBy()));
        return saved;
    }

    @Transactional
    public AcademicTrainingPlan approveDirectlyByAdmin(String planId, String actorId) {
        AcademicTrainingPlan plan = requireStatus(planId,
                Set.of("DRAFT", "REVISION_REQUIRED", "SUBMITTED"),
                "Kế hoạch không ở trạng thái có thể công bố");
        PlanValidationReport report = validate(planId);
        if (!report.valid()) {
            throw ApiException.conflict("Kế hoạch còn " + report.errorCount()
                    + " lỗi bắt buộc; hãy mở phần kiểm tra để xử lý");
        }
        String from = plan.getStatus();
        Instant now = Instant.now();
        plan.setStatus("APPROVED");
        plan.setSubmittedAt(plan.getSubmittedAt() == null ? now : plan.getSubmittedAt());
        plan.setSubmittedBy(plan.getSubmittedBy() == null ? actorId : plan.getSubmittedBy());
        plan.setReviewedAt(now);
        plan.setReviewedBy(actorId);
        plan.setApprovedAt(now);
        plan.setApprovedBy(actorId);
        plan.setWorkflowComment("Admin kiểm tra và công bố trực tiếp");
        plan.setUpdatedAt(now);
        AcademicTrainingPlan saved = plans.save(plan);
        record(saved, "ADMIN_APPROVE", from, "APPROVED", actorId,
                "Admin kiểm tra và công bố trực tiếp");
        return saved;
    }

    @Transactional
    public AcademicTrainingPlan archive(String planId, String actorId, String comment) {
        AcademicTrainingPlan plan = requireStatus(planId, Set.of("PUBLISHED", "LOCKED"),
                "Chỉ kế hoạch đã công bố mới được lưu trữ");
        String from = plan.getStatus();
        plan.setStatus("ARCHIVED");
        plan.setLockedAt(Instant.now());
        plan.setLockedBy(actorId);
        plan.setWorkflowComment(comment.trim());
        plan.setUpdatedAt(Instant.now());
        AcademicTrainingPlan saved = plans.save(plan);
        record(saved, "ARCHIVE", from, "ARCHIVED", actorId, comment);
        return saved;
    }

    @Transactional
    public void recordPublished(String planId, String actorId) {
        AcademicTrainingPlan plan = getPlan(planId);
        PlanValidationReport validation = validate(planId);
        try {
            plan.setValidationSnapshot(objectMapper.writeValueAsString(validation));
            plan.setValidatedAt(Instant.now());
            plans.save(plan);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Không thể lưu kết quả kiểm tra khi công bố", exception);
        }
        record(plan, "PUBLISH", "APPROVED", "PUBLISHED", actorId, "Công bố kế hoạch giáo dục");
        List<String> classIds = structure.listClasses(plan.getAcademicYearId(), plan.getGradeLevel())
                .stream().map(SchoolClass::getId).toList();
        events.publish("academic.education_plan.published", actorId,
                "education_plan", planId, Map.of(
                        "classIds", classIds,
                        "message", "Nhà trường đã công bố kế hoạch giáo dục mới."));
    }

    @Transactional
    public void copyCompletionContent(String sourcePlanId, String targetPlanId) {
        Map<String, String> targetSubjects = planSubjects
                .findByPlanIdOrderByDisplayOrderAscSubjectIdAsc(targetPlanId).stream()
                .collect(Collectors.toMap(
                        row -> row.getSemesterId() + "|" + row.getSubjectId(),
                        AcademicTrainingPlanSubject::getId));
        Map<String, String> sourceToTarget = planSubjects
                .findByPlanIdOrderByDisplayOrderAscSubjectIdAsc(sourcePlanId).stream()
                .collect(Collectors.toMap(AcademicTrainingPlanSubject::getId,
                        row -> targetSubjects.get(row.getSemesterId() + "|" + row.getSubjectId())));
        Instant now = Instant.now();
        Map<String, String> curriculumIds = new HashMap<>();
        sourceToTarget.forEach((sourceSubjectId, targetSubjectId) -> {
            if (targetSubjectId == null) return;
            Map<String, String> targetByCode = curriculum
                    .findByPlanSubjectIdOrderBySequenceAsc(targetSubjectId).stream()
                    .collect(Collectors.toMap(AcademicCurriculumItem::getCode,
                            AcademicCurriculumItem::getId, (left, right) -> left));
            curriculum.findByPlanSubjectIdOrderBySequenceAsc(sourceSubjectId)
                    .forEach(item -> {
                        String mapped = targetByCode.get(item.getCode());
                        if (mapped != null) curriculumIds.put(item.getId(), mapped);
                    });
            distributions.findByPlanSubjectIdOrderByWeekNumberAscIdAsc(sourceSubjectId)
                    .forEach(item -> distributions.save(AcademicCurriculumDistribution.builder()
                            .id(Ids.gen("distribution")).planSubjectId(targetSubjectId)
                            .curriculumItemId(curriculumIds.get(item.getCurriculumItemId()))
                            .weekNumber(item.getWeekNumber()).contentType(item.getContentType())
                            .title(item.getTitle()).periods(item.getPeriods()).notes(item.getNotes())
                            .createdAt(now).updatedAt(now).build()));
        });
        assessments.findByPlanIdOrderBySemesterIdAscWeekNumberAscSubjectIdAsc(sourcePlanId)
                .forEach(item -> assessments.save(AcademicAssessmentPlan.builder()
                        .id(Ids.gen("assessment")).planId(targetPlanId)
                        .semesterId(item.getSemesterId()).classId(item.getClassId())
                        .subjectId(item.getSubjectId()).assessmentType(item.getAssessmentType())
                        .name(item.getName()).assessmentForm(item.getAssessmentForm())
                        .curriculumItemIds(remapCurriculumIds(item.getCurriculumItemIds(), curriculumIds))
                        .resultMethod(item.getResultMethod())
                        .weekNumber(item.getWeekNumber()).durationMinutes(item.getDurationMinutes())
                        .teacherId(item.getTeacherId()).notes(item.getNotes())
                        .createdAt(now).updatedAt(now).build()));
    }

    private String remapCurriculumIds(String sourceIds, Map<String, String> mappings) {
        if (sourceIds == null || sourceIds.isBlank()) return null;
        String value = java.util.Arrays.stream(sourceIds.split(","))
                .map(mappings::get).filter(java.util.Objects::nonNull)
                .collect(Collectors.joining(","));
        return value.isBlank() ? null : value;
    }

    private void validateClassesAndAssignments(
            AcademicTrainingPlan plan,
            List<AcademicTrainingPlanSubject> rows,
            List<EducationProgramSubject> configured,
            List<Semester> semesters,
            List<ValidationIssue> issues) {
        List<SchoolClass> classes = structure.listClasses(plan.getAcademicYearId(), plan.getGradeLevel());
        Map<String, EducationProgramSubject> configBySubject = configured.stream()
                .collect(Collectors.toMap(EducationProgramSubject::getSubjectId, item -> item));
        boolean requiresCombination = configured.stream().anyMatch(config ->
                !config.isRequired() && Set.of("OPTIONAL", "SPECIALIZED")
                        .contains(config.getSubjectType()));
        for (SchoolClass schoolClass : classes) {
            ClassSubjectCombination combination = classCombinations.findById(schoolClass.getId()).orElse(null);
            if (requiresCombination && combination == null) {
                error(issues, "CLASS_COMBINATION", "Lớp " + schoolClass.getCode()
                        + " chưa được gán tổ hợp môn", schoolClass.getId());
            }
            Set<String> selectedSubjects = combination == null ? Set.of()
                    : combinationSubjects.findByCombinationIdOrderBySubjectIdAsc(combination.getCombinationId())
                    .stream().map(SubjectCombinationSubject::getSubjectId).collect(Collectors.toSet());
            for (AcademicTrainingPlanSubject row : rows) {
                EducationProgramSubject config = configBySubject.get(row.getSubjectId());
                boolean applies = config == null || config.isRequired()
                        || selectedSubjects.contains(row.getSubjectId());
                if (!applies) continue;
                if (config != null && "EDUCATIONAL_ACTIVITY".equals(config.getSubjectType())) continue;
                List<com.sse.app.academic.teaching.TeachingDtos.TeachingAssignmentDto> assigned =
                        teaching.list(null, schoolClass.getId(), row.getSubjectId(),
                                row.getSemesterId(), "ACTIVE");
                if (assigned.isEmpty()) {
                    error(issues, "TEACHER_ASSIGNMENT", schoolClass.getCode() + " · "
                            + structure.subjectName(row.getSubjectId()) + " chưa có giáo viên phụ trách",
                            schoolClass.getId());
                } else {
                    assigned.forEach(item -> {
                        if (!capabilities.findByTeacherIdAndSubjectId(item.teacherId(), row.getSubjectId())
                                .map(TeacherSubjectCapability::isActive).orElse(false)) {
                            warning(issues, "TEACHER_CAPABILITY", item.teacherName()
                                    + " chưa được khai báo năng lực dạy " + item.subjectName(), item.teacherId());
                        }
                    });
                }
            }
        }
        if (classes.isEmpty()) {
            error(issues, "CLASSES", "Khối chưa có lớp trong năm học", plan.getGradeLevel());
        }
    }

    private AcademicTrainingPlan getPlan(String id) {
        return plans.findById(id).orElseThrow(() -> ApiException.notFound("Kế hoạch giáo dục"));
    }

    private AcademicTrainingPlan requireEditable(String id) {
        AcademicTrainingPlan plan = getPlan(id);
        if (!EDITABLE.contains(plan.getStatus())) {
            throw ApiException.conflict("Chỉ bản nháp hoặc bản được yêu cầu chỉnh sửa mới được thay đổi");
        }
        return plan;
    }

    private AcademicTrainingPlan requireStatus(String id, Set<String> statuses, String message) {
        AcademicTrainingPlan plan = getPlan(id);
        if (!statuses.contains(plan.getStatus())) throw ApiException.conflict(message);
        return plan;
    }

    private AcademicTrainingPlanSubject requirePlanSubject(String planId, String id) {
        return planSubjects.findById(id).filter(item -> planId.equals(item.getPlanId()))
                .orElseThrow(() -> ApiException.notFound("Môn trong kế hoạch"));
    }

    private String requireProgramId(AcademicTrainingPlan plan) {
        return plan.getProgramId() == null || plan.getProgramId().isBlank()
                ? "program-gdpt-2018" : plan.getProgramId();
    }

    private int periodsForSemester(List<AcademicTrainingPlanSubject> rows,
                                   Map<String, Integer> sequences, int sequence) {
        return rows.stream().filter(row -> sequences.getOrDefault(row.getSemesterId(), 0) == sequence)
                .mapToInt(AcademicTrainingPlanSubject::getTotalPeriods).sum();
    }

    private String normalize(String value, Set<String> allowed, String label) {
        String normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (!allowed.contains(normalized)) throw ApiException.badRequest(label + " không hợp lệ");
        return normalized;
    }

    private void record(AcademicTrainingPlan plan, String action, String from,
                        String to, String actorId, String comment) {
        approvalHistory.save(AcademicPlanApprovalHistory.builder()
                .id(Ids.gen("plan-approval")).planId(plan.getId()).action(action)
                .fromStatus(from).toStatus(to).actorId(actorId)
                .comment(comment.trim()).createdAt(Instant.now()).build());
    }

    private void error(List<ValidationIssue> issues, String code, String message, String ref) {
        issues.add(new ValidationIssue("ERROR", code, message, ref));
    }

    private void warning(List<ValidationIssue> issues, String code, String message, String ref) {
        issues.add(new ValidationIssue("WARNING", code, message, ref));
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String defaultValue(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private String assessmentTypeName(String type) {
        return switch (type) {
            case "REGULAR" -> "Kiểm tra thường xuyên";
            case "MIDTERM" -> "Kiểm tra giữa kỳ";
            case "FINAL" -> "Kiểm tra cuối kỳ";
            case "MAKEUP" -> "Kiểm tra bù";
            case "PRACTICE" -> "Bài thực hành";
            case "PROJECT" -> "Bài dự án";
            default -> "Kế hoạch đánh giá";
        };
    }

    private void syncAssessmentWeek(
            AcademicTrainingPlanSubject subject, int weekNumber, String type, Instant now) {
        AcademicTrainingPlanSpecialWeek week = specialWeeks
                .findByPlanSubjectIdAndWeekNumber(subject.getId(), weekNumber)
                .orElseGet(() -> AcademicTrainingPlanSpecialWeek.builder()
                        .id(Ids.gen("week")).planSubjectId(subject.getId())
                        .createdAt(now).build());
        if (!"EXAM".equals(week.getWeekType()) && week.getId() != null
                && week.getUpdatedAt() != null) {
            throw ApiException.conflict("Tuần " + weekNumber
                    + " đang là tuần dự phòng; hãy chọn tuần khác hoặc cập nhật tuần đặc biệt");
        }
        week.setWeekType("EXAM");
        week.setWeekNumber(weekNumber);
        week.setName("Tuần " + assessmentTypeName(type).toLowerCase(Locale.ROOT));
        week.setDescription("Tự động từ kế hoạch đánh giá");
        week.setUpdatedAt(now);
        specialWeeks.save(week);
    }

    private void cleanupAssessmentWeek(
            String planId, String semesterId, String subjectId, int weekNumber) {
        if (semesterId == null || subjectId == null || weekNumber <= 0) return;
        if (assessments.countByPlanIdAndSemesterIdAndSubjectIdAndWeekNumber(
                planId, semesterId, subjectId, weekNumber) > 0) return;
        planSubjects.findByPlanIdAndSemesterIdAndSubjectId(planId, semesterId, subjectId)
                .flatMap(subject -> specialWeeks.findByPlanSubjectIdAndWeekNumber(
                        subject.getId(), weekNumber))
                .filter(week -> "EXAM".equals(week.getWeekType())
                        && week.getDescription() != null
                        && week.getDescription().startsWith("Tự động từ kế hoạch đánh giá"))
                .ifPresent(specialWeeks::delete);
    }
}
