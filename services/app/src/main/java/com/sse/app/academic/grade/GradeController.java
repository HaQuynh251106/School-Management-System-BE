package com.sse.app.academic.grade;

import com.sse.app.academic.grade.GradeDtos.*;
import com.sse.app.common.ApiException;
import com.sse.app.identity.UserDto;
import com.sse.app.identity.UserService;
import com.sse.app.security.CurrentUser;
import com.sse.app.security.CurrentUserHolder;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/** B4/C2/D2/A4: Bảng điểm + cấu hình loại điểm. Route /grades khớp json-server. */
@RestController
public class GradeController {

    private final GradeService grades;
    private final UserService users;

    public GradeController(GradeService grades, UserService users) {
        this.grades = grades;
        this.users = users;
    }

    @GetMapping("/grades")
    public List<Grade> list(@RequestParam(required = false) String studentId,
                            @RequestParam(required = false) String subjectId,
                            @RequestParam(required = false) String semesterId,
                            @RequestParam(required = false) String category,
                            @RequestParam(required = false) String classId) {
        CurrentUser me = CurrentUserHolder.require();
        if (me.isStudent()) {
            studentId = me.id();
        } else if (me.isParent()) {
            if (studentId == null) throw ApiException.badRequest("Thiếu studentId (chọn con)");
            users.assertParentOf(me.id(), studentId);
        }
        Set<String> studentIds = null;
        if (classId != null) {
            studentIds = users.list("STUDENT", null, classId).stream()
                    .map(UserDto::id).collect(Collectors.toSet());
        }
        return grades.list(studentId, subjectId, semesterId, category, studentIds);
    }

    @GetMapping("/students/{studentId}/grades")
    public List<Grade> studentGrades(@PathVariable String studentId,
                                     @RequestParam(required = false) String subjectId,
                                     @RequestParam(required = false) String semesterId,
                                     @RequestParam(required = false) String category) {
        CurrentUser me = CurrentUserHolder.require();
        if (me.isStudent() && !me.id().equals(studentId)) throw ApiException.forbidden("Không đủ quyền");
        if (me.isParent()) users.assertParentOf(me.id(), studentId);
        return grades.list(studentId, subjectId, semesterId, category, null);
    }

    @PostMapping("/grades/bulk")
    public List<Grade> bulk(@Valid @RequestBody BulkGradeRequest req) {
        CurrentUser me = CurrentUserHolder.require();
        CurrentUserHolder.requireRole("TEACHER", "ADMIN");
        return grades.bulkUpsert(req, me.id(), me.isTeacher());
    }

    @GetMapping("/grades/{id}/change-logs")
    public List<GradeChangeLog> changeLogs(@PathVariable String id) {
        CurrentUserHolder.requireRole("TEACHER", "ADMIN");
        return grades.changeLogs(id);
    }

    @GetMapping("/exam-categories")
    public List<ExamCategory> categories() {
        CurrentUserHolder.require();
        return grades.listCategories();
    }

    @PostMapping("/exam-categories")
    public ExamCategory createCategory(@Valid @RequestBody CreateExamCategoryRequest r) {
        CurrentUserHolder.requireRole("ADMIN");
        return grades.createCategory(r);
    }

    @GetMapping("/grade-configurations")
    public List<GradeConfiguration> configurations(
            @RequestParam String subjectId,
            @RequestParam String semesterId) {
        CurrentUserHolder.requireRole("ADMIN", "TEACHER");
        return grades.listConfigurations(subjectId, semesterId);
    }

    @PutMapping("/grade-configurations")
    public GradeConfiguration upsertConfiguration(
            @Valid @RequestBody UpsertGradeConfigurationRequest request) {
        CurrentUser actor = CurrentUserHolder.require();
        CurrentUserHolder.requireRole("ADMIN");
        return grades.upsertConfiguration(request, actor.id());
    }

    @GetMapping("/grades/completeness")
    public GradeCompletenessResponse completeness(
            @RequestParam String classId,
            @RequestParam String subjectId,
            @RequestParam String semesterId) {
        CurrentUser me = CurrentUserHolder.require();
        CurrentUserHolder.requireRole("TEACHER", "ADMIN");
        return grades.completeness(
                classId, subjectId, semesterId, me.id(), me.isTeacher());
    }
}
