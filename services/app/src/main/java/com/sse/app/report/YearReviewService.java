package com.sse.app.report;

import com.sse.app.academic.structure.AcademicYear;
import com.sse.app.academic.structure.SchoolClass;
import com.sse.app.academic.structure.Semester;
import com.sse.app.academic.structure.StructureService;
import com.sse.app.audit.AuditService;
import com.sse.app.common.ApiException;
import com.sse.app.common.Ids;
import com.sse.app.identity.UserService;
import com.sse.app.report.YearReviewDtos.AnnualSubjectResult;
import com.sse.app.report.YearReviewDtos.PromotionPolicy;
import com.sse.app.report.YearReviewDtos.SemesterResult;
import com.sse.app.report.YearReviewDtos.UpdatePromotionPolicyRequest;
import com.sse.app.report.YearReviewDtos.YearReviewMetrics;
import com.sse.app.report.YearReviewDtos.YearReviewResponse;
import com.sse.app.report.YearReviewDtos.YearReviewStudent;
import com.sse.app.report.YearSummaryPreviewDtos.StudentSummaryRow;
import com.sse.app.report.YearSummaryPreviewDtos.SubjectSummary;
import com.sse.app.report.YearSummaryPreviewDtos.YearSummaryPreviewResponse;
import com.sse.app.security.CurrentUser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Service
public class YearReviewService {
    private static final String YEARLY_FORMULA = "(HK1 + HK2 x 2) / 3";
    private static final Set<String> RESULTS = Set.of(
            "PROMOTED", "RETAINED", "ELIGIBLE_FOR_GRADUATION",
            "INCOMPLETE", "PENDING_REVIEW");
    private static final Set<String> CONDUCT_GRADES = Set.of("GOOD", "FAIR", "PASS", "FAIL");
    private static final Map<String, Integer> CONDUCT_RANK = Map.of(
            "FAIL", 1, "PASS", 2, "FAIR", 3, "GOOD", 4);

    private final StructureService structure;
    private final YearSummaryPreviewService previews;
    private final StudentYearlySummaryRepository summaries;
    private final AcademicPromotionPolicyRepository policies;
    private final AcademicResultLockService locks;
    private final AuditService audit;
    private final UserService users;

    public YearReviewService(StructureService structure,
                             YearSummaryPreviewService previews,
                             StudentYearlySummaryRepository summaries,
                             AcademicPromotionPolicyRepository policies,
                             AcademicResultLockService locks,
                             AuditService audit,
                             UserService users) {
        this.structure = structure;
        this.previews = previews;
        this.summaries = summaries;
        this.policies = policies;
        this.locks = locks;
        this.audit = audit;
        this.users = users;
    }

    public YearReviewResponse review(String academicYearId, String classId, CurrentUser actor) {
        AcademicYear year = requireYear(academicYearId);
        SchoolClass schoolClass = structure.getClass(require(classId, "classId"));
        if (!year.getId().equals(schoolClass.getAcademicYearId())) {
            throw ApiException.badRequest("Lớp không thuộc năm học đã chọn");
        }
        List<Semester> semesters = structure.listSemesters(year.getId()).stream()
                .sorted(Comparator.comparingInt(Semester::getSequence))
                .toList();
        if (semesters.size() != 2
                || semesters.stream().noneMatch(semester -> semester.getSequence() == 1)
                || semesters.stream().noneMatch(semester -> semester.getSequence() == 2)) {
            throw ApiException.badRequest("Năm học phải có đủ hai học kỳ HK1 và HK2");
        }

        List<YearSummaryPreviewResponse> semesterPreviews = semesters.stream()
                .map(semester -> previews.preview(year.getId(), semester.getId(), schoolClass.getId(), actor))
                .toList();
        Map<String, StudentYearlySummary> savedByStudent = new LinkedHashMap<>();
        summaries.findByAcademicYearIdAndClassId(year.getId(), schoolClass.getId())
                .forEach(summary -> savedByStudent.put(summary.getStudentId(), summary));

        PromotionPolicy policy = policy(year.getId());
        List<YearReviewStudent> rows = buildRows(
                schoolClass, semesters, semesterPreviews, savedByStudent, policy);
        boolean yearClosed = "CLOSED".equalsIgnoreCase(year.getStatus());
        boolean finalized = !rows.isEmpty() && rows.stream()
                .allMatch(row -> "FINALIZED".equals(row.decisionStatus()));
        List<String> blockers = finalizeBlockers(yearClosed, schoolClass, rows);
        return new YearReviewResponse(
                year.getId(), display(year.getName(), year.getCode()),
                schoolClass.getId(), schoolClass.getCode(), display(schoolClass.getName(), schoolClass.getCode()),
                schoolClass.getGradeLevel(), year.getStatus(),
                yearClosed, finalized, blockers.isEmpty() && !finalized, blockers,
                YEARLY_FORMULA, policy, metrics(rows), rows, Instant.now());
    }

    @Transactional
    public YearReviewResponse saveDecision(String academicYearId, String classId, String studentId,
                                           String result, String conductGrade, String reason,
                                           CurrentUser actor) {
        YearReviewResponse review = review(academicYearId, classId, actor);
        YearReviewStudent row = review.students().stream()
                .filter(student -> student.studentId().equals(studentId))
                .findFirst().orElseThrow(() -> ApiException.notFound("Học sinh trong lớp"));
        String normalizedConduct = normalizeConduct(conductGrade);
        String normalizedResult = normalizeResult(result);
        validateResultForGradeLevel(review.gradeLevel(), normalizedResult);

        StudentYearlySummary summary = summaries.findByAcademicYearIdAndStudentId(
                academicYearId, studentId).orElse(null);
        boolean finalized = summary != null && "FINALIZED".equals(summary.getStatus());
        if (finalized && !actor.isAdmin()) {
            throw ApiException.forbidden("Chỉ Admin được sửa kết quả đã chốt");
        }
        String expectedResult = suggestResult(review.gradeLevel(), row.academicReady(),
                row.yearlyAverage(), row.attendanceRate(), normalizedConduct,
                row.subjectsBelowMinimum(), review.policy());
        boolean changesFinalResult = finalized && !normalizedResult.equals(summary.getResult());
        boolean changesFinalConduct = finalized && !normalizedConduct.equals(summary.getConductGrade());
        if ((changesFinalResult || changesFinalConduct
                || requiresReason(normalizedResult)
                || !normalizedResult.equals(expectedResult))
                && (reason == null || reason.isBlank())) {
            throw ApiException.badRequest("Cần nhập lý do cho kết quả này");
        }

        String oldResult = summary == null ? null : summary.getResult();
        String oldConduct = summary == null ? null : summary.getConductGrade();
        if (summary == null) {
            summary = StudentYearlySummary.builder()
                    .id(Ids.gen("sys"))
                    .academicYearId(academicYearId)
                    .classId(classId)
                    .studentId(studentId)
                    .studentCode(row.studentCode())
                    .studentName(row.studentName())
                    .status("DRAFT")
                    .build();
        }
        summary.setYearlyAverage(row.yearlyAverage());
        summary.setAttendanceRate(row.attendanceRate());
        summary.setConductGrade(normalizedConduct);
        summary.setResult(normalizedResult);
        summary.setReason(blankToNull(reason));
        summary.setReviewedBy(actor.id());
        summary.setReviewedAt(Instant.now());
        summary.setUpdatedAt(Instant.now());
        summaries.save(summary);
        audit.record(actor.id(), users.fullNameOf(actor.id()), actor.role(),
                finalized ? "AMEND_RESULT" : "REVIEW_RESULT", "academic",
                "student_yearly_summary", summary.getId(),
                "Năm học=" + academicYearId + "; lớp=" + classId + "; học sinh=" + studentId
                        + "; kết quả=" + oldResult + " -> " + normalizedResult
                        + "; hạnh kiểm=" + oldConduct + " -> " + normalizedConduct
                        + "; lý do=" + (reason == null ? "" : reason.trim()));
        return review(academicYearId, classId, actor);
    }

    @Transactional
    public YearReviewResponse finalizeClass(String academicYearId, String classId,
                                            boolean confirmed, CurrentUser actor) {
        if (!actor.isAdmin()) throw ApiException.forbidden("Chỉ Admin được chốt kết quả năm học");
        if (!confirmed) throw ApiException.badRequest("Cần xác nhận thao tác chốt kết quả");
        YearReviewResponse review = review(academicYearId, classId, actor);
        if (!review.finalizeBlockers().isEmpty()) {
            throw ApiException.conflict(String.join("; ", review.finalizeBlockers()));
        }
        Instant now = Instant.now();
        for (YearReviewStudent row : review.students()) {
            StudentYearlySummary summary = summaries.findByAcademicYearIdAndStudentId(
                    academicYearId, row.studentId()).orElseThrow(
                    () -> ApiException.conflict("Chưa lưu kết quả của " + row.studentName()));
            summary.setYearlyAverage(row.yearlyAverage());
            summary.setAttendanceRate(row.attendanceRate());
            summary.setConductGrade(row.conductGrade());
            summary.setResult(row.result());
            summary.setStatus("FINALIZED");
            summary.setReviewedBy(summary.getReviewedBy() == null ? actor.id() : summary.getReviewedBy());
            summary.setReviewedAt(summary.getReviewedAt() == null ? now : summary.getReviewedAt());
            summary.setFinalizedBy(actor.id());
            summary.setFinalizedAt(now);
            summary.setUpdatedAt(now);
            summaries.save(summary);
        }
        List<String> semesterIds = structure.listSemesters(academicYearId).stream()
                .map(Semester::getId).toList();
        locks.lock(academicYearId, classId, semesterIds, actor.id());
        audit.record(actor.id(), users.fullNameOf(actor.id()), actor.role(),
                "FINALIZE", "academic", "year_review", academicYearId + ":" + classId,
                "Chốt kết quả năm học cho lớp " + classId
                        + "; số học sinh=" + review.students().size()
                        + "; công thức=" + YEARLY_FORMULA);
        return review(academicYearId, classId, actor);
    }

    @Transactional
    public YearReviewResponse reopenClass(String academicYearId, String classId,
                                          String reason, boolean confirmed, CurrentUser actor) {
        if (!actor.isAdmin()) throw ApiException.forbidden("Chỉ Admin được mở lại kết quả lớp");
        if (!confirmed) throw ApiException.badRequest("Cần xác nhận thao tác mở lại");
        String normalizedReason = require(reason, "lý do mở lại");
        List<StudentYearlySummary> classSummaries =
                summaries.findByAcademicYearIdAndClassId(academicYearId, classId);
        boolean hasFinalized = classSummaries.stream()
                .anyMatch(summary -> "FINALIZED".equals(summary.getStatus()));
        if (!hasFinalized && !locks.classLocked(academicYearId, classId)) {
            throw ApiException.conflict("Lớp chưa có kết quả đã chốt để mở lại");
        }
        Instant now = Instant.now();
        for (StudentYearlySummary summary : classSummaries) {
            if (!"FINALIZED".equals(summary.getStatus())) continue;
            summary.setStatus("DRAFT");
            summary.setFinalizedBy(null);
            summary.setFinalizedAt(null);
            summary.setUpdatedAt(now);
            summaries.save(summary);
        }
        locks.unlock(academicYearId, classId);
        audit.record(actor.id(), users.fullNameOf(actor.id()), actor.role(),
                "REOPEN_CLASS_RESULT", "academic", "year_review",
                academicYearId + ":" + classId, "Lý do=" + normalizedReason);
        return review(academicYearId, classId, actor);
    }

    @Transactional
    public AcademicYear changeYearStatus(String academicYearId, String status,
                                         String reason, boolean confirmed, CurrentUser actor) {
        if (!actor.isAdmin()) throw ApiException.forbidden("Chỉ Admin được đổi trạng thái năm học");
        if (!confirmed) throw ApiException.badRequest("Cần xác nhận thao tác đổi trạng thái năm học");
        String normalizedReason = require(reason, "lý do");
        String normalizedStatus = require(status, "status").toUpperCase();
        if (!Set.of("ACTIVE", "CLOSED").contains(normalizedStatus)) {
            throw ApiException.badRequest("Trạng thái chỉ được là ACTIVE hoặc CLOSED");
        }
        AcademicYear before = requireYear(academicYearId);
        String oldStatus = before.getStatus();
        AcademicYear updated = structure.updateYearStatus(academicYearId, normalizedStatus);
        audit.record(actor.id(), users.fullNameOf(actor.id()), actor.role(),
                "CLOSED".equals(normalizedStatus) ? "CLOSE_ACADEMIC_YEAR" : "REOPEN_ACADEMIC_YEAR",
                "academic", "academic_year", academicYearId,
                "Trạng thái=" + oldStatus + " -> " + normalizedStatus
                        + "; lý do=" + normalizedReason);
        return updated;
    }

    public PromotionPolicy getPolicy(String academicYearId) {
        requireYear(academicYearId);
        return policy(academicYearId);
    }

    @Transactional
    public PromotionPolicy updatePolicy(String academicYearId,
                                        UpdatePromotionPolicyRequest request,
                                        CurrentUser actor) {
        if (!actor.isAdmin()) throw ApiException.forbidden("Chỉ Admin được sửa quy tắc xét lên lớp");
        requireYear(academicYearId);
        if (summaries.existsByAcademicYearIdAndStatus(academicYearId, "FINALIZED")) {
            throw ApiException.conflict("Không thể đổi quy tắc khi năm học đã có lớp được chốt");
        }
        double minimumYearlyAverage = score(request.minimumYearlyAverage(), 5.0,
                "Điểm trung bình tối thiểu");
        String minimumConductGrade = request.minimumConductGrade() == null
                ? "PASS" : normalizeConduct(request.minimumConductGrade());
        double subjectMinimumScore = score(request.subjectMinimumScore(), 5.0,
                "Điểm môn tối thiểu");
        int maximumSubjectsBelowMinimum = request.maximumSubjectsBelowMinimum() == null
                ? 0 : request.maximumSubjectsBelowMinimum();
        if (maximumSubjectsBelowMinimum < 0 || maximumSubjectsBelowMinimum > 20) {
            throw ApiException.badRequest("Số môn dưới ngưỡng phải từ 0 đến 20");
        }
        Double minimumAttendanceRate = request.minimumAttendanceRate();
        if (minimumAttendanceRate != null
                && (minimumAttendanceRate < 0 || minimumAttendanceRate > 100)) {
            throw ApiException.badRequest("Tỷ lệ chuyên cần phải từ 0 đến 100");
        }
        AcademicPromotionPolicy entity = policies.findByAcademicYearId(academicYearId)
                .orElseGet(() -> AcademicPromotionPolicy.builder()
                        .id(Ids.gen("app")).academicYearId(academicYearId).build());
        entity.setMinimumYearlyAverage(minimumYearlyAverage);
        entity.setMinimumConductGrade(minimumConductGrade);
        entity.setSubjectMinimumScore(subjectMinimumScore);
        entity.setMaximumSubjectsBelowMinimum(maximumSubjectsBelowMinimum);
        entity.setMinimumAttendanceRate(minimumAttendanceRate);
        entity.setUpdatedBy(actor.id());
        entity.setUpdatedAt(Instant.now());
        policies.save(entity);
        audit.record(actor.id(), users.fullNameOf(actor.id()), actor.role(),
                "UPDATE_PROMOTION_POLICY", "academic", "academic_promotion_policy", entity.getId(),
                "Năm học=" + academicYearId + "; TB tối thiểu=" + minimumYearlyAverage
                        + "; hạnh kiểm tối thiểu=" + minimumConductGrade
                        + "; điểm môn tối thiểu=" + subjectMinimumScore
                        + "; số môn dưới ngưỡng tối đa=" + maximumSubjectsBelowMinimum
                        + "; chuyên cần tối thiểu=" + minimumAttendanceRate);
        return toPolicy(entity);
    }

    private List<YearReviewStudent> buildRows(SchoolClass schoolClass,
                                              List<Semester> semesters,
                                              List<YearSummaryPreviewResponse> semesterPreviews,
                                              Map<String, StudentYearlySummary> savedByStudent,
                                              PromotionPolicy policy) {
        Map<String, List<StudentSemesterData>> byStudent = new LinkedHashMap<>();
        for (int index = 0; index < semesterPreviews.size(); index++) {
            YearSummaryPreviewResponse preview = semesterPreviews.get(index);
            int sequence = semesters.get(index).getSequence();
            for (StudentSummaryRow student : preview.students()) {
                byStudent.computeIfAbsent(student.studentId(), ignored -> new ArrayList<>())
                        .add(new StudentSemesterData(preview, student, sequence));
            }
        }
        List<YearReviewStudent> rows = new ArrayList<>();
        for (Map.Entry<String, List<StudentSemesterData>> entry : byStudent.entrySet()) {
            List<StudentSemesterData> semesterRows = entry.getValue();
            StudentSummaryRow identity = semesterRows.get(0).student();
            boolean academicReady = semesterRows.size() == semesterPreviews.size()
                    && semesterRows.stream().allMatch(row -> row.student().ready());
            Double yearlyAverage = yearlyAverage(semesterRows);
            Double attendanceRate = combinedAttendanceRate(semesterRows);
            List<AnnualSubjectResult> annualSubjects = annualSubjects(semesterRows, policy);
            int subjectsBelowMinimum = (int) annualSubjects.stream()
                    .filter(AnnualSubjectResult::belowMinimum).count();
            StudentYearlySummary saved = savedByStudent.get(entry.getKey());
            String conductGrade = saved == null ? null : saved.getConductGrade();
            String suggested = suggestResult(schoolClass.getGradeLevel(), academicReady,
                    yearlyAverage, attendanceRate, conductGrade, subjectsBelowMinimum, policy);
            String result = saved == null ? suggested : normalizeLegacyResult(
                    schoolClass.getGradeLevel(), saved.getResult());
            rows.add(new YearReviewStudent(
                    identity.studentId(), identity.studentCode(), identity.studentName(),
                    semesterRows.stream().sorted(Comparator.comparingInt(StudentSemesterData::sequence))
                            .map(row -> new SemesterResult(
                                    row.preview().semesterId(), row.preview().semesterName(),
                                    row.preview().periodState(), row.student().overallAverage(),
                                    row.student().attendance().attendanceRate(), row.student().ready(),
                                    row.student().warnings())).toList(),
                    annualSubjects, yearlyAverage, attendanceRate, academicReady,
                    conductGrade, subjectsBelowMinimum, suggested, result,
                    saved == null ? "NOT_SAVED" : saved.getStatus(),
                    saved == null ? null : saved.getReason(),
                    saved == null ? null : users.fullNameOf(saved.getReviewedBy()),
                    saved == null ? null : saved.getReviewedAt(),
                    saved == null ? null : saved.getFinalizedAt()));
        }
        for (StudentYearlySummary saved : savedByStudent.values()) {
            if (byStudent.containsKey(saved.getStudentId())
                    || !"FINALIZED".equals(saved.getStatus())) {
                continue;
            }
            String result = normalizeLegacyResult(
                    schoolClass.getGradeLevel(), saved.getResult());
            rows.add(new YearReviewStudent(
                    saved.getStudentId(), saved.getStudentCode(), saved.getStudentName(),
                    List.of(), List.of(), saved.getYearlyAverage(), saved.getAttendanceRate(),
                    true, saved.getConductGrade(), 0, result, result,
                    saved.getStatus(), saved.getReason(),
                    users.fullNameOf(saved.getReviewedBy()), saved.getReviewedAt(),
                    saved.getFinalizedAt()));
        }
        return rows.stream().sorted(Comparator.comparing(YearReviewStudent::studentCode,
                Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER))).toList();
    }

    private List<AnnualSubjectResult> annualSubjects(List<StudentSemesterData> rows,
                                                     PromotionPolicy policy) {
        Set<String> subjectIds = new LinkedHashSet<>();
        Map<String, String> subjectNames = new LinkedHashMap<>();
        for (StudentSemesterData row : rows) {
            for (SubjectSummary subject : row.student().subjects()) {
                subjectIds.add(subject.subjectId());
                subjectNames.putIfAbsent(subject.subjectId(), subject.subjectName());
            }
        }
        List<AnnualSubjectResult> results = new ArrayList<>();
        for (String subjectId : subjectIds) {
            Double first = subjectAverage(rows, 1, subjectId);
            Double second = subjectAverage(rows, 2, subjectId);
            Double annual = weightedYearAverage(first, second);
            results.add(new AnnualSubjectResult(subjectId, subjectNames.get(subjectId),
                    first, second, annual,
                    annual != null && annual < policy.subjectMinimumScore()));
        }
        return results;
    }

    private Double subjectAverage(List<StudentSemesterData> rows, int sequence, String subjectId) {
        return rows.stream().filter(row -> row.sequence() == sequence)
                .flatMap(row -> row.student().subjects().stream())
                .filter(subject -> Objects.equals(subjectId, subject.subjectId()))
                .findFirst().map(SubjectSummary::average).orElse(null);
    }

    private List<String> finalizeBlockers(boolean yearClosed, SchoolClass schoolClass,
                                          List<YearReviewStudent> rows) {
        List<String> blockers = new ArrayList<>();
        if (!yearClosed) blockers.add("Năm học chưa kết thúc hoặc chưa được đóng");
        if (rows.isEmpty()) blockers.add("Lớp chưa có học sinh");
        long incompleteData = rows.stream().filter(row -> !row.academicReady()).count();
        if (incompleteData > 0) {
            blockers.add(incompleteData + " học sinh chưa đủ điểm/chuyên cần");
        }
        long missingConduct = rows.stream()
                .filter(row -> row.conductGrade() == null).count();
        if (missingConduct > 0) {
            blockers.add(missingConduct + " học sinh chưa có hạnh kiểm");
        }
        long unsaved = rows.stream()
                .filter(row -> "NOT_SAVED".equals(row.decisionStatus())).count();
        if (unsaved > 0) {
            blockers.add(unsaved + " học sinh chưa được lưu kết quả xét");
        }
        long undecided = rows.stream().filter(row -> Set.of("INCOMPLETE", "PENDING_REVIEW")
                .contains(row.result())).count();
        if (undecided > 0) {
            blockers.add(undecided + " học sinh chưa có kết quả cuối cùng");
        }
        long invalidForGrade = rows.stream()
                .filter(row -> !validResultForGradeLevel(schoolClass.getGradeLevel(), row.result()))
                .count();
        if (invalidForGrade > 0) {
            blockers.add(invalidForGrade + " học sinh có kết quả không phù hợp với khối lớp");
        }
        return blockers;
    }

    private YearReviewMetrics metrics(List<YearReviewStudent> rows) {
        return new YearReviewMetrics(rows.size(),
                (int) rows.stream().filter(YearReviewStudent::academicReady).count(),
                count(rows, "PROMOTED"), count(rows, "RETAINED"),
                count(rows, "ELIGIBLE_FOR_GRADUATION"), count(rows, "INCOMPLETE"),
                (int) rows.stream().filter(row -> row.conductGrade() != null).count(),
                (int) rows.stream().filter(row -> !"NOT_SAVED".equals(row.decisionStatus())).count());
    }

    private int count(List<YearReviewStudent> rows, String result) {
        return (int) rows.stream().filter(row -> result.equals(row.result())).count();
    }

    private String suggestResult(String gradeLevel, boolean ready, Double average,
                                 Double attendanceRate, String conductGrade,
                                 int subjectsBelowMinimum, PromotionPolicy policy) {
        if (!ready || average == null || conductGrade == null) return "INCOMPLETE";
        boolean retained = average < policy.minimumYearlyAverage()
                || CONDUCT_RANK.get(conductGrade) < CONDUCT_RANK.get(policy.minimumConductGrade())
                || subjectsBelowMinimum > policy.maximumSubjectsBelowMinimum()
                || (policy.minimumAttendanceRate() != null
                && (attendanceRate == null || attendanceRate < policy.minimumAttendanceRate()));
        if (retained) return "RETAINED";
        return "K12".equalsIgnoreCase(gradeLevel)
                ? "ELIGIBLE_FOR_GRADUATION" : "PROMOTED";
    }

    private Double combinedAttendanceRate(List<StudentSemesterData> rows) {
        int present = 0;
        int late = 0;
        int excused = 0;
        int unexcused = 0;
        for (StudentSemesterData row : rows) {
            present += row.student().attendance().present();
            late += row.student().attendance().late();
            excused += row.student().attendance().absentExcused();
            unexcused += row.student().attendance().absentUnexcused();
        }
        int total = present + late + excused + unexcused;
        return total == 0 ? null : round((present + late * 0.5) * 100.0 / total);
    }

    private Double yearlyAverage(List<StudentSemesterData> rows) {
        Double first = rows.stream().filter(row -> row.sequence() == 1)
                .findFirst().map(row -> row.student().overallAverage()).orElse(null);
        Double second = rows.stream().filter(row -> row.sequence() == 2)
                .findFirst().map(row -> row.student().overallAverage()).orElse(null);
        return weightedYearAverage(first, second);
    }

    private Double weightedYearAverage(Double first, Double second) {
        if (first == null || second == null) return null;
        return round((first + second * 2.0) / 3.0);
    }

    private PromotionPolicy policy(String academicYearId) {
        return policies.findByAcademicYearId(academicYearId)
                .map(this::toPolicy)
                .orElse(new PromotionPolicy(academicYearId,
                        5.0, "PASS", 5.0, 0, null));
    }

    private PromotionPolicy toPolicy(AcademicPromotionPolicy entity) {
        return new PromotionPolicy(entity.getAcademicYearId(),
                valueOr(entity.getMinimumYearlyAverage(), 5.0),
                entity.getMinimumConductGrade() == null ? "PASS" : entity.getMinimumConductGrade(),
                valueOr(entity.getSubjectMinimumScore(), 5.0),
                entity.getMaximumSubjectsBelowMinimum() == null
                        ? 0 : entity.getMaximumSubjectsBelowMinimum(),
                entity.getMinimumAttendanceRate());
    }

    private AcademicYear requireYear(String academicYearId) {
        String id = require(academicYearId, "academicYearId");
        return structure.listYears().stream().filter(year -> id.equals(year.getId())).findFirst()
                .orElseThrow(() -> ApiException.notFound("Năm học"));
    }

    private String normalizeResult(String result) {
        String normalized = require(result, "result").toUpperCase();
        if ("GRADUATED".equals(normalized)) normalized = "ELIGIBLE_FOR_GRADUATION";
        if (!RESULTS.contains(normalized)) {
            throw ApiException.badRequest("Kết quả năm học không hợp lệ");
        }
        return normalized;
    }

    private String normalizeLegacyResult(String gradeLevel, String result) {
        if ("K12".equalsIgnoreCase(gradeLevel) && "GRADUATED".equalsIgnoreCase(result)) {
            return "ELIGIBLE_FOR_GRADUATION";
        }
        return result;
    }

    private String normalizeConduct(String conductGrade) {
        String normalized = require(conductGrade, "hạnh kiểm").toUpperCase();
        if (!CONDUCT_GRADES.contains(normalized)) {
            throw ApiException.badRequest("Hạnh kiểm không hợp lệ");
        }
        return normalized;
    }

    private void validateResultForGradeLevel(String gradeLevel, String result) {
        if (!validResultForGradeLevel(gradeLevel, result)) {
            throw ApiException.badRequest("Kết quả không phù hợp với khối lớp");
        }
    }

    private boolean validResultForGradeLevel(String gradeLevel, String result) {
        if (Set.of("INCOMPLETE", "PENDING_REVIEW", "RETAINED").contains(result)) return true;
        if ("K12".equalsIgnoreCase(gradeLevel)) {
            return "ELIGIBLE_FOR_GRADUATION".equals(result);
        }
        return "PROMOTED".equals(result);
    }

    private boolean requiresReason(String result) {
        return "RETAINED".equals(result) || "INCOMPLETE".equals(result);
    }

    private double score(Double value, double fallback, String field) {
        double result = value == null ? fallback : value;
        if (result < 0 || result > 10) {
            throw ApiException.badRequest(field + " phải từ 0 đến 10");
        }
        return round(result);
    }

    private String require(String value, String field) {
        if (value == null || value.isBlank()) throw ApiException.badRequest("Thiếu " + field);
        return value.trim();
    }

    private String display(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private double valueOr(Double value, double fallback) {
        return value == null ? fallback : value;
    }

    private double round(double value) {
        return Math.round(value * 10.0) / 10.0;
    }

    private record StudentSemesterData(
            YearSummaryPreviewResponse preview,
            StudentSummaryRow student,
            int sequence) {}
}
