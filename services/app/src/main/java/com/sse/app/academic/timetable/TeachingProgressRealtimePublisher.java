package com.sse.app.academic.timetable;

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
class TeachingProgressRealtimePublisher {
    private final TeachingProgressRepository progress;
    private final UserService users;
    private final RealtimeEventHub realtime;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void publish(TeachingProgressChangedEvent event) {
        progress.findById(event.progressId()).ifPresent(item -> {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("resource", "TEACHING_PROGRESS");
            payload.put("action", event.action());
            payload.put("entityId", item.getId());
            payload.put("teacherId", item.getTeacherId());
            payload.put("classId", item.getClassId());
            payload.put("subjectId", item.getSubjectId());
            payload.put("semesterId", item.getSemesterId());
            payload.put("makeupStatus", item.getMakeupStatus());
            payload.put("occurredAt", Instant.now().toString());

            Set<String> recipients = new LinkedHashSet<>();
            recipients.add(item.getTeacherId());
            users.list("ADMIN", null, null).stream()
                    .map(UserDto::id).forEach(recipients::add);
            recipients.forEach(userId -> realtime.publish(
                    userId, "TEACHING_PROGRESS_UPDATED", payload));
        });
    }
}
