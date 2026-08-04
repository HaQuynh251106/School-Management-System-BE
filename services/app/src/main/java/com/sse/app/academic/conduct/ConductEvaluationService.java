package com.sse.app.academic.conduct;

import com.sse.app.academic.structure.*;
import com.sse.app.academic.timetable.TeachingAssignmentService;
import com.sse.app.common.ApiException;
import com.sse.app.common.Ids;
import com.sse.app.identity.User;
import com.sse.app.identity.UserService;
import com.sse.app.security.CurrentUser;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Date;
import java.time.*;
import java.util.*;

import static com.sse.app.academic.conduct.ConductDtos.*;

@Service
@RequiredArgsConstructor
public class ConductEvaluationService {
    private static final Set<String> GRADES = Set.of("GOOD", "FAIR", "AVERAGE", "WEAK");
    private static final Map<String, String> LABELS = Map.of(
            "ATTENDANCE", "Chuyên cần", "DISCIPLINE", "Ý thức và kỷ luật",
            "RESPONSIBILITY", "Trách nhiệm học tập", "PARTICIPATION", "Tham gia và đóng góp");

    private final ConductRuleSetRepository ruleSets;
    private final ConductEvidenceRepository evidenceRepository;
    private final ConductEvaluationRepository evaluations;
    private final ConductEvaluationAuditRepository audits;
    private final StructureService structure;
    private final TeachingAssignmentService teachingAssignments;
    private final UserService users;
    private final JdbcTemplate jdbc;

    @Transactional
    public RuleSetView activeRules(String academicYearId, String semesterId) {
        validateScope(academicYearId, semesterId);
        return ruleView(activeRule(academicYearId, clean(semesterId)));
    }

    @Transactional
    public RuleSetView replaceRules(String academicYearId, SaveRuleSetRequest request, CurrentUser actor) {
        if (!actor.isAcademicStaff()) throw ApiException.forbidden("Chỉ Giáo vụ được cấu hình tiêu chí rèn luyện");
        validateScope(academicYearId, request.semesterId());
        double total = request.attendanceWeight() + request.disciplineWeight()
                + request.responsibilityWeight() + request.participationWeight();
        if (Math.abs(total - 100d) > 0.01d) throw ApiException.badRequest("Tổng trọng số tiêu chí phải bằng 100%");
        if (!(request.goodMin() > request.fairMin() && request.fairMin() > request.averageMin())) {
            throw ApiException.badRequest("Ngưỡng Tốt phải lớn hơn Khá và Khá phải lớn hơn Trung bình");
        }
        String semesterId = clean(request.semesterId());
        List<ConductRuleSet> history = history(academicYearId, semesterId);
        history.stream().filter(item -> "ACTIVE".equals(item.getStatus())).forEach(item -> item.setStatus("ARCHIVED"));
        ruleSets.saveAll(history);
        Instant now = Instant.now();
        ConductRuleSet created = ruleSets.save(ConductRuleSet.builder()
                .id(Ids.gen("crs")).academicYearId(academicYearId).semesterId(semesterId)
                .scopeKey(scopeKey(semesterId))
                .versionNo(history.stream().mapToInt(ConductRuleSet::getVersionNo).max().orElse(0) + 1)
                .status("ACTIVE").attendanceWeight(request.attendanceWeight())
                .disciplineWeight(request.disciplineWeight()).responsibilityWeight(request.responsibilityWeight())
                .participationWeight(request.participationWeight()).goodMin(request.goodMin())
                .fairMin(request.fairMin()).averageMin(request.averageMin())
                .minAttendanceRecords(request.minAttendanceRecords())
                .minParticipationEvidence(request.minParticipationEvidence())
                .createdBy(actor.id()).createdAt(now).activatedAt(now).build());
        return ruleView(created);
    }

    @Transactional
    public EvidenceView addEvidence(SaveEvidenceRequest request, CurrentUser actor) {
        if (!actor.isTeacher()) throw ApiException.forbidden("Chỉ giáo viên được ghi nhận minh chứng rèn luyện");
        validateScope(request.academicYearId(), request.semesterId());
        SchoolClass schoolClass = structure.getClass(request.classId());
        if (!request.academicYearId().equals(schoolClass.getAcademicYearId())) {
            throw ApiException.badRequest("Lớp không thuộc năm học đã chọn");
        }
        User student = users.getById(request.studentId());
        if (!"STUDENT".equals(student.getRole()) || !request.classId().equals(student.getClassId())) {
            throw ApiException.badRequest("Học sinh không thuộc lớp đã chọn");
        }
        boolean homeroom = actor.id().equals(schoolClass.getHomeroomTeacherId());
        boolean subjectTeacher = teachingAssignments.isAssigned(actor.id(), request.classId());
        if (!homeroom && !subjectTeacher) {
            throw ApiException.forbidden("Giáo viên chỉ được ghi minh chứng cho lớp đang dạy hoặc chủ nhiệm");
        }
        DateRange range = dateRange(request.academicYearId(), request.semesterId());
        if (request.occurredOn().isBefore(range.from()) || request.occurredOn().isAfter(range.to())) {
            throw ApiException.badRequest("Ngày minh chứng nằm ngoài phạm vi năm học/học kỳ");
        }
        assertEvaluationEditable(request.academicYearId(), request.studentId());
        String id = Ids.gen("cev");
        String sourceRef = request.externalKey() == null || request.externalKey().isBlank()
                ? id : actor.id() + ":" + request.externalKey().trim().toLowerCase(Locale.ROOT);
        if (evidenceRepository.findByAcademicYearIdAndSourceTypeAndSourceRef(
                request.academicYearId(), "MANUAL", sourceRef).isPresent()) {
            throw ApiException.conflict("Minh chứng này đã được ghi nhận trước đó");
        }
        Instant now = Instant.now();
        ConductEvidence saved;
        try {
            saved = evidenceRepository.save(ConductEvidence.builder().id(id)
                    .academicYearId(request.academicYearId()).semesterId(clean(request.semesterId()))
                    .studentId(request.studentId()).classId(request.classId()).teacherId(actor.id())
                    .category(request.category()).impactPoints(round(request.impactPoints()))
                    .title(request.title().trim()).description(clean(request.description()))
                    .occurredOn(request.occurredOn()).sourceType("MANUAL").sourceRef(sourceRef)
                    .createdAt(now).updatedAt(now).build());
        } catch (DataIntegrityViolationException duplicate) {
            throw ApiException.conflict("Minh chứng này đã được ghi nhận trước đó");
        }
        return evidenceView(saved);
    }

    @Transactional
    public EvaluationView evaluate(String academicYearId, String semesterId, String studentId, CurrentUser actor) {
        validateScope(academicYearId, semesterId);
        User student = users.getById(studentId);
        SchoolClass schoolClass = structure.getClass(student.getClassId());
        if (!academicYearId.equals(schoolClass.getAcademicYearId())) {
            throw ApiException.badRequest("Học sinh không thuộc năm học đã chọn");
        }
        assertViewAccess(studentId, schoolClass, actor);
        if ((actor.isStudent() || actor.isParent()) && !isPublished(academicYearId, studentId)) {
            throw ApiException.notFound("Kết quả rèn luyện chưa được nhà trường phát hành");
        }
        String normalizedSemesterId = clean(semesterId);
        Optional<ConductEvaluation> existingEvaluation = evaluation(academicYearId, normalizedSemesterId, studentId);
        ConductRuleSet rules = existingEvaluation
                .filter(item -> Set.of("LOCKED", "PUBLISHED").contains(item.getWorkflowStatus()))
                .map(item -> ruleSets.findById(item.getRuleSetId()).orElse(null))
                .orElseGet(() -> activeRule(academicYearId, normalizedSemesterId));
        Calculation calculation = calculate(rules, studentId, schoolClass.getId());
        ConductEvaluation evaluation = existingEvaluation
                .orElseGet(() -> ConductEvaluation.builder().id(Ids.gen("ceval"))
                        .academicYearId(academicYearId).semesterId(normalizedSemesterId).studentId(studentId)
                        .scopeKey(scopeKey(normalizedSemesterId))
                        .classId(schoolClass.getId()).workflowStatus(reportCardStatus(academicYearId, studentId))
                        .build());
        boolean frozen = Set.of("LOCKED", "PUBLISHED").contains(evaluation.getWorkflowStatus());
        if (!frozen) {
            evaluation.setRuleSetId(rules.getId());
            evaluation.setReadiness(calculation.missing().isEmpty() ? "READY" : "INSUFFICIENT_DATA");
            evaluation.setSuggestedScore(calculation.score());
            evaluation.setSuggestedGrade(calculation.grade());
            evaluation.setCalculatedAt(Instant.now());
            evaluation.setUpdatedAt(Instant.now());
            evaluation = evaluations.save(evaluation);
        }
        return view(evaluation, rules, student, schoolClass, calculation, actor);
    }

    @Transactional
    public EvaluationView decide(String academicYearId, String studentId, DecisionRequest request, CurrentUser actor) {
        if (!actor.isTeacher()) throw ApiException.forbidden("Chỉ giáo viên chủ nhiệm được quyết định mức rèn luyện");
        User student = users.getById(studentId);
        SchoolClass schoolClass = structure.getClass(student.getClassId());
        if (!academicYearId.equals(schoolClass.getAcademicYearId())
                || !actor.id().equals(schoolClass.getHomeroomTeacherId())) {
            throw ApiException.forbidden("Chỉ giáo viên chủ nhiệm của lớp được quyết định mức rèn luyện");
        }
        assertEvaluationEditable(academicYearId, studentId);
        EvaluationView current = evaluate(academicYearId, request.semesterId(), studentId, actor);
        boolean override = current.suggestedGrade() == null || !request.finalGrade().equals(current.suggestedGrade());
        if (override && (request.overrideReason() == null || request.overrideReason().isBlank())) {
            throw ApiException.badRequest("Cần ghi rõ lý do khi quyết định khác mức hệ thống đề xuất hoặc khi chưa đủ căn cứ");
        }
        ConductEvaluation evaluation = evaluations.findById(current.id()).orElseThrow();
        String previous = evaluation.getFinalGrade();
        evaluation.setFinalGrade(request.finalGrade());
        evaluation.setOverrideReason(override ? request.overrideReason().trim() : clean(request.overrideReason()));
        evaluation.setDecidedBy(actor.id());
        evaluation.setDecidedAt(Instant.now());
        evaluation.setWorkflowStatus("DRAFT");
        evaluation.setUpdatedAt(Instant.now());
        evaluations.save(evaluation);
        audit(evaluation, "HOMEROOM_DECIDED", previous, request.finalGrade(), evaluation.getOverrideReason(), actor.id());
        return evaluate(academicYearId, request.semesterId(), studentId, actor);
    }

    @Transactional
    public void syncWorkflow(String academicYearId, String studentId, String workflowStatus,
                             String action, String note, String actorId) {
        evaluation(academicYearId, null, studentId).ifPresent(evaluation -> {
            evaluation.setWorkflowStatus(workflowStatus);
            evaluation.setUpdatedAt(Instant.now());
            evaluations.save(evaluation);
            audit(evaluation, action, evaluation.getFinalGrade(), evaluation.getFinalGrade(), clean(note), actorId);
        });
    }

    private Calculation calculate(ConductRuleSet rules, String studentId, String classId) {
        DateRange range = dateRange(rules.getAcademicYearId(), rules.getSemesterId());
        List<ConductEvidence> manual = evidenceRepository
                .findByAcademicYearIdAndStudentIdOrderByOccurredOnDescCreatedAtDesc(rules.getAcademicYearId(), studentId)
                .stream().filter(item -> !item.getOccurredOn().isBefore(range.from()) && !item.getOccurredOn().isAfter(range.to()))
                .filter(item -> rules.getSemesterId() == null || rules.getSemesterId().equals(item.getSemesterId()))
                .toList();
        List<String> missing = new ArrayList<>();
        List<CriterionBreakdown> criteria = new ArrayList<>();

        Map<String, Integer> attendance = counts("""
                select status, count(*) amount from attendance_records
                where student_id=? and date between ? and ? group by status
                """, studentId, Date.valueOf(range.from()), Date.valueOf(range.to()));
        int present = attendance.getOrDefault("PRESENT", 0);
        int excused = attendance.getOrDefault("ABSENT_EXCUSED", 0);
        int unexcused = attendance.getOrDefault("ABSENT_UNEXCUSED", 0);
        int late = attendance.getOrDefault("LATE", 0);
        int total = present + excused + unexcused + late;
        boolean attendanceReady = total >= rules.getMinAttendanceRecords();
        if (!attendanceReady) missing.add("Chuyên cần mới có " + total + "/" + rules.getMinAttendanceRecords() + " lượt ghi nhận");
        Double attendanceScore = attendanceReady ? clamp(100d * (present + excused + late * 0.5d) / Math.max(1, total)) : null;
        List<EvidenceView> attendanceEvidence = new ArrayList<>();
        if (unexcused > 0) attendanceEvidence.add(systemEvidence("ATTENDANCE", -unexcused * 8d,
                unexcused + " lượt vắng không phép", "Mỗi lượt vắng không phép làm giảm chỉ số chuyên cần", range.to(), "ATTENDANCE:UNEXCUSED"));
        if (late > 0) attendanceEvidence.add(systemEvidence("ATTENDANCE", -late * 2d,
                late + " lượt đi muộn", "Đi muộn được tính 50% một lượt chuyên cần", range.to(), "ATTENDANCE:LATE"));
        if (excused > 0) attendanceEvidence.add(systemEvidence("ATTENDANCE", 0,
                excused + " lượt nghỉ có phép", "Nghỉ có phép không bị trừ điểm rèn luyện", range.to(), "ATTENDANCE:EXCUSED"));
        criteria.add(criterion("ATTENDANCE", rules.getAttendanceWeight(), attendanceScore, attendanceReady,
                attendanceReady ? present + " có mặt · " + excused + " có phép · " + unexcused + " không phép · " + late + " đi muộn"
                        : "Chưa đủ dữ liệu chuyên cần", attendanceEvidence));

        List<EvidenceView> disciplineEvidence = new ArrayList<>();
        double disciplineImpact = manualImpact(manual, "DISCIPLINE", disciplineEvidence);
        List<Map<String, Object>> interventions = jdbc.queryForList("""
                select id, severity, title, description, created_at from student_interventions
                where student_id=? and category='BEHAVIOR' and created_at between ? and ?
                order by created_at desc
                """, studentId, range.from().atStartOfDay(), range.to().plusDays(1).atStartOfDay());
        for (Map<String, Object> item : interventions) {
            double impact = switch (String.valueOf(item.get("severity"))) { case "HIGH" -> -20d; case "MEDIUM" -> -10d; default -> -5d; };
            disciplineImpact += impact;
            disciplineEvidence.add(systemEvidence("DISCIPLINE", impact, String.valueOf(item.get("title")),
                    Objects.toString(item.get("description"), null), toLocalDate(item.get("created_at")),
                    "INTERVENTION:" + item.get("id")));
        }
        Double disciplineScore = clamp(100d + disciplineImpact);
        criteria.add(criterion("DISCIPLINE", rules.getDisciplineWeight(), disciplineScore, true,
                disciplineEvidence.isEmpty() ? "Không có vi phạm hoặc minh chứng điều chỉnh" : disciplineEvidence.size() + " minh chứng", disciplineEvidence));

        Map<String, Object> assignment = jdbc.queryForMap("""
                select count(distinct a.id) total,
                       count(distinct case when s.submitted_at is not null then a.id end) completed
                from assignments a left join assignment_submissions s
                  on s.assignment_id=a.id and s.student_id=?
                where a.class_id=? and a.status in ('PUBLISHED','CLOSED')
                  and coalesce(a.deadline, a.created_at) between ? and ?
                """, studentId, classId, range.from().atStartOfDay(), range.to().plusDays(1).atStartOfDay());
        int assignmentTotal = number(assignment.get("total"));
        int assignmentCompleted = number(assignment.get("completed"));
        List<EvidenceView> responsibilityEvidence = new ArrayList<>();
        double responsibilityImpact = manualImpact(manual, "RESPONSIBILITY", responsibilityEvidence);
        boolean responsibilityReady = assignmentTotal > 0 || !responsibilityEvidence.isEmpty();
        if (!responsibilityReady) missing.add("Chưa có bài tập hoặc minh chứng trách nhiệm học tập");
        Double responsibilityScore = responsibilityReady
                ? clamp((assignmentTotal > 0 ? 100d * assignmentCompleted / assignmentTotal : 100d) + responsibilityImpact) : null;
        if (assignmentTotal > 0 && assignmentCompleted < assignmentTotal) {
            responsibilityEvidence.add(systemEvidence("RESPONSIBILITY", 0,
                    (assignmentTotal - assignmentCompleted) + " bài chưa nộp", "Hoàn thành " + assignmentCompleted + "/" + assignmentTotal + " bài tập",
                    range.to(), "ASSIGNMENT:COMPLETION"));
        }
        criteria.add(criterion("RESPONSIBILITY", rules.getResponsibilityWeight(), responsibilityScore, responsibilityReady,
                responsibilityReady ? "Hoàn thành " + assignmentCompleted + "/" + assignmentTotal + " bài tập" : "Chưa đủ dữ liệu trách nhiệm học tập",
                responsibilityEvidence));

        List<EvidenceView> participationEvidence = new ArrayList<>();
        double participationImpact = manualImpact(manual, "PARTICIPATION", participationEvidence);
        boolean participationReady = participationEvidence.size() >= rules.getMinParticipationEvidence();
        if (!participationReady) missing.add("Tham gia và đóng góp mới có " + participationEvidence.size() + "/"
                + rules.getMinParticipationEvidence() + " minh chứng");
        Double participationScore = participationReady ? clamp(70d + participationImpact) : null;
        criteria.add(criterion("PARTICIPATION", rules.getParticipationWeight(), participationScore, participationReady,
                participationEvidence.isEmpty() ? "Chưa có ghi nhận cộng/trừ; áp dụng mức nền 70" : participationEvidence.size() + " minh chứng",
                participationEvidence));

        if (!missing.isEmpty()) return new Calculation(null, null, List.copyOf(missing), List.copyOf(criteria));
        double score = round(criteria.stream().mapToDouble(item -> item.weightedScore() == null ? 0d : item.weightedScore()).sum());
        return new Calculation(score, grade(score, rules), List.of(), List.copyOf(criteria));
    }

    private double manualImpact(List<ConductEvidence> evidence, String category, List<EvidenceView> target) {
        double result = 0;
        for (ConductEvidence item : evidence) if (category.equals(item.getCategory())) {
            result += item.getImpactPoints(); target.add(evidenceView(item));
        }
        return result;
    }

    private CriterionBreakdown criterion(String code, double weight, Double raw, boolean sufficient,
                                         String summary, List<EvidenceView> evidence) {
        return new CriterionBreakdown(code, LABELS.get(code), weight, raw == null ? null : round(raw),
                raw == null ? null : round(raw * weight / 100d), sufficient, summary, List.copyOf(evidence));
    }

    private ConductRuleSet activeRule(String academicYearId, String semesterId) {
        return history(academicYearId, semesterId).stream().filter(item -> "ACTIVE".equals(item.getStatus())).findFirst()
                .orElseGet(() -> createDefaultRule(academicYearId, semesterId));
    }

    private ConductRuleSet createDefaultRule(String academicYearId, String semesterId) {
        Instant now = Instant.now();
        try {
            return ruleSets.save(ConductRuleSet.builder().id(Ids.gen("crs")).academicYearId(academicYearId)
                    .semesterId(semesterId).scopeKey(scopeKey(semesterId)).versionNo(1).status("ACTIVE")
                    .attendanceWeight(35).disciplineWeight(30).responsibilityWeight(20).participationWeight(15)
                    .goodMin(85).fairMin(70).averageMin(50).minAttendanceRecords(10)
                    .minParticipationEvidence(0).createdBy("SYSTEM").createdAt(now).activatedAt(now).build());
        } catch (DataIntegrityViolationException race) {
            return history(academicYearId, semesterId).stream().filter(item -> "ACTIVE".equals(item.getStatus()))
                    .findFirst().orElseThrow(() -> race);
        }
    }

    private List<ConductRuleSet> history(String academicYearId, String semesterId) {
        return semesterId == null ? ruleSets.findByAcademicYearIdAndSemesterIdIsNullOrderByVersionNoDesc(academicYearId)
                : ruleSets.findByAcademicYearIdAndSemesterIdOrderByVersionNoDesc(academicYearId, semesterId);
    }

    private Optional<ConductEvaluation> evaluation(String yearId, String semesterId, String studentId) {
        return semesterId == null ? evaluations.findByAcademicYearIdAndSemesterIdIsNullAndStudentId(yearId, studentId)
                : evaluations.findByAcademicYearIdAndSemesterIdAndStudentId(yearId, semesterId, studentId);
    }

    private String scopeKey(String semesterId) {
        return semesterId == null ? "ANNUAL" : semesterId;
    }

    private EvaluationView view(ConductEvaluation evaluation, ConductRuleSet rules, User student,
                                SchoolClass schoolClass, Calculation calculation, CurrentUser actor) {
        return new EvaluationView(evaluation.getId(), evaluation.getAcademicYearId(), evaluation.getSemesterId(),
                evaluation.getStudentId(), student.getFullName(), evaluation.getClassId(), schoolClass.getCode(),
                ruleView(rules), evaluation.getReadiness(), calculation.missing(), evaluation.getSuggestedScore(),
                evaluation.getSuggestedGrade(), evaluation.getFinalGrade(), evaluation.getOverrideReason(),
                evaluation.getWorkflowStatus(), evaluation.getDecidedBy(), nameOf(evaluation.getDecidedBy()),
                instant(evaluation.getDecidedAt()), instant(evaluation.getCalculatedAt()), calculation.criteria(),
                audits.findByEvaluationIdOrderByCreatedAtDesc(evaluation.getId()).stream().map(this::auditView).toList(),
                actor.isTeacher() && actor.id().equals(schoolClass.getHomeroomTeacherId())
                        && !Set.of("LOCKED", "PUBLISHED").contains(evaluation.getWorkflowStatus()));
    }

    private void validateScope(String academicYearId, String semesterId) {
        structure.getYear(academicYearId);
        if (semesterId != null && !semesterId.isBlank()) {
            Semester semester = structure.getSemester(semesterId);
            if (!academicYearId.equals(semester.getAcademicYearId())) throw ApiException.badRequest("Học kỳ không thuộc năm học đã chọn");
        }
    }

    private DateRange dateRange(String academicYearId, String semesterId) {
        if (semesterId != null) {
            Semester semester = structure.getSemester(semesterId);
            return new DateRange(semester.getStartDate(), semester.getEndDate());
        }
        AcademicYear year = structure.getYear(academicYearId);
        return new DateRange(year.getStartDate(), year.getEndDate());
    }

    private void assertViewAccess(String studentId, SchoolClass schoolClass, CurrentUser actor) {
        if (actor.canManageAcademics()) return;
        if (actor.isStudent() && actor.id().equals(studentId)) return;
        if (actor.isParent()) { users.assertParentOf(actor.id(), studentId); return; }
        if (actor.isTeacher() && (actor.id().equals(schoolClass.getHomeroomTeacherId())
                || teachingAssignments.isAssigned(actor.id(), schoolClass.getId()))) return;
        throw ApiException.forbidden("Không có quyền xem đánh giá rèn luyện này");
    }

    private void assertEvaluationEditable(String academicYearId, String studentId) {
        String status = reportCardStatus(academicYearId, studentId);
        if (Set.of("LOCKED", "PUBLISHED").contains(status)) throw ApiException.conflict("Học bạ đã khóa; Giáo vụ phải mở bản điều chỉnh trước khi thay đổi");
    }

    private boolean isPublished(String academicYearId, String studentId) {
        return "PUBLISHED".equals(reportCardStatus(academicYearId, studentId));
    }

    private String reportCardStatus(String academicYearId, String studentId) {
        List<String> rows = jdbc.queryForList("select status from report_cards where academic_year_id=? and student_id=?",
                String.class, academicYearId, studentId);
        return rows.isEmpty() ? "DRAFT" : rows.get(0);
    }

    private void audit(ConductEvaluation evaluation, String action, String previous, String next, String note, String actorId) {
        audits.save(ConductEvaluationAudit.builder().id(Ids.gen("ceaud")).evaluationId(evaluation.getId())
                .action(action).previousGrade(previous).newGrade(next).note(note).actorId(actorId)
                .createdAt(Instant.now()).build());
    }

    private Map<String, Integer> counts(String sql, Object... args) {
        Map<String, Integer> result = new HashMap<>();
        org.springframework.jdbc.core.RowCallbackHandler handler =
                rs -> result.put(rs.getString(1), rs.getInt(2));
        jdbc.query(sql, handler, args);
        return result;
    }

    private RuleSetView ruleView(ConductRuleSet item) {
        return new RuleSetView(item.getId(), item.getAcademicYearId(), item.getSemesterId(), item.getVersionNo(),
                item.getStatus(), item.getAttendanceWeight(), item.getDisciplineWeight(), item.getResponsibilityWeight(),
                item.getParticipationWeight(), item.getGoodMin(), item.getFairMin(), item.getAverageMin(),
                item.getMinAttendanceRecords(), item.getMinParticipationEvidence(), item.getCreatedBy(),
                instant(item.getCreatedAt()), instant(item.getActivatedAt()));
    }

    private EvidenceView evidenceView(ConductEvidence item) {
        return new EvidenceView(item.getId(), item.getCategory(), item.getImpactPoints(), item.getTitle(),
                item.getDescription(), item.getOccurredOn(), item.getSourceType(), item.getSourceRef(),
                item.getTeacherId(), nameOf(item.getTeacherId()), instant(item.getCreatedAt()));
    }

    private EvidenceView systemEvidence(String category, double impact, String title, String description,
                                        LocalDate date, String sourceRef) {
        return new EvidenceView(sourceRef, category, impact, title, description, date, "SYSTEM", sourceRef,
                "SYSTEM", "Hệ thống", null);
    }

    private AuditView auditView(ConductEvaluationAudit item) {
        return new AuditView(item.getId(), item.getAction(), item.getPreviousGrade(), item.getNewGrade(), item.getNote(),
                item.getActorId(), nameOf(item.getActorId()), instant(item.getCreatedAt()));
    }

    private String grade(double score, ConductRuleSet rules) {
        if (score >= rules.getGoodMin()) return "GOOD";
        if (score >= rules.getFairMin()) return "FAIR";
        if (score >= rules.getAverageMin()) return "AVERAGE";
        return "WEAK";
    }

    private int number(Object value) { return value instanceof Number number ? number.intValue() : 0; }
    private String nameOf(String userId) { return userId == null ? null : users.fullNameOf(userId); }
    private double clamp(double value) { return Math.max(0d, Math.min(100d, value)); }
    private double round(double value) { return Math.round(value * 100d) / 100d; }
    private String clean(String value) { return value == null || value.isBlank() ? null : value.trim(); }
    private String instant(Instant value) { return value == null ? null : value.toString(); }
    private LocalDate toLocalDate(Object value) {
        if (value instanceof java.sql.Timestamp timestamp) return timestamp.toLocalDateTime().toLocalDate();
        if (value instanceof LocalDateTime dateTime) return dateTime.toLocalDate();
        if (value instanceof java.util.Date date) return date.toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
        return LocalDate.now();
    }

    private record DateRange(LocalDate from, LocalDate to) {}
    private record Calculation(Double score, String grade, List<String> missing, List<CriterionBreakdown> criteria) {}
}
