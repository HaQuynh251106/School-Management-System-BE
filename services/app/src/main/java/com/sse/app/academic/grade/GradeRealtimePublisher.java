package com.sse.app.academic.grade;

import com.sse.app.identity.UserService;
import com.sse.app.realtime.RealtimeEventHub;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
class GradeRealtimePublisher {
    private final GradeRepository grades;
    private final UserService users;
    private final RealtimeEventHub realtime;

    GradeRealtimePublisher(GradeRepository grades, UserService users, RealtimeEventHub realtime) {
        this.grades = grades;
        this.users = users;
        this.realtime = realtime;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void publish(GradeChangedEvent event) {
        grades.findById(event.gradeId()).ifPresent(grade -> {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("resource", "GRADE");
            payload.put("action", event.action());
            payload.put("entityId", grade.getId());
            payload.put("studentId", grade.getStudentId());
            payload.put("subjectId", grade.getSubjectId());
            payload.put("semesterId", grade.getSemesterId());
            payload.put("category", grade.getCategory());
            payload.put("assessmentIndex", grade.getAssessmentIndex());
            payload.put("version", grade.getVersion());
            payload.put("occurredAt", Instant.now().toString());

            String type = "GRADE_" + event.action();
            realtime.publish(grade.getStudentId(), type, payload);
            users.parentIdsOf(grade.getStudentId())
                    .forEach(parentId -> realtime.publish(parentId, type, payload));
        });
    }
}
