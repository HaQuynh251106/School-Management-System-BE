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

    @PostMapping("/assignments")
    public Assignment create(@Valid @RequestBody CreateAssignmentRequest r) {
        CurrentUser me = CurrentUserHolder.require();
        CurrentUserHolder.requireRole("TEACHER", "ADMIN");
        return assignments.create(r, me.id());
    }

    @PostMapping("/assignments/{id}/publish")
    public Assignment publish(@PathVariable String id) {
        CurrentUser me = CurrentUserHolder.require();
        CurrentUserHolder.requireRole("TEACHER", "ADMIN");
        return assignments.publish(id, me.id());
    }

    @GetMapping("/assignments/{id}/submissions")
    public List<AssignmentSubmission> submissions(@PathVariable String id) {
        CurrentUserHolder.requireRole("TEACHER", "ADMIN");
        return assignments.submissionsOf(id);
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
        return assignments.grade(id, r, me.id());
    }

    @GetMapping("/me/submissions")
    public List<AssignmentSubmission> mySubmissions() {
        CurrentUser me = CurrentUserHolder.require();
        return assignments.submissionsByStudent(me.id());
    }
}
