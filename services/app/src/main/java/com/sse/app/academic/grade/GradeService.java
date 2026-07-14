package com.sse.app.academic.grade;

import com.sse.app.academic.structure.StructureService;
import com.sse.app.academic.timetable.TimetableService;
import com.sse.app.academic.grade.GradeDtos.*;
import com.sse.app.common.ApiException;
import com.sse.app.common.Ids;
import com.sse.app.identity.User;
import com.sse.app.identity.UserService;
import com.sse.app.notification.NotificationService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** B4: Quản lý điểm (nhập/sửa có log — flowchart 2.6) + A4: cấu hình loại điểm. */
@Service
public class GradeService {

    private final GradeRepository grades;
    private final GradeChangeLogRepository logs;
    private final ExamCategoryRepository categories;
    private final StructureService structure;
    private final TimetableService timetable;
    private final UserService users;
    private final NotificationService notifications;

    public GradeService(GradeRepository grades, GradeChangeLogRepository logs,
                        ExamCategoryRepository categories, StructureService structure,
                        TimetableService timetable, UserService users, NotificationService notifications) {
        this.grades = grades;
        this.logs = logs;
        this.categories = categories;
        this.structure = structure;
        this.timetable = timetable;
        this.users = users;
        this.notifications = notifications;
    }

    public Grade get(String id) {
        return grades.findById(id).orElseThrow(() -> ApiException.notFound("Điểm"));
    }

    public TeacherGradebookContext teacherGradebookContext(String teacherId, String classId, String semesterId) {
        User teacher = users.getById(teacherId);
        if (!"TEACHER".equals(teacher.getRole())) throw ApiException.forbidden("Tài khoản không phải giáo viên");
        structure.getClass(classId);
        structure.assertSemesterExists(semesterId);

        LinkedHashMap<String, String> assignedSubjects = new LinkedHashMap<>();
        timetable.list(classId, null, semesterId, null).stream()
                .filter(slot -> teacherId.equals(slot.getTeacherId()))
                .forEach(slot -> assignedSubjects.putIfAbsent(slot.getSubjectId(), slot.getSubjectName()));
        if (assignedSubjects.isEmpty()) {
            throw ApiException.forbidden("Giáo viên không được phân công dạy lớp này trong học kỳ đã chọn");
        }

        String mainSubject = clean(teacher.getMainSubject());
        String subjectId = assignedSubjects.entrySet().stream()
                .filter(entry -> mainSubject != null && (entry.getKey().equalsIgnoreCase(mainSubject)
                        || entry.getValue().equalsIgnoreCase(mainSubject)))
                .map(java.util.Map.Entry::getKey)
                .findFirst()
                .orElse(null);
        if (subjectId == null && assignedSubjects.size() == 1) subjectId = assignedSubjects.keySet().iterator().next();
        if (subjectId == null) {
            throw ApiException.badRequest("Chưa xác định được môn mặc định của giáo viên trong lớp này");
        }
        return new TeacherGradebookContext(classId, semesterId, subjectId, assignedSubjects.get(subjectId));
    }

    @Transactional
    public Grade create(CreateGradeRequest req, String actorId, String actorRole) {
        String categoryCode = normalizeCategory(req.category());
        int assessmentIndex = normalizeAssessmentIndex(req.assessmentIndex());
        ExamCategory category = requireCategory(categoryCode, assessmentIndex);
        User targetStudent = users.getById(req.studentId());
        String subjectId = resolveSubjectId(actorId, actorRole, targetStudent.getClassId(), req.semesterId(), req.subjectId());
        String subjectName = validateTarget(actorId, actorRole, req.studentId(), targetStudent.getClassId(), subjectId, req.semesterId());
        validateScore(req.score(), req.studentId());

        if (grades.findByStudentIdAndSubjectIdAndSemesterIdAndCategoryAndAssessmentIndex(
                req.studentId(), subjectId, req.semesterId(), categoryCode, assessmentIndex).isPresent()) {
            throw ApiException.conflict("Đầu điểm này đã tồn tại; hãy dùng chức năng sửa điểm");
        }

        Instant now = Instant.now();
        Grade grade = grades.saveAndFlush(Grade.builder()
                .id(Ids.gen("g")).studentId(req.studentId())
                .subjectId(subjectId).subjectName(subjectName)
                .semesterId(req.semesterId()).category(categoryCode).categoryName(category.getName())
                .assessmentIndex(assessmentIndex).score(req.score()).note(clean(req.note()))
                .recordedAt(now).createdAt(now).createdBy(actorId).updatedAt(now).updatedBy(actorId)
                .build());
        audit(grade, "CREATE", null, req.score(), null, grade.getNote(), actorId, "Thêm điểm");
        notifyGrade(grade, category, false);
        return grade;
    }

    @Transactional
    public Grade update(String id, UpdateGradeRequest req, String actorId, String actorRole) {
        Grade grade = get(id);
        validateTarget(actorId, actorRole, grade.getStudentId(), null, grade.getSubjectId(), grade.getSemesterId());
        validateScore(req.score(), grade.getStudentId());
        assertVersion(grade, req.expectedVersion());

        String note = clean(req.note());
        if (equalsScore(grade.getScore(), req.score()) && Objects.equals(grade.getNote(), note)) return grade;

        audit(grade, "UPDATE", grade.getScore(), req.score(), grade.getNote(), note,
                actorId, clean(req.reason()));
        grade.setScore(req.score());
        grade.setNote(note);
        grade.setRecordedAt(Instant.now());
        grade.setUpdatedAt(grade.getRecordedAt());
        grade.setUpdatedBy(actorId);
        Grade saved = grades.saveAndFlush(grade);
        notifyGrade(saved, requireCategory(saved.getCategory(), saved.getAssessmentIndex()), true);
        return saved;
    }

    public List<Grade> list(String studentId, String subjectId, String semesterId,
                            String category, Collection<String> studentIds) {
        List<Grade> base;
        if (studentId != null && semesterId != null) base = grades.findByStudentIdAndSemesterId(studentId, semesterId);
        else if (studentId != null) base = grades.findByStudentId(studentId);
        else if (subjectId != null && semesterId != null) base = grades.findBySubjectIdAndSemesterId(subjectId, semesterId);
        else base = grades.findAll();
        return base.stream()
                .filter(g -> subjectId == null || subjectId.equals(g.getSubjectId()))
                .filter(g -> semesterId == null || semesterId.equals(g.getSemesterId()))
                .filter(g -> category == null || category.equals(g.getCategory()))
                .filter(g -> studentIds == null || studentIds.contains(g.getStudentId()))
                .toList();
    }

    @Transactional
    public List<Grade> bulkUpsert(BulkGradeRequest req, String changedBy, String actorRole) {
        String categoryCode = normalizeCategory(req.category());
        int assessmentIndex = normalizeAssessmentIndex(req.assessmentIndex());
        ExamCategory examCategory = requireCategory(categoryCode, assessmentIndex);
        structure.assertSemesterExists(req.semesterId());
        String subjectId = resolveSubjectId(changedBy, actorRole, req.classId(), req.semesterId(), req.subjectId());
        String subjectName = structure.requireSubjectName(subjectId);

        Set<String> studentIds = new HashSet<>();
        for (Entry entry : req.entries()) {
            if (!studentIds.add(entry.studentId())) {
                throw ApiException.badRequest("Học sinh " + entry.studentId() + " bị lặp trong cùng lần lưu");
            }
        }

        List<Grade> result = new ArrayList<>();
        for (Entry e : req.entries()) {
            validateTarget(changedBy, actorRole, e.studentId(), req.classId(), subjectId, req.semesterId());
            validateScore(e.score(), e.studentId());
            Grade existing = grades.findByStudentIdAndSubjectIdAndSemesterIdAndCategoryAndAssessmentIndex(
                    e.studentId(), subjectId, req.semesterId(), categoryCode, assessmentIndex).orElse(null);

            if (existing == null) {
                Instant now = Instant.now();
                Grade g = grades.save(Grade.builder()
                        .id(Ids.gen("g")).studentId(e.studentId())
                        .subjectId(subjectId).subjectName(subjectName)
                        .semesterId(req.semesterId()).category(categoryCode).categoryName(examCategory.getName())
                        .assessmentIndex(assessmentIndex)
                        .score(e.score()).note(clean(e.note())).recordedAt(now)
                        .createdAt(now).createdBy(changedBy).updatedAt(now).updatedBy(changedBy).build());
                audit(g, "CREATE", null, e.score(), null, g.getNote(), changedBy, reason(req.reason(), "Lưu điểm hàng loạt"));
                result.add(g);
                notifyGrade(g, examCategory, false);
            } else {
                assertVersion(existing, e.expectedVersion());
                String note = clean(e.note());
                if (!equalsScore(existing.getScore(), e.score()) || !Objects.equals(existing.getNote(), note)) {
                    audit(existing, "UPDATE", existing.getScore(), e.score(), existing.getNote(), note,
                            changedBy, reason(req.reason(), "Cập nhật điểm hàng loạt"));
                    existing.setScore(e.score());
                    existing.setNote(note);
                    existing.setRecordedAt(Instant.now());
                    existing.setUpdatedAt(existing.getRecordedAt());
                    existing.setUpdatedBy(changedBy);
                    grades.save(existing);
                    notifyGrade(existing, examCategory, true);
                }
                result.add(existing);
            }
        }
        return result;
    }

    private void notifyGrade(Grade grade, ExamCategory category, boolean changed) {
        String title = changed ? "Điểm được cập nhật" : "Có điểm mới";
        String label = category.getRequiredCount() > 1
                ? category.getName() + " " + grade.getAssessmentIndex() : category.getName();
        String body = String.format("Môn %s — %s: %.1f", grade.getSubjectName(), label, grade.getScore());
        notifications.notifyUser(grade.getStudentId(), "GRADE", title, body, "GRADE", grade.getId());
        notifications.notifyParentsOfStudent(grade.getStudentId(), "GRADE", title, body, "GRADE", grade.getId());
    }

    public List<GradeChangeLog> changeLogs(String gradeId) {
        return logs.findByGradeIdOrderByChangedAtDesc(gradeId);
    }

    // ---------- Exam categories (A4) ----------
    public List<ExamCategory> listCategories() { return categories.findAll(); }

    public ExamCategory createCategory(CreateExamCategoryRequest r) {
        return categories.save(ExamCategory.builder()
                .id(r.id() == null || r.id().isBlank() ? Ids.gen("ec") : r.id())
                .code(r.code()).name(r.name())
                .weight(r.weight() == null ? 1.0 : r.weight())
                .requiredCount(r.requiredCount() == null ? 1 : r.requiredCount()).build());
    }

    /** Seed raw (không bắn notification) — dùng bởi DataSeeder. */
    public void seed(List<ExamCategory> cats, List<Grade> gradeList) {
        categories.saveAll(cats);
        grades.saveAll(gradeList);
    }

    /** A8: toàn bộ điểm cho báo cáo phổ điểm. */
    public List<Grade> allGrades() { return grades.findAll(); }

    private boolean equalsScore(Double a, Double b) {
        return a != null && b != null && Math.abs(a - b) < 1e-9;
    }

    private String validateTarget(String actorId, String actorRole, String studentId, String expectedClassId,
                                  String subjectId, String semesterId) {
        User student = users.getById(studentId);
        if (!"STUDENT".equals(student.getRole())) throw ApiException.badRequest("Người nhận điểm không phải học sinh");
        if (!"ACTIVE".equals(student.getStatus())) throw ApiException.badRequest("Tài khoản học sinh không hoạt động");
        if (expectedClassId != null && !Objects.equals(expectedClassId, student.getClassId())) {
            throw ApiException.badRequest("Học sinh không thuộc lớp đang nhập điểm");
        }
        structure.assertSemesterExists(semesterId);
        String subjectName = structure.requireSubjectName(subjectId);

        if (!"ADMIN".equals(actorRole)) {
            boolean assigned = timetable.list(null, actorId, null, null).stream().anyMatch(slot ->
                    Objects.equals(slot.getClassId(), student.getClassId())
                            && Objects.equals(slot.getSubjectId(), subjectId)
                            && (slot.getSemesterId() == null || Objects.equals(slot.getSemesterId(), semesterId)));
            if (!assigned) throw ApiException.forbidden("Giáo viên không phụ trách môn/lớp của học sinh này");
        }
        return subjectName;
    }

    private String resolveSubjectId(String actorId, String actorRole, String classId,
                                    String semesterId, String requestedSubjectId) {
        if ("TEACHER".equals(actorRole)) {
            if (classId == null || classId.isBlank()) throw ApiException.badRequest("Thiếu lớp học để xác định môn mặc định");
            return teacherGradebookContext(actorId, classId, semesterId).subjectId();
        }
        if (requestedSubjectId == null || requestedSubjectId.isBlank()) {
            throw ApiException.badRequest("Thiếu môn học");
        }
        return requestedSubjectId;
    }

    private ExamCategory requireCategory(String code, int assessmentIndex) {
        ExamCategory category = categories.findByCode(code)
                .orElseThrow(() -> ApiException.notFound("Loại điểm"));
        if (assessmentIndex > category.getRequiredCount()) {
            throw ApiException.badRequest("Đầu điểm vượt quá số lượng cấu hình của " + category.getName());
        }
        return category;
    }

    private void validateScore(Double score, String studentId) {
        if (score == null || !Double.isFinite(score) || score < 0 || score > 10) {
            throw ApiException.badRequest("Điểm phải trong khoảng 0..10 (HS " + studentId + ")");
        }
    }

    private void assertVersion(Grade grade, Long expectedVersion) {
        if (expectedVersion != null && !Objects.equals(expectedVersion, grade.getVersion())) {
            throw ApiException.conflict("Điểm đã được người khác cập nhật; hãy tải lại dữ liệu trước khi lưu");
        }
    }

    private void audit(Grade grade, String action, Double oldScore, Double newScore,
                       String oldNote, String newNote, String actorId, String reason) {
        logs.save(GradeChangeLog.builder()
                .id(Ids.gen("gcl")).gradeId(grade.getId()).action(action)
                .oldScore(oldScore).newScore(newScore).oldNote(oldNote).newNote(newNote)
                .changedBy(actorId).reason(reason).changedAt(Instant.now()).build());
    }

    private int normalizeAssessmentIndex(Integer value) {
        return value == null ? 1 : value;
    }

    private String normalizeCategory(String value) {
        return value.trim().toUpperCase();
    }

    private String clean(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String reason(String value, String fallback) {
        String cleaned = clean(value);
        return cleaned == null ? fallback : cleaned;
    }
}
