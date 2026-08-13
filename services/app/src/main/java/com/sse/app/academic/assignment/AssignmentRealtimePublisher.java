package com.sse.app.academic.assignment;

import com.sse.app.identity.UserDto;
import com.sse.app.identity.UserService;
import com.sse.app.realtime.RealtimeEventHub;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

@Component
@RequiredArgsConstructor
class AssignmentRealtimePublisher {
    private final AssignmentRepository assignments;
    private final AssignmentSubmissionRepository submissions;
    private final UserService users;
    private final RealtimeEventHub realtime;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void publish(AssignmentChangedEvent event) {
        assignments.findById(event.assignmentId()).ifPresent(assignment -> {
            AssignmentSubmission submission = event.submissionId() == null ? null
                    : submissions.findById(event.submissionId()).orElse(null);
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("resource", "ASSIGNMENT");
            payload.put("action", event.action());
            payload.put("entityId", assignment.getId());
            payload.put("assignmentId", assignment.getId());
            payload.put("classId", assignment.getClassId());
            payload.put("subjectId", assignment.getSubjectId());
            payload.put("teacherId", assignment.getTeacherId());
            payload.put("status", assignment.getStatus());
            if (submission != null) {
                payload.put("submissionId", submission.getId());
                payload.put("studentId", submission.getStudentId());
                payload.put("submissionStatus", submission.getStatus());
            }
            payload.put("occurredAt", Instant.now().toString());

            recipients(event.action(), assignment, submission)
                    .forEach(userId -> realtime.publish(userId, "ASSIGNMENT_UPDATED", payload));
        });
    }

    private Set<String> recipients(String action, Assignment assignment, AssignmentSubmission submission) {
        LinkedHashSet<String> recipients = new LinkedHashSet<>();
        if ("SUBMITTED".equals(action)) {
            recipients.add(assignment.getTeacherId());
            return recipients;
        }
        if ("GRADED".equals(action) || "RESUBMISSION_ALLOWED".equals(action)) {
            if (submission != null) {
                recipients.add(submission.getStudentId());
                recipients.addAll(users.parentIdsOf(submission.getStudentId()));
            }
            return recipients;
        }

        var students = users.list("STUDENT", null, assignment.getClassId()).stream()
                .map(UserDto::id).toList();
        recipients.addAll(students);
        students.forEach(studentId -> recipients.addAll(users.parentIdsOf(studentId)));
        return recipients;
    }
}
