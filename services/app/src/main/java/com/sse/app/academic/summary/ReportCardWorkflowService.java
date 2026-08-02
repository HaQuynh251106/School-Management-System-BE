package com.sse.app.academic.summary;

import com.sse.app.academic.grade.*;
import com.sse.app.academic.structure.*;
import com.sse.app.academic.timetable.TeachingAssignmentService;
import com.sse.app.common.ApiException;
import com.sse.app.common.Ids;
import com.sse.app.common.PageResponse;
import com.sse.app.identity.UserDto;
import com.sse.app.identity.UserService;
import com.sse.app.notification.NotificationService;
import com.sse.app.security.CurrentUser;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

import static com.sse.app.academic.summary.ReportCardDtos.*;

@Service
@RequiredArgsConstructor
public class ReportCardWorkflowService {
    public static final String DRAFT = "DRAFT";
    public static final String SUBMITTED = "HOMEROOM_SUBMITTED";
    public static final String APPROVED = "APPROVED";
    public static final String LOCKED = "LOCKED";
    public static final String PUBLISHED = "PUBLISHED";

    private final JdbcTemplate jdbc;
    private final YearEndService yearEnd;
    private final StructureService structure;
    private final TeachingAssignmentService assignments;
    private final GradeService grades;
    private final GradeCalculationService calculations;
    private final GradebookCompletionService completions;
    private final UserService users;
    private final NotificationService notifications;

    @Transactional
    public List<ReportCardListItem> list(String academicYearId, String classId, String gradeLevel,
                                        String status, String query, CurrentUser actor) {
        List<StudentYearlySummary> summaries;
        if (actor.isTeacher()) summaries = yearEnd.homeroomPreview(academicYearId, actor.id());
        else if (actor.canManageAcademics()) summaries = yearEnd.preview(academicYearId);
        else throw ApiException.forbidden("Chỉ GVCN và Giáo vụ được xem danh sách học bạ");
        String normalized = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        List<ReportCardListItem> result = new ArrayList<>();
        for (StudentYearlySummary summary : summaries) {
            SchoolClass schoolClass = structure.getClass(summary.getClassId());
            if (classId != null && !classId.isBlank() && !classId.equals(summary.getClassId())) continue;
            if (gradeLevel != null && !gradeLevel.isBlank() && !gradeLevel.equalsIgnoreCase(schoolClass.getGradeLevel())) continue;
            UserDto student = users.dtoById(summary.getStudentId());
            if (!normalized.isBlank() && !(safe(student.fullName()) + " " + safe(student.studentCode()) + " " + safe(schoolClass.getCode()))
                    .toLowerCase(Locale.ROOT).contains(normalized)) continue;
            ReportCardListItem item = listItem(summary, schoolClass, student);
            if (status != null && !status.isBlank() && !status.equals(item.status())) continue;
            result.add(item);
        }
        return result.stream().sorted(Comparator.comparing(ReportCardListItem::classCode)
                .thenComparing(ReportCardListItem::studentName)).toList();
    }

    /** Dashboard theo phạm vi; chỉ trả số tổng hợp và không trả hồ sơ học sinh. */
    @Transactional
    public ReportCardScopeOverview overview(String academicYearId, String cohortId, CurrentUser actor) {
        List<ReportCardClassSummary> classes = classSummaries(academicYearId, cohortId, actor);
        long students = classes.stream().mapToLong(ReportCardClassSummary::studentCount).sum();
        long draft = classes.stream().mapToLong(ReportCardClassSummary::draftCount).sum();
        long submitted = classes.stream().mapToLong(ReportCardClassSummary::submittedCount).sum();
        long approved = classes.stream().mapToLong(ReportCardClassSummary::approvedCount).sum();
        long locked = classes.stream().mapToLong(ReportCardClassSummary::lockedCount).sum();
        long published = classes.stream().mapToLong(ReportCardClassSummary::publishedCount).sum();
        long incomplete = classes.stream().mapToLong(ReportCardClassSummary::incompleteCount).sum();
        return new ReportCardScopeOverview(academicYearId, clean(cohortId), classes.size(), students,
                draft, submitted, approved, locked, published, incomplete,
                percent(students - incomplete, students), percent(published, students));
    }

    /** Danh sách lớp là tầng điều hướng đầu tiên của học bạ. */
    @Transactional
    public List<ReportCardClassSummary> classSummaries(String academicYearId, String cohortId, CurrentUser actor) {
        return accessibleClasses(academicYearId, cohortId, actor).stream().map(schoolClass -> {
            ClassSnapshot snapshot = classSnapshot(academicYearId, schoolClass.getId());
            long total = snapshot.total();
            return new ReportCardClassSummary(schoolClass.getId(), schoolClass.getCode(), schoolClass.getName(),
                    schoolClass.getGradeLevel(), schoolClass.getCohortId(), schoolClass.getHomeroomTeacherId(),
                    schoolClass.getHomeroomTeacherName(), total, snapshot.draft(), snapshot.submitted(),
                    snapshot.approved(), snapshot.locked(), snapshot.published(), snapshot.incomplete(),
                    percent(total - snapshot.incomplete(), total), percent(snapshot.published(), total));
        }).sorted(Comparator.comparing(ReportCardClassSummary::classCode)).toList();
    }

    /** Danh sách học sinh phân trang trong đúng một lớp; không trả học sinh của lớp khác. */
    @Transactional
    public PageResponse<ReportCardListItem> studentPage(String academicYearId, String classId, String status,
                                                        String query, int page, int size, CurrentUser actor) {
        SchoolClass schoolClass = accessibleClasses(academicYearId, null, actor).stream()
                .filter(item -> item.getId().equals(classId)).findFirst()
                .orElseThrow(() -> ApiException.forbidden("Bạn không có quyền xem học bạ của lớp này"));
        String normalized = safe(query).trim().toLowerCase(Locale.ROOT);
        int subjectCount = assignedSubjectCount(academicYearId, classId);
        // Chỉ tính/khởi tạo snapshot cho đúng lớp đang mở. Dùng stored-only ở đây làm
        // lớp năm hiện hành hiển thị 0 học sinh cho tới khi một luồng khác vô tình tạo summary.
        List<ReportCardListItem> filtered = yearEnd.classPreview(academicYearId, classId).stream()
                .map(summary -> listItem(summary, schoolClass, users.dtoById(summary.getStudentId()), subjectCount))
                .filter(item -> status == null || status.isBlank() || status.equals(item.status()))
                .filter(item -> normalized.isBlank() || (safe(item.studentName()) + " " + safe(item.studentCode()))
                        .toLowerCase(Locale.ROOT).contains(normalized))
                .sorted(Comparator.comparing(ReportCardListItem::studentName)).toList();
        int safeSize = Math.max(5, Math.min(size, 50));
        int totalPages = filtered.isEmpty() ? 0 : (int) Math.ceil((double) filtered.size() / safeSize);
        int safePage = Math.max(0, Math.min(page, Math.max(0, totalPages - 1)));
        int from = Math.min(safePage * safeSize, filtered.size());
        int to = Math.min(from + safeSize, filtered.size());
        Map<String, Long> summary = new LinkedHashMap<>();
        for (String workflowStatus : List.of(DRAFT, SUBMITTED, APPROVED, LOCKED, PUBLISHED)) {
            summary.put(workflowStatus, filtered.stream().filter(item -> workflowStatus.equals(item.status())).count());
        }
        summary.put("INCOMPLETE", filtered.stream().filter(item -> item.missingRequirements() != null).count());
        return new PageResponse<>(filtered.subList(from, to), safePage, safeSize, filtered.size(), totalPages,
                safePage == 0, totalPages == 0 || safePage >= totalPages - 1, summary);
    }

    @Transactional
    public ReportCardView view(String academicYearId, String studentId, CurrentUser actor) {
        StudentYearlySummary summary = yearEnd.studentSummary(academicYearId, studentId);
        SchoolClass schoolClass = structure.getClass(summary.getClassId());
        assertViewAccess(studentId, schoolClass, actor);
        CardRow card = ensure(summary, schoolClass);
        if (summary.getFinalizedAt() != null && DRAFT.equals(card.status())
                && audits(card.id()).stream().anyMatch(item -> "OFFICIAL_REVISION_OPENED".equals(item.action()))) {
            summary = yearEnd.refreshOfficialRevision(academicYearId, studentId);
        }
        if ((actor.isStudent() || actor.isParent()) && !PUBLISHED.equals(card.status())) {
            throw ApiException.notFound("Học bạ chưa được nhà trường phát hành");
        }
        UserDto student = users.dtoById(studentId);
        UserDto homeroom = schoolClass.getHomeroomTeacherId() == null ? null : users.dtoById(schoolClass.getHomeroomTeacherId());
        List<SubjectResult> subjectResults = subjectResults(summary, schoolClass);
        return new ReportCardView(card.id(), academicYearId, structure.getYear(academicYearId).getCode(), studentId,
                student.studentCode(), student.fullName(), schoolClass.getId(), schoolClass.getCode(), card.status(),
                schoolClass.getHomeroomTeacherId(), homeroom == null ? null : homeroom.fullName(), card.comment(),
                summary.getSemesterOneAverage(), summary.getSemesterTwoAverage(), summary.getAverageScore(),
                summary.getConductGrade(), summary.getPromotionStatus(), cardMissing(summary, schoolClass, card),
                subjectResults.size(), subjectResults, attendance(academicYearId, studentId), card.verificationCode(),
                instant(card.submittedAt()), instant(card.approvedAt()), instant(card.lockedAt()), instant(card.publishedAt()),
                actor.isTeacher() && actor.id().equals(schoolClass.getHomeroomTeacherId()) && !LOCKED.equals(card.status())
                        && !PUBLISHED.equals(card.status()), audits(card.id()));
    }

    @Transactional
    public ReportCardView updateHomeroom(String academicYearId, String studentId,
                                         HomeroomUpdateRequest request, CurrentUser actor) {
        if (!actor.isTeacher()) throw ApiException.forbidden("Chỉ giáo viên chủ nhiệm được hoàn thiện học bạ");
        StudentYearlySummary summary = yearEnd.studentSummary(academicYearId, studentId);
        SchoolClass schoolClass = structure.getClass(summary.getClassId());
        assertHomeroom(schoolClass, actor.id());
        CardRow card = ensure(summary, schoolClass);
        if (Set.of(LOCKED, PUBLISHED).contains(card.status())) throw ApiException.conflict("Học bạ đã khóa, không thể chỉnh sửa");
        if (summary.getFinalizedAt() == null) yearEnd.setConduct(academicYearId, studentId, request.conductGrade(), actor);
        else yearEnd.setConductForReportCardRevision(academicYearId, studentId, request.conductGrade(), actor);
        jdbc.update("update report_cards set homeroom_comment=?,status=?,updated_at=?,version=version+1 where id=?",
                request.homeroomComment().trim(), DRAFT, Timestamp.from(Instant.now()), card.id());
        audit(card.id(), "HOMEROOM_UPDATED", card.status(), DRAFT, "Cập nhật hạnh kiểm và nhận xét", actor.id());
        return view(academicYearId, studentId, actor);
    }

    @Transactional
    public ReportCardView submit(String academicYearId, String studentId, TransitionRequest request, CurrentUser actor) {
        if (!actor.isTeacher()) throw ApiException.forbidden("Chỉ GVCN được gửi duyệt học bạ");
        StudentYearlySummary summary = yearEnd.studentSummary(academicYearId, studentId);
        SchoolClass schoolClass = structure.getClass(summary.getClassId());
        assertHomeroom(schoolClass, actor.id());
        CardRow card = ensure(summary, schoolClass);
        if (!DRAFT.equals(card.status())) throw ApiException.conflict("Chỉ học bạ ở trạng thái Nháp mới được gửi duyệt");
        if (summary.getFinalizedAt() != null
                && audits(card.id()).stream().anyMatch(item -> "OFFICIAL_REVISION_OPENED".equals(item.action()))) {
            summary = yearEnd.refreshOfficialRevision(academicYearId, studentId);
        }
        String missing = readinessMissing(summary, schoolClass);
        if (missing != null) throw ApiException.conflict("Chưa thể gửi duyệt: " + missing);
        if (card.comment() == null || card.comment().isBlank()) throw ApiException.conflict("GVCN chưa nhập nhận xét học bạ");
        transition(card, SUBMITTED, "SUBMITTED", request == null ? null : request.note(), actor.id());
        return view(academicYearId, studentId, actor);
    }

    @Transactional
    public ReportCardView approve(String academicYearId, String studentId, TransitionRequest request, CurrentUser actor) {
        assertAcademicStaff(actor);
        CardContext context = context(academicYearId, studentId);
        requireStatus(context.card(), SUBMITTED, "Chỉ học bạ GVCN đã gửi mới được duyệt");
        transition(context.card(), APPROVED, "APPROVED", request == null ? null : request.note(), actor.id());
        return view(academicYearId, studentId, actor);
    }

    @Transactional
    public ReportCardView lock(String academicYearId, String studentId, TransitionRequest request, CurrentUser actor) {
        assertAcademicStaff(actor);
        CardContext context = context(academicYearId, studentId);
        requireStatus(context.card(), APPROVED, "Học bạ phải được duyệt trước khi khóa");
        assertAllGradebooksCompleted(academicYearId, context.schoolClass());
        transition(context.card(), LOCKED, "LOCKED", request == null ? null : request.note(), actor.id());
        return view(academicYearId, studentId, actor);
    }

    @Transactional
    public ReportCardView publish(String academicYearId, String studentId, TransitionRequest request, CurrentUser actor) {
        assertAcademicStaff(actor);
        CardContext context = context(academicYearId, studentId);
        requireStatus(context.card(), LOCKED, "Học bạ phải được khóa trước khi phát hành");
        if (context.summary().getFinalizedAt() == null) {
            throw ApiException.conflict("Cần hoàn tất tổng kết năm học trước khi phát hành học bạ chính thức");
        }
        transition(context.card(), PUBLISHED, "PUBLISHED", request == null ? null : request.note(), actor.id());
        String body = "Học bạ năm học " + structure.getYear(academicYearId).getCode()
                + " đã được nhà trường phát hành. Bạn có thể xem và tải PDF trên hệ thống.";
        notifications.notifyUser(studentId, "REPORT_CARD_PUBLISHED", "Học bạ đã được phát hành", body,
                "REPORT_CARD", context.card().id());
        notifications.notifyParentsOfStudent(studentId, "REPORT_CARD_PUBLISHED", "Học bạ của học sinh đã được phát hành",
                body, "REPORT_CARD", context.card().id());
        return view(academicYearId, studentId, actor);
    }

    @Transactional
    public ReportCardView reopen(String academicYearId, String studentId, ReopenRequest request, CurrentUser actor) {
        assertAcademicStaff(actor);
        CardContext context = context(academicYearId, studentId);
        if (DRAFT.equals(context.card().status())) throw ApiException.conflict("Học bạ đang ở trạng thái Nháp");
        boolean wasPublished = PUBLISHED.equals(context.card().status());
        unlockGradebooks(academicYearId, context.schoolClass(), request.reason(), actor.id(), wasPublished);
        if (wasPublished) {
            long revisions = audits(context.card().id()).stream().filter(item -> "OFFICIAL_REVISION_OPENED".equals(item.action())).count() + 1;
            jdbc.update("update report_cards set verification_code=? where id=?",
                    context.card().verificationCode().replaceFirst("-R\\d+$", "") + "-R" + revisions, context.card().id());
        }
        transition(context.card(), DRAFT, wasPublished ? "OFFICIAL_REVISION_OPENED" : "REOPENED", request.reason(), actor.id());
        if (wasPublished) {
            String body = "Học bạ năm học " + structure.getYear(academicYearId).getCode()
                    + " đang được nhà trường điều chỉnh với lý do: " + request.reason() + ". Bản mới sẽ được thông báo sau khi phát hành lại.";
            notifications.notifyUser(studentId, "REPORT_CARD_REVISION", "Học bạ đang được điều chỉnh", body,
                    "REPORT_CARD", context.card().id());
            notifications.notifyParentsOfStudent(studentId, "REPORT_CARD_REVISION", "Học bạ của học sinh đang được điều chỉnh",
                    body, "REPORT_CARD", context.card().id());
        }
        return view(academicYearId, studentId, actor);
    }

    public List<ReportCardAudit> audits(String reportCardId) {
        return jdbc.query("select a.id,a.action,a.from_status,a.to_status,a.note,a.actor_id,a.created_at,u.full_name from report_card_audits a left join users u on u.id=a.actor_id where a.report_card_id=? order by a.created_at desc",
                (rs, rowNum) -> new ReportCardAudit(rs.getString("id"), rs.getString("action"), rs.getString("from_status"),
                        rs.getString("to_status"), rs.getString("note"), rs.getString("actor_id"), rs.getString("full_name"),
                        rs.getTimestamp("created_at").toInstant().toString()), reportCardId);
    }

    private CardContext context(String academicYearId, String studentId) {
        StudentYearlySummary summary = yearEnd.studentSummary(academicYearId, studentId);
        SchoolClass schoolClass = structure.getClass(summary.getClassId());
        return new CardContext(summary, schoolClass, ensure(summary, schoolClass));
    }

    private CardRow ensure(StudentYearlySummary summary, SchoolClass schoolClass) {
        List<CardRow> rows = cardRows(summary.getAcademicYearId(), summary.getStudentId());
        if (!rows.isEmpty()) return rows.get(0);
        Instant now = Instant.now();
        boolean finalized = summary.getFinalizedAt() != null;
        String status = finalized ? PUBLISHED : DRAFT;
        String id = Ids.gen("report-card");
        String code = ("HB-" + structure.getYear(summary.getAcademicYearId()).getCode() + "-" + summary.getStudentId())
                .replaceAll("[^A-Za-z0-9-]", "").toUpperCase(Locale.ROOT);
        jdbc.update("insert into report_cards(id,academic_year_id,student_id,class_id,homeroom_teacher_id,status,verification_code,published_at,published_by,created_at,updated_at,version) values (?,?,?,?,?,?,?,?,?,?,?,0)",
                id, summary.getAcademicYearId(), summary.getStudentId(), schoolClass.getId(), schoolClass.getHomeroomTeacherId(), status,
                code, finalized ? Timestamp.from(summary.getFinalizedAt()) : null, finalized ? summary.getFinalizedBy() : null,
                Timestamp.from(now), Timestamp.from(now));
        audit(id, finalized ? "MIGRATED_AS_PUBLISHED" : "CREATED", null, status,
                finalized ? "Khởi tạo từ dữ liệu tổng kết năm đã chốt" : null, "SYSTEM");
        return cardRows(summary.getAcademicYearId(), summary.getStudentId()).get(0);
    }

    private List<CardRow> cardRows(String yearId, String studentId) {
        return jdbc.query("select * from report_cards where academic_year_id=? and student_id=?",
                (rs, rowNum) -> new CardRow(rs.getString("id"), rs.getString("status"), rs.getString("homeroom_comment"),
                        rs.getString("verification_code"), timestamp(rs.getTimestamp("submitted_at")), timestamp(rs.getTimestamp("approved_at")),
                        timestamp(rs.getTimestamp("locked_at")), timestamp(rs.getTimestamp("published_at")), timestamp(rs.getTimestamp("updated_at"))),
                yearId, studentId);
    }

    private List<SubjectResult> subjectResults(StudentYearlySummary summary, SchoolClass schoolClass) {
        List<Semester> semesters = structure.listSemesters(summary.getAcademicYearId());
        Semester first = semester(semesters, 1); Semester second = semester(semesters, 2);
        LinkedHashMap<String, String> subjects = new LinkedHashMap<>();
        if (first != null) assignments.assignmentsOfClass(schoolClass.getId(), first.getId()).forEach(item -> subjects.put(item.getSubjectId(), item.getSubjectName()));
        if (second != null) assignments.assignmentsOfClass(schoolClass.getId(), second.getId()).forEach(item -> subjects.put(item.getSubjectId(), item.getSubjectName()));
        List<ExamCategory> categories = grades.listCategories();
        List<Grade> all = grades.list(summary.getStudentId(), null, null, null, null);
        return subjects.entrySet().stream().sorted(Map.Entry.comparingByValue()).map(entry -> {
            SubjectSemester one = subjectSemester(all, categories, first, entry.getKey());
            SubjectSemester two = subjectSemester(all, categories, second, entry.getKey());
            Double annual = one.average() == null || two.average() == null ? null : round((one.average() + 2 * two.average()) / 3d);
            return new SubjectResult(entry.getKey(), entry.getValue(), one.average(), two.average(), annual, one.complete() && two.complete());
        }).toList();
    }

    private SubjectSemester subjectSemester(List<Grade> all, List<ExamCategory> categories, Semester semester, String subjectId) {
        if (semester == null) return new SubjectSemester(null, false);
        List<Grade> entries = all.stream().filter(item -> semester.getId().equals(item.getSemesterId()) && subjectId.equals(item.getSubjectId())).toList();
        boolean complete = categories.stream().allMatch(category -> {
            Set<Integer> indexes = entries.stream().filter(item -> category.getCode().equals(item.getCategory()))
                    .filter(item -> item.getScore() != null).map(item -> item.getAssessmentIndex() == null ? 1 : item.getAssessmentIndex()).collect(Collectors.toSet());
            return java.util.stream.IntStream.rangeClosed(1, Math.max(1, category.getRequiredCount())).allMatch(indexes::contains);
        });
        return new SubjectSemester(complete ? calculations.subjectAverage(entries, categories) : null, complete);
    }

    private String readinessMissing(StudentYearlySummary summary, SchoolClass schoolClass) {
        List<SubjectResult> subjects = subjectResults(summary, schoolClass);
        List<String> missing = new ArrayList<>();
        if (subjects.size() != 12) missing.add("Cần đủ 12 môn, hiện có " + subjects.size());
        subjects.stream().filter(item -> !item.complete()).forEach(item -> missing.add(item.subjectName() + " chưa đủ điểm hai học kỳ"));
        if (summary.getMissingRequirements() != null) missing.add(summary.getMissingRequirements());
        if (summary.getConductGrade() == null) missing.add("Chưa có hạnh kiểm");
        return missing.isEmpty() ? null : String.join("; ", missing.stream().distinct().limit(16).toList());
    }

    private String cardMissing(StudentYearlySummary summary, SchoolClass schoolClass, CardRow card) {
        List<String> missing = new ArrayList<>();
        String academic = readinessMissing(summary, schoolClass);
        if (academic != null) missing.add(academic);
        if (card.comment() == null || card.comment().isBlank()) missing.add("GVCN chưa nhập nhận xét");
        return missing.isEmpty() ? null : String.join("; ", missing);
    }

    private ReportCardListItem listItem(StudentYearlySummary summary, SchoolClass schoolClass, UserDto student) {
        CardRow card = ensure(summary, schoolClass);
        return new ReportCardListItem(card.id(), student.id(), student.studentCode(), student.fullName(),
                schoolClass.getId(), schoolClass.getCode(), card.status(), subjectResults(summary, schoolClass).size(),
                summary.getAverageScore(), summary.getConductGrade(), summary.getPromotionStatus(),
                cardMissing(summary, schoolClass, card), card.updatedAt() == null ? null : card.updatedAt().toString());
    }

    /** Danh sách chỉ dùng snapshot tổng kết; chi tiết 12 môn chỉ được tính khi mở một học sinh. */
    private ReportCardListItem listItem(StudentYearlySummary summary, SchoolClass schoolClass,
                                        UserDto student, int subjectCount) {
        CardRow card = ensure(summary, schoolClass);
        List<String> missing = new ArrayList<>();
        if (subjectCount != 12) missing.add("Cáº§n Ä‘á»§ 12 mÃ´n, hiá»‡n cÃ³ " + subjectCount);
        if (summary.getMissingRequirements() != null) missing.add(summary.getMissingRequirements());
        if (summary.getConductGrade() == null) missing.add("Chưa có hạnh kiểm");
        if (card.comment() == null || card.comment().isBlank()) missing.add("GVCN chưa nhập nhận xét");
        return new ReportCardListItem(card.id(), student.id(), student.studentCode(), student.fullName(),
                schoolClass.getId(), schoolClass.getCode(), card.status(), subjectCount,
                summary.getAverageScore(), summary.getConductGrade(), summary.getPromotionStatus(),
                missing.isEmpty() ? null : String.join("; ", missing.stream().distinct().limit(16).toList()),
                card.updatedAt() == null ? null : card.updatedAt().toString());
    }

    private int assignedSubjectCount(String academicYearId, String classId) {
        LinkedHashSet<String> subjectIds = new LinkedHashSet<>();
        for (Semester semester : structure.listSemesters(academicYearId)) {
            assignments.assignmentsOfClass(classId, semester.getId())
                    .forEach(item -> subjectIds.add(item.getSubjectId()));
        }
        return subjectIds.size();
    }

    /**
     * Truy vấn tổng hợp nhẹ thay cho việc đọc điểm của từng học sinh.
     * Enrollment được dùng để lịch sử năm cũ vẫn đúng sau khi học sinh chuyển lớp.
     */
    private ClassSnapshot classSnapshot(String academicYearId, String classId) {
        String sql = """
                with students_in_class as (
                    select student_id from class_enrollments
                    where academic_year_id=? and class_id=? and status <> 'ROLLED_BACK'
                    union
                    select student_id from student_yearly_summaries
                    where academic_year_id=? and class_id=?
                )
                select count(*) as total,
                       count(*) filter (where coalesce(rc.status, 'DRAFT')='DRAFT') as draft,
                       count(*) filter (where rc.status=?) as submitted,
                       count(*) filter (where rc.status=?) as approved,
                       count(*) filter (where rc.status=?) as locked,
                       count(*) filter (where rc.status=?) as published,
                       count(*) filter (where ys.student_id is null
                           or ys.missing_requirements is not null
                           or ys.conduct_grade is null
                           or coalesce(ys.promotion_status, 'INCOMPLETE')='INCOMPLETE'
                           or rc.id is null
                           or nullif(trim(rc.homeroom_comment), '') is null) as incomplete
                from students_in_class sic
                left join student_yearly_summaries ys
                  on ys.academic_year_id=? and ys.class_id=? and ys.student_id=sic.student_id
                left join report_cards rc
                  on rc.academic_year_id=? and rc.class_id=? and rc.student_id=sic.student_id
                """;
        return jdbc.queryForObject(sql, (rs, rowNum) -> new ClassSnapshot(
                        rs.getLong("total"), rs.getLong("draft"), rs.getLong("submitted"),
                        rs.getLong("approved"), rs.getLong("locked"), rs.getLong("published"),
                        rs.getLong("incomplete")),
                academicYearId, classId, academicYearId, classId,
                SUBMITTED, APPROVED, LOCKED, PUBLISHED,
                academicYearId, classId, academicYearId, classId);
    }

    private List<SchoolClass> accessibleClasses(String academicYearId, String cohortId, CurrentUser actor) {
        if (!actor.canManageAcademics() && !actor.isTeacher()) {
            throw ApiException.forbidden("Chỉ Giáo vụ và GVCN được xem danh sách học bạ");
        }
        return structure.listClasses(academicYearId, null).stream()
                .filter(item -> cohortId == null || cohortId.isBlank() || cohortId.equals(item.getCohortId()))
                .filter(item -> !actor.isTeacher() || actor.id().equals(item.getHomeroomTeacherId()))
                .toList();
    }

    private double percent(long value, long total) {
        return total <= 0 ? 0d : Math.round(value * 1000d / total) / 10d;
    }

    private AttendanceSummary attendance(String academicYearId, String studentId) {
        AcademicYear year = structure.getYear(academicYearId);
        Map<String, Integer> counts = jdbc.query("select status,count(*) total from attendance_records where student_id=? and date between ? and ? group by status",
                rs -> { Map<String, Integer> map = new HashMap<>(); while (rs.next()) map.put(rs.getString("status"), rs.getInt("total")); return map; },
                studentId, year.getStartDate(), year.getEndDate());
        int present = counts.getOrDefault("PRESENT", 0), excused = counts.getOrDefault("ABSENT_EXCUSED", 0);
        int unexcused = counts.getOrDefault("ABSENT_UNEXCUSED", 0), late = counts.getOrDefault("LATE", 0);
        return new AttendanceSummary(present, excused, unexcused, late, present + excused + unexcused + late);
    }

    private void assertAllGradebooksCompleted(String yearId, SchoolClass schoolClass) {
        List<Semester> semesters = structure.listSemesters(yearId);
        LinkedHashSet<String> subjectIds = new LinkedHashSet<>();
        for (Semester semester : semesters) assignments.assignmentsOfClass(schoolClass.getId(), semester.getId()).forEach(item -> subjectIds.add(item.getSubjectId()));
        if (subjectIds.size() != 12) throw ApiException.conflict("Lớp phải có đúng 12 môn trước khi khóa học bạ; hiện có " + subjectIds.size());
        List<String> missing = new ArrayList<>();
        for (Semester semester : semesters) for (String subjectId : subjectIds) {
            boolean assigned = assignments.assignmentsOfClass(schoolClass.getId(), semester.getId()).stream().anyMatch(item -> subjectId.equals(item.getSubjectId()));
            if (!assigned || !completions.isCompleted(semester.getId(), schoolClass.getId(), subjectId)) {
                missing.add(semester.getCode() + " · " + structure.requireSubjectName(subjectId));
            }
        }
        if (!missing.isEmpty()) throw ApiException.conflict("Còn " + missing.size() + " sổ điểm môn chưa được giáo viên xác nhận hoàn tất: " + String.join(", ", missing.stream().limit(8).toList()));
    }

    private void unlockGradebooks(String yearId, SchoolClass schoolClass, String reason, String actorId,
                                  boolean officialRevision) {
        for (Semester semester : structure.listSemesters(yearId)) {
            for (var assignment : assignments.assignmentsOfClass(schoolClass.getId(), semester.getId())) {
                if (completions.isCompleted(semester.getId(), schoolClass.getId(), assignment.getSubjectId())) {
                    var request = new GradebookCompletionDtos.ReopenRequest(reason);
                    var staff = new CurrentUser(actorId, actorId, "ACADEMIC_STAFF");
                    if (officialRevision) completions.reopenForOfficialRevision(
                            semester.getId(), schoolClass.getId(), assignment.getSubjectId(), request, staff);
                    else completions.reopen(semester.getId(), schoolClass.getId(), assignment.getSubjectId(), request, staff);
                }
            }
        }
    }

    private void transition(CardRow card, String target, String action, String note, String actorId) {
        Instant now = Instant.now();
        String timeColumn = switch (target) { case SUBMITTED -> "submitted_at"; case APPROVED -> "approved_at"; case LOCKED -> "locked_at"; case PUBLISHED -> "published_at"; default -> null; };
        String byColumn = switch (target) { case SUBMITTED -> "submitted_by"; case APPROVED -> "approved_by"; case LOCKED -> "locked_by"; case PUBLISHED -> "published_by"; default -> null; };
        if (DRAFT.equals(target)) {
            jdbc.update("update report_cards set status=?,submitted_at=null,submitted_by=null,approved_at=null,approved_by=null,locked_at=null,locked_by=null,published_at=null,published_by=null,updated_at=?,version=version+1 where id=?",
                    target, Timestamp.from(now), card.id());
        } else {
            jdbc.update("update report_cards set status=?," + timeColumn + "=?," + byColumn + "=?,updated_at=?,version=version+1 where id=?",
                    target, Timestamp.from(now), actorId, Timestamp.from(now), card.id());
        }
        audit(card.id(), action, card.status(), target, note, actorId);
    }

    private void audit(String cardId, String action, String from, String to, String note, String actorId) {
        jdbc.update("insert into report_card_audits(id,report_card_id,action,from_status,to_status,note,actor_id,created_at) values (?,?,?,?,?,?,?,?)",
                Ids.gen("report-card-audit"), cardId, action, from, to, clean(note), actorId, Timestamp.from(Instant.now()));
    }

    private void assertViewAccess(String studentId, SchoolClass schoolClass, CurrentUser actor) {
        if (actor.canManageAcademics()) return;
        if (actor.isStudent() && actor.id().equals(studentId)) return;
        if (actor.isParent()) { yearEnd.assertParentOf(actor.id(), studentId); return; }
        if (actor.isTeacher() && actor.id().equals(schoolClass.getHomeroomTeacherId())) return;
        throw ApiException.forbidden("Không có quyền xem học bạ học sinh này");
    }
    private void assertHomeroom(SchoolClass schoolClass, String teacherId) { if (!teacherId.equals(schoolClass.getHomeroomTeacherId())) throw ApiException.forbidden("Chỉ GVCN của lớp được hoàn thiện học bạ"); }
    private void assertAcademicStaff(CurrentUser actor) { if (!actor.canManageAcademics()) throw ApiException.forbidden("Chỉ Giáo vụ được xử lý duyệt và phát hành học bạ"); }
    private void requireStatus(CardRow card, String status, String message) { if (!status.equals(card.status())) throw ApiException.conflict(message); }
    private Semester semester(List<Semester> list, int sequence) { return list.stream().filter(item -> item.getSequence() == sequence || ("HK" + sequence).equalsIgnoreCase(item.getCode())).findFirst().orElse(null); }
    private double round(double value) { return Math.round(value * 100d) / 100d; }
    private String clean(String value) { return value == null || value.isBlank() ? null : value.trim(); }
    private String safe(String value) { return value == null ? "" : value; }
    private String instant(Instant value) { return value == null ? null : value.toString(); }
    private Instant timestamp(Timestamp value) { return value == null ? null : value.toInstant(); }
    private record SubjectSemester(Double average, boolean complete) {}
    private record CardContext(StudentYearlySummary summary, SchoolClass schoolClass, CardRow card) {}
    private record ClassSnapshot(long total, long draft, long submitted, long approved,
                                 long locked, long published, long incomplete) {}
    private record CardRow(String id, String status, String comment, String verificationCode,
                           Instant submittedAt, Instant approvedAt, Instant lockedAt, Instant publishedAt, Instant updatedAt) {}
}
