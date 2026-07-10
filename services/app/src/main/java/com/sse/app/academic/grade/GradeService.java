package com.sse.app.academic.grade;

import com.sse.app.academic.structure.StructureService;
import com.sse.app.academic.grade.GradeDtos.*;
import com.sse.app.common.ApiException;
import com.sse.app.common.Ids;
import com.sse.app.academic.timetable.TimetableService;
import com.sse.app.academic.teaching.TeachingAssignmentService;
import com.sse.app.event.DomainEventPublisher;
import com.sse.app.identity.UserService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/** B4: Quản lý điểm (nhập/sửa có log — flowchart 2.6) + A4: cấu hình loại điểm. */
@Service
public class GradeService {

    private final GradeRepository grades;
    private final GradeChangeLogRepository logs;
    private final ExamCategoryRepository categories;
    private final StructureService structure;
    private final TimetableService timetable;
    private final TeachingAssignmentService teachingAssignments;
    private final UserService users;
    private final DomainEventPublisher events;

    public GradeService(GradeRepository grades, GradeChangeLogRepository logs,
                        ExamCategoryRepository categories, StructureService structure,
                        TimetableService timetable, TeachingAssignmentService teachingAssignments,
                        UserService users, DomainEventPublisher events) {
        this.grades = grades;
        this.logs = logs;
        this.categories = categories;
        this.structure = structure;
        this.timetable = timetable;
        this.teachingAssignments = teachingAssignments;
        this.users = users;
        this.events = events;
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
    public List<Grade> bulkUpsert(BulkGradeRequest req, String changedBy, boolean enforceTeacherAssignment) {
        String subjectName = structure.subjectName(req.subjectId());
        String categoryName = categories.findByCode(req.category())
                .map(ExamCategory::getName).orElse(req.category());

        List<Grade> result = new ArrayList<>();
        for (Entry e : req.entries()) {
            if (enforceTeacherAssignment) {
                String classId = users.dtoById(e.studentId()).classId();
                if (!isTeacherAssigned(changedBy, classId, req.subjectId(), req.semesterId())) {
                    throw ApiException.forbidden("Giáo viên chỉ được nhập điểm cho lớp/môn được phân công");
                }
            }
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
                publishGradeEvent("academic.grade.published", g.getId(), e.studentId(), subjectName, categoryName, e.score());
            } else {
                if (!equalsScore(existing.getScore(), e.score()) || changed(existing.getNote(), e.note())) {
                    if (req.reason() == null || req.reason().isBlank()) {
                        throw ApiException.badRequest("Cần nhập lý do khi sửa điểm");
                    }
                    logs.save(GradeChangeLog.builder()
                            .id(Ids.gen("gcl")).gradeId(existing.getId())
                            .oldScore(existing.getScore()).newScore(e.score())
                            .oldNote(existing.getNote()).newNote(e.note())
                            .changedBy(changedBy).reason(req.reason()).changedAt(Instant.now()).build());
                    existing.setScore(e.score());
                    existing.setNote(e.note());
                    existing.setRecordedAt(Instant.now());
                    grades.save(existing);
                    publishGradeEvent("academic.grade.changed", existing.getId(), e.studentId(), subjectName, categoryName, e.score());
                }
                result.add(existing);
            }
        }
        return result;
    }

    private void publishGradeEvent(String eventName, String gradeId, String studentId,
                                   String subjectName, String categoryName, Double score) {
        String body = String.format("Môn %s — %s: %.1f", subjectName, categoryName, score);
        events.publish(eventName, studentId, "grade", gradeId,
                Map.of("studentId", studentId,
                        "subjectName", subjectName == null ? "" : subjectName,
                        "categoryName", categoryName == null ? "" : categoryName,
                        "score", score,
                        "message", body));
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

    private boolean isTeacherAssigned(String teacherId, String classId, String subjectId, String semesterId) {
        if (classId == null) return false;
        return teachingAssignments.teacherAssigned(teacherId, classId, subjectId, semesterId)
                || timetable.teacherAssigned(teacherId, classId, subjectId, semesterId);
    }

    private boolean changed(String a, String b) {
        return a == null ? b != null : !a.equals(b);
    }
}
