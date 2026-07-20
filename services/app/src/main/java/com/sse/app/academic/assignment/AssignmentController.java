package com.sse.app.academic.assignment;

import com.sse.app.academic.assignment.AssignmentDtos.*;
import com.sse.app.identity.User;
import com.sse.app.identity.UserService;
import com.sse.app.security.CurrentUser;
import com.sse.app.security.CurrentUserHolder;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** B5/C4: Quản lý & nộp bài tập. */
@RestController
public class AssignmentController {

    private final AssignmentService assignments;
    private final UserService users;

    public AssignmentController(AssignmentService assignments, UserService users) {
        this.assignments = assignments;
        this.users = users;
    }

    @GetMapping("/assignments")
    public List<Assignment> list(@RequestParam(required = false) String classId,
                                 @RequestParam(required = false) String teacherId,
                                 @RequestParam(required = false) String status) {
        CurrentUser me = CurrentUserHolder.require();
        if (me.isStudent()) {
            User u = users.getById(me.id());
            return assignments.list(u.getClassId(), null, status, true);
        }
        if (me.isTeacher() && teacherId == null && classId == null) teacherId = me.id();
        return assignments.list(classId, teacherId, status, false);
    }

    @GetMapping("/me/assignments")
    public List<Assignment> mine() {
        CurrentUser me = CurrentUserHolder.require();
        if (me.isTeacher()) return assignments.list(null, me.id(), null, false);
        User u = users.getById(me.id());
        return assignments.list(u.getClassId(), null, null, true);
    }

    @GetMapping("/children/{studentId}/assignments")
    public List<Assignment> assignmentsOfChild(@PathVariable String studentId) {
        CurrentUser me = CurrentUserHolder.require();
        CurrentUserHolder.requireRole("PARENT");
        users.assertParentOf(me.id(), studentId);
        User student = users.getById(studentId);
        return assignments.list(student.getClassId(), null, null, true);
    }

    @GetMapping("/children/{studentId}/submissions")
    public List<AssignmentSubmission> submissionsOfChild(@PathVariable String studentId) {
        CurrentUser me = CurrentUserHolder.require();
        CurrentUserHolder.requireRole("PARENT");
        users.assertParentOf(me.id(), studentId);
        return assignments.submissionsByStudent(studentId);
    }

    @PostMapping("/assignments")
    public Assignment create(@Valid @RequestBody CreateAssignmentRequest r) {
        CurrentUser me = CurrentUserHolder.require();
        CurrentUserHolder.requireRole("TEACHER", "ADMIN");
        return assignments.create(r, me.id(), me.role());
    }

    @PostMapping("/assignments/{id}/publish")
    public Assignment publish(@PathVariable String id) {
        CurrentUser me = CurrentUserHolder.require();
        CurrentUserHolder.requireRole("TEACHER", "ADMIN");
        return assignments.publish(id, me.id(), me.role());
    }

    @PutMapping("/assignments/{id}")
    public Assignment update(@PathVariable String id, @Valid @RequestBody UpdateAssignmentRequest request) {
        CurrentUser me = CurrentUserHolder.require();
        CurrentUserHolder.requireRole("TEACHER", "ADMIN");
        return assignments.update(id, request, me.id(), me.role());
    }

    @DeleteMapping("/assignments/{id}")
    public void delete(@PathVariable String id) {
        CurrentUser me = CurrentUserHolder.require();
        CurrentUserHolder.requireRole("TEACHER", "ADMIN");
        assignments.delete(id, me.id(), me.role());
    }

    @PostMapping("/assignments/{id}/extend")
    public Assignment extend(@PathVariable String id, @Valid @RequestBody ExtendDeadlineRequest request) {
        CurrentUser me = CurrentUserHolder.require();
        CurrentUserHolder.requireRole("TEACHER", "ADMIN");
        return assignments.extend(id, request.deadline(), me.id(), me.role());
    }

    @PostMapping("/assignments/{id}/close")
    public Assignment close(@PathVariable String id) {
        CurrentUser me = CurrentUserHolder.require();
        CurrentUserHolder.requireRole("TEACHER", "ADMIN");
        return assignments.setOpen(id, false, me.id(), me.role());
    }

    @PostMapping("/assignments/{id}/reopen")
    public Assignment reopen(@PathVariable String id) {
        CurrentUser me = CurrentUserHolder.require();
        CurrentUserHolder.requireRole("TEACHER", "ADMIN");
        return assignments.setOpen(id, true, me.id(), me.role());
    }

    @GetMapping("/assignments/{id}/submissions")
    public List<AssignmentSubmission> submissions(@PathVariable String id) {
        CurrentUser me = CurrentUserHolder.require();
        CurrentUserHolder.requireRole("TEACHER", "ADMIN");
        return assignments.submissionsOf(id, me.id(), me.role());
    }

    @PostMapping("/assignments/{id}/submit")
    public AssignmentSubmission submit(@PathVariable String id, @RequestBody(required = false) SubmitRequest r) {
        CurrentUser me = CurrentUserHolder.require();
        CurrentUserHolder.requireRole("STUDENT");
        return assignments.submit(id, me.id(), r == null ? new SubmitRequest(null, null) : r);
    }

    @PostMapping("/submissions/{id}/grade")
    public AssignmentSubmission grade(@PathVariable String id, @Valid @RequestBody GradeSubmissionRequest r) {
        CurrentUser me = CurrentUserHolder.require();
        CurrentUserHolder.requireRole("TEACHER", "ADMIN");
        return assignments.grade(id, r, me.id(), me.role());
    }

    @PostMapping("/submissions/{id}/allow-resubmit")
    public AssignmentSubmission allowResubmit(@PathVariable String id) {
        CurrentUser me = CurrentUserHolder.require();
        CurrentUserHolder.requireRole("TEACHER", "ADMIN");
        return assignments.allowResubmit(id, me.id(), me.role());
    }

    @GetMapping("/me/submissions")
    public List<AssignmentSubmission> mySubmissions() {
        CurrentUser me = CurrentUserHolder.require();
        return assignments.submissionsByStudent(me.id());
    }
}
