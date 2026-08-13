package com.sse.app.academic.exam;

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
class ExamRealtimePublisher {
    private final ExamCandidateRepository candidates;
    private final ExamResultRepository results;
    private final ExamReviewRepository reviews;
    private final ExamRoomRepository rooms;
    private final ExamGradingAssignmentRepository graders;
    private final UserService users;
    private final RealtimeEventHub realtime;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void publish(ExamChangedEvent event) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("resource", "EXAM");
        payload.put("action", event.action());
        payload.put("examPeriodId", event.examPeriodId());
        if (event.resultId() != null) payload.put("resultId", event.resultId());
        if (event.reviewId() != null) payload.put("reviewId", event.reviewId());
        payload.put("occurredAt", Instant.now().toString());

        recipients(event).forEach(userId -> realtime.publish(userId, "EXAM_UPDATED", payload));
    }

    private Set<String> recipients(ExamChangedEvent event) {
        LinkedHashSet<String> recipients = new LinkedHashSet<>();
        if ("SCHEDULE_PUBLISHED".equals(event.action())) {
            candidates.findByExamPeriodId(event.examPeriodId()).forEach(candidate -> {
                recipients.add(candidate.getStudentId());
                recipients.addAll(users.parentIdsOf(candidate.getStudentId()));
            });
            graders.findByExamPeriodId(event.examPeriodId())
                    .forEach(assignment -> recipients.add(assignment.getTeacherId()));
            candidates.findByExamPeriodId(event.examPeriodId()).stream()
                    .map(ExamCandidate::getScheduleId).distinct()
                    .flatMap(scheduleId -> rooms.findByScheduleId(scheduleId).stream())
                    .forEach(room -> {
                        recipients.add(room.getProctorOneId());
                        recipients.add(room.getProctorTwoId());
                    });
            recipients.remove(null);
            recipients.remove("");
            return recipients;
        }

        if (event.resultId() != null) {
            results.findById(event.resultId()).ifPresent(result -> {
                recipients.add(result.getStudentId());
                recipients.addAll(users.parentIdsOf(result.getStudentId()));
            });
        }
        if (event.reviewId() != null) {
            reviews.findById(event.reviewId()).ifPresent(review -> {
                recipients.add(review.getStudentId());
                recipients.addAll(users.parentIdsOf(review.getStudentId()));
                if ("REVIEW_REQUESTED".equals(event.action())) {
                    results.findById(review.getResultId()).ifPresent(result ->
                            candidates.findByScheduleIdAndStudentId(result.getScheduleId(), result.getStudentId())
                                    .flatMap(candidate -> graders.findByScheduleIdAndClassId(
                                            result.getScheduleId(), candidate.getClassId()))
                                    .ifPresent(assignment -> recipients.add(assignment.getTeacherId())));
                }
            });
        }
        return recipients;
    }
}
