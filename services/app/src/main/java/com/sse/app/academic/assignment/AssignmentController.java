package com.sse.app.academic.assignment;

import com.sse.app.academic.assignment.AssignmentDtos.*;
import com.sse.app.common.ApiException;
import com.sse.app.file.FileDtos.PresignDownloadResponse;
import com.sse.app.identity.User;
import com.sse.app.identity.UserService;
import com.sse.app.security.CurrentUser;
import com.sse.app.security.CurrentUserHolder;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;

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
        if (me.isParent()) throw ApiException.forbidden("Use the child assignment endpoints");
        if (me.isTeacher() && teacherId == null && classId == null) teacherId = me.id();
        return assignments.list(classId, teacherId, status, false);
    }

    @GetMapping("/me/assignments")
    public List<Assignment> mine() {
        CurrentUser me = CurrentUserHolder.require();
        if (me.isTeacher()) return assignments.list(null, me.id(), null, false);
        if (me.isParent()) throw ApiException.forbidden("Use the child assignment endpoints");
        User u = users.getById(me.id());
        return assignments.list(u.getClassId(), null, null, true);
    }

    @PostMapping("/assignments")
    public Assignment create(@Valid @RequestBody CreateAssignmentRequest r) {
        CurrentUser me = CurrentUserHolder.require();
        CurrentUserHolder.requireRole("TEACHER");
        return assignments.create(r, me.id());
    }

    @PostMapping("/assignments/{id}/publish")
    public Assignment publish(@PathVariable String id) {
        CurrentUser me = CurrentUserHolder.require();
        CurrentUserHolder.requireRole("TEACHER", "ADMIN");
        return assignments.publish(id, me.id(), me.isAdmin());
    }

    @GetMapping("/assignments/{id}/submissions")
    public List<AssignmentSubmission> submissions(@PathVariable String id) {
        CurrentUser me = CurrentUserHolder.require();
        CurrentUserHolder.requireRole("TEACHER", "ADMIN");
        return assignments.submissionsOf(id, me.id(), me.isAdmin());
    }

    @PostMapping("/assignments/{assignmentId}/submissions/{submissionId}/presigned-download")
    public PresignDownloadResponse submissionDownload(@PathVariable String assignmentId,
                                                       @PathVariable String submissionId) {
        CurrentUser me = CurrentUserHolder.require();
        CurrentUserHolder.requireRole("TEACHER", "ADMIN");
        return assignments.submissionDownloadUrl(assignmentId, submissionId, me.id(), me.isAdmin());
    }

    @PostMapping("/assignments/{id}/attachment/presigned-download")
    public PresignDownloadResponse assignmentAttachmentDownload(@PathVariable String id) {
        CurrentUser me = CurrentUserHolder.require();
        CurrentUserHolder.requireRole("STUDENT");
        return assignments.assignmentAttachmentDownloadUrlForStudent(id, me.id());
    }

    @PostMapping("/assignments/{id}/submit")
    public AssignmentSubmission submit(@PathVariable String id, @RequestBody(required = false) SubmitRequest r) {
        CurrentUser me = CurrentUserHolder.require();
        CurrentUserHolder.requireRole("STUDENT");
        return assignments.submit(id, me.id(), r == null ? new SubmitRequest(null, null, null) : r);
    }

    @PostMapping("/submissions/{id}/grade")
    public AssignmentSubmission grade(@PathVariable String id, @Valid @RequestBody GradeSubmissionRequest r) {
        CurrentUser me = CurrentUserHolder.require();
        CurrentUserHolder.requireRole("TEACHER", "ADMIN");
        return assignments.grade(id, r, me.id(), me.isAdmin());
    }

    @PostMapping("/submissions/batch-grade")
    public List<AssignmentSubmission> batchGrade(
            @Valid @RequestBody BatchGradeRequest request) {
        CurrentUser me = CurrentUserHolder.require();
        CurrentUserHolder.requireRole("TEACHER", "ADMIN");
        return assignments.batchGrade(request, me.id(), me.isAdmin());
    }

    @PostMapping("/submissions/{id}/request-resubmission")
    public SubmissionResubmissionRequest requestResubmission(
            @PathVariable String id,
            @Valid @RequestBody RequestResubmissionRequest request) {
        CurrentUser me = CurrentUserHolder.require();
        CurrentUserHolder.requireRole("TEACHER", "ADMIN");
        return assignments.requestResubmission(
                id, request, me.id(), me.isAdmin());
    }

    @GetMapping("/submissions/{id}/resubmission-requests")
    public List<SubmissionResubmissionRequest> resubmissionRequests(
            @PathVariable String id) {
        CurrentUser me = CurrentUserHolder.require();
        CurrentUserHolder.requireRole("STUDENT", "TEACHER", "ADMIN");
        return assignments.resubmissionRequests(id, me.id(), me.isAdmin());
    }

    @GetMapping("/submissions/{id}/versions")
    public List<AssignmentSubmissionVersion> submissionVersions(
            @PathVariable String id) {
        CurrentUser me = CurrentUserHolder.require();
        CurrentUserHolder.requireRole("STUDENT", "TEACHER", "ADMIN");
        return assignments.submissionVersions(id, me.id(), me.isAdmin());
    }

    @PostMapping("/assignments/{id}/remind-due")
    public AssignmentReminderResponse remindDue(@PathVariable String id) {
        CurrentUser me = CurrentUserHolder.require();
        CurrentUserHolder.requireRole("TEACHER", "ADMIN");
        return assignments.remindDue(id, me.id(), me.isAdmin());
    }

    @GetMapping("/assignments/{id}/submissions/export")
    public ResponseEntity<byte[]> exportSubmissions(@PathVariable String id) {
        CurrentUser me = CurrentUserHolder.require();
        CurrentUserHolder.requireRole("TEACHER", "ADMIN");
        AssignmentExportFile file =
                assignments.exportSubmissions(id, me.id(), me.isAdmin());
        return ResponseEntity.ok()
                .header("Content-Disposition",
                        "attachment; filename=\"" + file.filename() + "\"")
                .header("Content-Type", file.contentType())
                .body(file.content());
    }

    @GetMapping("/me/submissions")
    public List<AssignmentSubmission> mySubmissions() {
        CurrentUser me = CurrentUserHolder.require();
        CurrentUserHolder.requireRole("STUDENT");
        return assignments.submissionsByStudent(me.id());
    }

    @GetMapping("/me/children/{studentId}/assignments")
    public List<Assignment> childAssignments(@PathVariable String studentId) {
        CurrentUser me = CurrentUserHolder.require();
        CurrentUserHolder.requireRole("PARENT");
        users.assertParentOf(me.id(), studentId);
        return assignments.assignmentsForStudent(studentId);
    }

    @GetMapping("/me/children/{studentId}/submissions")
    public List<AssignmentSubmission> childSubmissions(@PathVariable String studentId) {
        CurrentUser me = CurrentUserHolder.require();
        CurrentUserHolder.requireRole("PARENT");
        users.assertParentOf(me.id(), studentId);
        return assignments.submissionsByStudent(studentId);
    }

    @PostMapping("/me/children/{studentId}/assignments/{assignmentId}/attachment/presigned-download")
    public PresignDownloadResponse childAssignmentAttachmentDownload(@PathVariable String studentId,
                                                                      @PathVariable String assignmentId) {
        CurrentUser me = CurrentUserHolder.require();
        CurrentUserHolder.requireRole("PARENT");
        users.assertParentOf(me.id(), studentId);
        return assignments.assignmentAttachmentDownloadUrlForStudent(assignmentId, studentId);
    }

    @PostMapping("/me/children/{studentId}/assignments/{assignmentId}/submissions/{submissionId}/presigned-download")
    public PresignDownloadResponse childSubmissionDownload(@PathVariable String studentId,
                                                           @PathVariable String assignmentId,
                                                           @PathVariable String submissionId) {
        CurrentUser me = CurrentUserHolder.require();
        CurrentUserHolder.requireRole("PARENT");
        users.assertParentOf(me.id(), studentId);
        return assignments.submissionDownloadUrlForStudent(assignmentId, submissionId, studentId);
    }

    @GetMapping("/me/children/{studentId}/submissions/{submissionId}/versions")
    public List<AssignmentSubmissionVersion> childSubmissionVersions(
            @PathVariable String studentId, @PathVariable String submissionId) {
        CurrentUser me = CurrentUserHolder.require();
        CurrentUserHolder.requireRole("PARENT");
        users.assertParentOf(me.id(), studentId);
        return assignments.submissionVersions(submissionId, studentId, false);
    }

    @GetMapping("/me/children/{studentId}/submissions/{submissionId}/resubmission-requests")
    public List<SubmissionResubmissionRequest> childResubmissionRequests(
            @PathVariable String studentId, @PathVariable String submissionId) {
        CurrentUser me = CurrentUserHolder.require();
        CurrentUserHolder.requireRole("PARENT");
        users.assertParentOf(me.id(), studentId);
        return assignments.resubmissionRequests(submissionId, studentId, false);
    }
}
