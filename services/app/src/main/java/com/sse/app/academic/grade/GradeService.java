package com.sse.app.academic.grade;

import com.sse.app.academic.structure.StructureService;
import com.sse.app.academic.grade.GradeDtos.*;
import com.sse.app.common.ApiException;
import com.sse.app.common.Ids;
import com.sse.app.academic.timetable.TimetableService;
import com.sse.app.academic.teaching.TeachingAssignmentService;
import com.sse.app.event.DomainEventPublisher;
import com.sse.app.identity.UserService;
import com.sse.app.report.AcademicResultLockService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.Comparator;
import java.util.stream.Collectors;

/** B4: Quản lý điểm (nhập/sửa có log — flowchart 2.6) + A4: cấu hình loại điểm. */
@Service
public class GradeService {

    private final GradeRepository grades;
    private final GradeChangeLogRepository logs;
    private final ExamCategoryRepository categories;
    private final GradeConfigurationRepository configurations;
    private final StructureService structure;
    private final TimetableService timetable;
    private final TeachingAssignmentService teachingAssignments;
    private final UserService users;
    private final DomainEventPublisher events;
    private final AcademicResultLockService resultLocks;

    public GradeService(GradeRepository grades, GradeChangeLogRepository logs,
                        ExamCategoryRepository categories, GradeConfigurationRepository configurations,
                        StructureService structure,
                        TimetableService timetable, TeachingAssignmentService teachingAssignments,
                        UserService users, DomainEventPublisher events,
                        AcademicResultLockService resultLocks) {
        this.grades = grades;
        this.logs = logs;
        this.categories = categories;
        this.configurations = configurations;
        this.structure = structure;
        this.timetable = timetable;
        this.teachingAssignments = teachingAssignments;
        this.users = users;
        this.events = events;
        this.resultLocks = resultLocks;
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
        int entryIndex = req.entryIndex() == null ? 1 : req.entryIndex();
        if (entryIndex < 1) throw ApiException.badRequest("Vị trí đầu điểm phải từ 1 trở lên");
        GradeConfiguration config = configurationFor(req.subjectId(), req.semesterId(), req.category());
        if (config != null) {
            if (!config.isActive()) throw ApiException.conflict("Loại điểm này đang bị tắt cho môn/học kỳ đã chọn");
            if (entryIndex > config.getRequiredCount()) {
                throw ApiException.badRequest("Vị trí đầu điểm vượt quá số lượng đã cấu hình");
            }
            categoryName = config.getCategoryName();
        }

        List<Grade> result = new ArrayList<>();
        for (Entry e : req.entries()) {
            String classId = users.dtoById(e.studentId()).classId();
            resultLocks.assertGradeWritable(classId, req.semesterId());
            if (enforceTeacherAssignment) {
                if (!isTeacherAssigned(changedBy, classId, req.subjectId(), req.semesterId())) {
                    throw ApiException.forbidden("Giáo viên chỉ được nhập điểm cho lớp/môn được phân công");
                }
            }
            if (e.score() == null || e.score() < 0 || e.score() > 10) {
                throw ApiException.badRequest("Điểm phải trong khoảng 0..10 (HS " + e.studentId() + ")");
            }
            Grade existing = grades.findByStudentIdAndSubjectIdAndSemesterIdAndCategoryAndEntryIndex(
                    e.studentId(), req.subjectId(), req.semesterId(), req.category(), entryIndex).orElse(null);

            if (existing == null) {
                Grade g = grades.save(Grade.builder()
                        .id(Ids.gen("g")).studentId(e.studentId())
                        .subjectId(req.subjectId()).subjectName(subjectName)
                        .semesterId(req.semesterId()).category(req.category()).categoryName(categoryName)
                        .entryIndex(entryIndex)
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

    public List<GradeConfiguration> listConfigurations(String subjectId, String semesterId) {
        if (subjectId == null || subjectId.isBlank() || semesterId == null || semesterId.isBlank()) {
            throw ApiException.badRequest("Bắt buộc subjectId và semesterId");
        }
        return configurations.findBySubjectIdAndSemesterIdOrderByCategoryCodeAsc(subjectId, semesterId);
    }

    @Transactional
    public GradeConfiguration upsertConfiguration(UpsertGradeConfigurationRequest request, String actorId) {
        String code = request.categoryCode().trim().toUpperCase(Locale.ROOT);
        ExamCategory fallback = categories.findByCode(code).orElse(null);
        int requiredCount = request.requiredCount() == null ? 1 : request.requiredCount();
        double weight = request.weight() == null
                ? fallback == null ? 1.0 : fallback.getWeight() : request.weight();
        if (requiredCount < 1 || requiredCount > 20) {
            throw ApiException.badRequest("Số đầu điểm bắt buộc phải từ 1 đến 20");
        }
        if (weight <= 0 || weight > 10) {
            throw ApiException.badRequest("Hệ số điểm phải lớn hơn 0 và không vượt quá 10");
        }
        GradeConfiguration row = configurations
                .findBySubjectIdAndSemesterIdAndCategoryCode(
                        request.subjectId(), request.semesterId(), code)
                .orElseGet(() -> GradeConfiguration.builder()
                        .id(Ids.gen("gcfg"))
                        .subjectId(request.subjectId())
                        .semesterId(request.semesterId())
                        .categoryCode(code)
                        .build());
        row.setCategoryName(request.categoryName() == null || request.categoryName().isBlank()
                ? fallback == null ? code : fallback.getName()
                : request.categoryName().trim());
        row.setRequiredCount(requiredCount);
        row.setWeight(weight);
        row.setActive(request.active() == null || request.active());
        row.setUpdatedBy(actorId);
        row.setUpdatedAt(Instant.now());
        return configurations.save(row);
    }

    public GradeCompletenessResponse completeness(
            String classId, String subjectId, String semesterId,
            String actorId, boolean enforceTeacherAssignment) {
        if (classId == null || classId.isBlank()
                || subjectId == null || subjectId.isBlank()
                || semesterId == null || semesterId.isBlank()) {
            throw ApiException.badRequest(
                    "Bắt buộc classId, subjectId và semesterId");
        }
        if (enforceTeacherAssignment
                && !isTeacherAssigned(actorId, classId, subjectId, semesterId)) {
            throw ApiException.forbidden(
                    "Giáo viên chưa được phân công lớp/môn/học kỳ này");
        }
        List<String> expected = expectedSlots(subjectId, semesterId);
        Map<String, Set<String>> enteredByStudent =
                grades.findBySubjectIdAndSemesterId(subjectId, semesterId).stream()
                        .filter(grade -> grade.getStudentId() != null)
                        .collect(Collectors.groupingBy(Grade::getStudentId,
                                Collectors.mapping(this::slotKey,
                                        Collectors.toSet())));
        List<GradeCompletenessStudent> rows = users
                .list("STUDENT", null, classId).stream()
                .sorted(Comparator.comparing(
                        com.sse.app.identity.UserDto::studentCode,
                        Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)))
                .map(student -> {
                    Set<String> entered = enteredByStudent.getOrDefault(
                            student.id(), Set.of());
                    List<String> missing = expected.stream()
                            .filter(code -> !entered.contains(code)).toList();
                    return new GradeCompletenessStudent(
                            student.id(), student.studentCode(),
                            student.fullName(),
                            expected.size() - missing.size(),
                            expected.size(), missing, missing.isEmpty());
                }).toList();
        int complete = (int) rows.stream()
                .filter(GradeCompletenessStudent::complete).count();
        return new GradeCompletenessResponse(
                classId, subjectId, semesterId, rows.size(), complete,
                rows.size() - complete, rows, Instant.now());
    }

    private boolean isTeacherAssigned(String teacherId, String classId, String subjectId, String semesterId) {
        if (classId == null) return false;
        return teachingAssignments.teacherAssigned(teacherId, classId, subjectId, semesterId)
                || timetable.teacherAssigned(teacherId, classId, subjectId, semesterId);
    }

    private boolean changed(String a, String b) {
        return a == null ? b != null : !a.equals(b);
    }

    private GradeConfiguration configurationFor(String subjectId, String semesterId, String category) {
        return configurations.findBySubjectIdAndSemesterIdAndCategoryCode(
                subjectId, semesterId, category.trim().toUpperCase(Locale.ROOT)).orElse(null);
    }

    private List<String> expectedSlots(String subjectId, String semesterId) {
        List<GradeConfiguration> configured = configurations
                .findBySubjectIdAndSemesterIdOrderByCategoryCodeAsc(subjectId, semesterId)
                .stream().filter(GradeConfiguration::isActive).toList();
        if (configured.isEmpty()) {
            return categories.findAll().stream().map(ExamCategory::getCode)
                    .filter(code -> code != null && !code.isBlank())
                    .distinct().sorted().toList();
        }
        List<String> slots = new ArrayList<>();
        configured.forEach(config -> {
            for (int index = 1; index <= config.getRequiredCount(); index++) {
                slots.add(config.getCategoryCode() + (config.getRequiredCount() == 1 ? "" : "#" + index));
            }
        });
        return slots;
    }

    private String slotKey(Grade grade) {
        int index = grade.getEntryIndex() < 1 ? 1 : grade.getEntryIndex();
        GradeConfiguration config = configurationFor(
                grade.getSubjectId(), grade.getSemesterId(), grade.getCategory());
        return config != null && config.getRequiredCount() > 1
                ? grade.getCategory() + "#" + index : grade.getCategory();
    }
}
