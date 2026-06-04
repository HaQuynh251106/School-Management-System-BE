package com.sse.app.academic.grade;

import com.sse.app.academic.structure.StructureService;
import com.sse.app.academic.grade.GradeDtos.*;
import com.sse.app.common.ApiException;
import com.sse.app.common.Ids;
import com.sse.app.identity.UserService;
import com.sse.app.notification.NotificationService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/** B4: Quản lý điểm (nhập/sửa có log — flowchart 2.6) + A4: cấu hình loại điểm. */
@Service
public class GradeService {

    private final GradeRepository grades;
    private final GradeChangeLogRepository logs;
    private final ExamCategoryRepository categories;
    private final StructureService structure;
    private final UserService users;
    private final NotificationService notifications;

    public GradeService(GradeRepository grades, GradeChangeLogRepository logs,
                        ExamCategoryRepository categories, StructureService structure,
                        UserService users, NotificationService notifications) {
        this.grades = grades;
        this.logs = logs;
        this.categories = categories;
        this.structure = structure;
        this.users = users;
        this.notifications = notifications;
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
    public List<Grade> bulkUpsert(BulkGradeRequest req, String changedBy) {
        String subjectName = structure.subjectName(req.subjectId());
        String categoryName = categories.findByCode(req.category())
                .map(ExamCategory::getName).orElse(req.category());

        List<Grade> result = new ArrayList<>();
        for (Entry e : req.entries()) {
            if (e.score() == null || e.score() < 0 || e.score() > 10) {
                throw ApiException.badRequest("Điểm phải trong khoảng 0..10 (HS " + e.studentId() + ")");
            }
            Grade existing = grades.findByStudentIdAndSubjectIdAndSemesterIdAndCategory(
                    e.studentId(), req.subjectId(), req.semesterId(), req.category()).orElse(null);

            if (existing == null) {
                Grade g = grades.save(Grade.builder()
                        .id(Ids.gen("g")).studentId(e.studentId())
                        .subjectId(req.subjectId()).subjectName(subjectName)
                        .semesterId(req.semesterId()).category(req.category()).categoryName(categoryName)
                        .score(e.score()).note(e.note()).recordedAt(Instant.now()).build());
                result.add(g);
                notifyGrade(e.studentId(), subjectName, categoryName, e.score(), false);
            } else {
                if (!equalsScore(existing.getScore(), e.score()) || changed(existing.getNote(), e.note())) {
                    logs.save(GradeChangeLog.builder()
                            .id(Ids.gen("gcl")).gradeId(existing.getId())
                            .oldScore(existing.getScore()).newScore(e.score())
                            .oldNote(existing.getNote()).newNote(e.note())
                            .changedBy(changedBy).reason(req.reason()).changedAt(Instant.now()).build());
                    existing.setScore(e.score());
                    existing.setNote(e.note());
                    existing.setRecordedAt(Instant.now());
                    grades.save(existing);
                    notifyGrade(e.studentId(), subjectName, categoryName, e.score(), true);
                }
                result.add(existing);
            }
        }
        return result;
    }

    private void notifyGrade(String studentId, String subjectName, String categoryName, Double score, boolean changed) {
        String title = changed ? "Điểm được cập nhật" : "Có điểm mới";
        String body = String.format("Môn %s — %s: %.1f", subjectName, categoryName, score);
        notifications.notifyUser(studentId, "GRADE", title, body, "GRADE", null);
        notifications.notifyParentsOfStudent(studentId, "GRADE", title, body, "GRADE", null);
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
                .weight(r.weight() == null ? 1.0 : r.weight()).build());
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

    private boolean changed(String a, String b) {
        return a == null ? b != null : !a.equals(b);
    }
}
