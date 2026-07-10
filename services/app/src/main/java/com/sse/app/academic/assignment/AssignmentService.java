package com.sse.app.academic.assignment;

import com.sse.app.academic.assignment.AssignmentDtos.*;
import com.sse.app.academic.structure.StructureService;
import com.sse.app.common.ApiException;
import com.sse.app.common.Ids;
import com.sse.app.event.DomainEventPublisher;
import com.sse.app.identity.UserService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** B5 + C4: vòng đời bài tập (flowchart 2.7). */
@Service
public class AssignmentService {

    private final AssignmentRepository assignments;
    private final AssignmentSubmissionRepository submissions;
    private final StructureService structure;
    private final UserService users;
    private final DomainEventPublisher events;

    public AssignmentService(AssignmentRepository assignments, AssignmentSubmissionRepository submissions,
                             StructureService structure, UserService users, DomainEventPublisher events) {
        this.assignments = assignments;
        this.submissions = submissions;
        this.structure = structure;
        this.users = users;
        this.events = events;
    }

    public List<Assignment> list(String classId, String teacherId, String status, boolean onlyPublished) {
        List<Assignment> base;
        if (classId != null)        base = assignments.findByClassId(classId);
        else if (teacherId != null) base = assignments.findByTeacherId(teacherId);
        else base = assignments.findAll();
        return base.stream()
                .filter(a -> status == null || status.equals(a.getStatus()))
                .filter(a -> !onlyPublished || "PUBLISHED".equals(a.getStatus()))
                .toList();
    }

    public Assignment get(String id) {
        return assignments.findById(id).orElseThrow(() -> ApiException.notFound("Bài tập"));
    }

    @Transactional
    public Assignment create(CreateAssignmentRequest r, String teacherId) {
        boolean publish = Boolean.TRUE.equals(r.publishNow());
        Assignment a = assignments.save(Assignment.builder()
                .id(r.id() == null || r.id().isBlank() ? Ids.gen("asg") : r.id())
                .classId(r.classId()).subjectId(r.subjectId())
                .subjectName(structure.subjectName(r.subjectId()))
                .teacherId(teacherId).teacherName(users.fullNameOf(teacherId))
                .title(r.title()).description(r.description())
                .status(publish ? "PUBLISHED" : "DRAFT")
                .deadline(r.deadline()).allowLate(Boolean.TRUE.equals(r.allowLate()))
                .attachmentName(r.attachmentName()).createdAt(Instant.now()).build());
        if (publish) publishAssignmentEvent(a);
        return a;
    }

    @Transactional
    public Assignment publish(String id, String teacherId) {
        Assignment a = get(id);
        a.setStatus("PUBLISHED");
        assignments.save(a);
        publishAssignmentEvent(a);
        return a;
    }

    private void publishAssignmentEvent(Assignment a) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("classId", a.getClassId());
        payload.put("subjectName", a.getSubjectName());
        payload.put("title", a.getTitle());
        payload.put("deadline", a.getDeadline() == null ? "" : String.valueOf(a.getDeadline()));
        payload.put("message", "Môn " + a.getSubjectName()
                + (a.getDeadline() != null ? " - hạn " + a.getDeadline() : ""));
        events.publish("academic.assignment.published", a.getTeacherId(), "assignment", a.getId(), payload);
    }

    @Transactional
    public AssignmentSubmission submit(String assignmentId, String studentId, SubmitRequest r) {
        Assignment a = get(assignmentId);
        if (!"PUBLISHED".equals(a.getStatus())) throw ApiException.badRequest("Bài tập chưa mở nộp");

        String status = "SUBMITTED";
        if (a.getDeadline() != null && Instant.now().isAfter(a.getDeadline())) {
            if (!a.isAllowLate()) throw ApiException.badRequest("Đã quá hạn nộp bài");
            status = "LATE";
        }
        final String st = status;
        AssignmentSubmission s = submissions.findByAssignmentIdAndStudentId(assignmentId, studentId)
                .orElseGet(() -> AssignmentSubmission.builder().id(Ids.gen("sub")).build());
        s.setAssignmentId(assignmentId);
        s.setStudentId(studentId);
        s.setStudentName(users.fullNameOf(studentId));
        s.setStatus(st);
        s.setContent(r.content());
        s.setAttachmentName(r.attachmentName());
        s.setSubmittedAt(Instant.now());
        return submissions.save(s);
    }

    @Transactional
    public AssignmentSubmission grade(String submissionId, GradeSubmissionRequest r, String gradedBy) {
        AssignmentSubmission s = submissions.findById(submissionId)
                .orElseThrow(() -> ApiException.notFound("Bài nộp"));
        if (r.score() != null && (r.score() < 0 || r.score() > 10))
            throw ApiException.badRequest("Điểm phải trong 0..10");
        s.setScore(r.score());
        s.setFeedback(r.feedback());
        s.setStatus("GRADED");
        s.setGradedBy(gradedBy);
        s.setGradedAt(Instant.now());
        submissions.save(s);

        Map<String, Object> payload = new HashMap<>();
        payload.put("studentId", s.getStudentId());
        payload.put("assignmentId", s.getAssignmentId());
        payload.put("score", r.score() == null ? "" : r.score());
        payload.put("message", "Điểm: " + (r.score() == null ? "-" : r.score()));
        events.publish("academic.submission.graded", gradedBy, "submission", s.getId(), payload);
        return s;
    }

    public List<AssignmentSubmission> submissionsOf(String assignmentId) {
        return submissions.findByAssignmentId(assignmentId);
    }

    public List<AssignmentSubmission> submissionsByStudent(String studentId) {
        return submissions.findByStudentId(studentId);
    }
}
