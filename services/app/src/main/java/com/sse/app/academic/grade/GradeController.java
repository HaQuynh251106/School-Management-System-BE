package com.sse.app.academic.grade;

import com.sse.app.academic.grade.GradeDtos.*;
import com.sse.app.common.ApiException;
import com.sse.app.identity.UserDto;
import com.sse.app.identity.UserService;
import com.sse.app.security.CurrentUser;
import com.sse.app.security.CurrentUserHolder;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
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
        } else if (me.isTeacher()) {
            if (classId == null || classId.isBlank() || semesterId == null || semesterId.isBlank()) {
                throw ApiException.badRequest("Giáo viên phải chọn lớp và học kỳ để xem bảng điểm");
            }
            subjectId = grades.resolveTeacherViewSubject(me.id(), classId, semesterId, subjectId);
        }
        Set<String> studentIds = null;
        if (classId != null) {
            studentIds = users.list("STUDENT", null, classId).stream()
                    .map(UserDto::id).collect(Collectors.toSet());
        }
        return grades.list(studentId, subjectId, semesterId, category, studentIds);
    }

    @PostMapping("/grades/bulk")
    public List<Grade> bulk(@Valid @RequestBody BulkGradeRequest req) {
        CurrentUser me = CurrentUserHolder.require();
        CurrentUserHolder.requireRole("TEACHER", "ADMIN");
        return grades.bulkUpsert(req, me.id(), me.role());
    }

    @GetMapping("/me/gradebook-context")
    public TeacherGradebookContext teacherGradebookContext(@RequestParam String classId,
                                                           @RequestParam String semesterId) {
        CurrentUser me = CurrentUserHolder.require();
        CurrentUserHolder.requireRole("TEACHER");
        return grades.teacherGradebookContext(me.id(), classId, semesterId);
    }

    @PostMapping("/grades")
    @ResponseStatus(HttpStatus.CREATED)
    public Grade create(@Valid @RequestBody CreateGradeRequest req) {
        CurrentUser me = CurrentUserHolder.require();
        CurrentUserHolder.requireRole("TEACHER", "ADMIN");
        return grades.create(req, me.id(), me.role());
    }

    @PutMapping("/grades/{id}")
    public Grade update(@PathVariable String id, @Valid @RequestBody UpdateGradeRequest req) {
        CurrentUser me = CurrentUserHolder.require();
        CurrentUserHolder.requireRole("TEACHER", "ADMIN");
        return grades.update(id, req, me.id(), me.role());
    }

    @GetMapping("/grades/{id}/change-logs")
    public List<GradeChangeLog> changeLogs(@PathVariable String id) {
        CurrentUserHolder.requireRole("TEACHER", "ADMIN");
        return grades.changeLogs(id, CurrentUserHolder.require());
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

    @PutMapping("/exam-categories/{id}")
    public ExamCategory updateCategory(@PathVariable String id, @Valid @RequestBody CreateExamCategoryRequest r) {
        CurrentUserHolder.requireRole("ADMIN");
        return grades.updateCategory(id, r);
    }

    @DeleteMapping("/exam-categories/{id}")
    public void deleteCategory(@PathVariable String id) {
        CurrentUserHolder.requireRole("ADMIN");
        grades.deleteCategory(id);
    }
}
