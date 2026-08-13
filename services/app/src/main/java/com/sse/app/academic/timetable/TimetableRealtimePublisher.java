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
class TimetableRealtimePublisher {
    private final TimetableService timetable;
    private final UserService users;
    private final RealtimeEventHub realtime;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void publish(TimetablePublishedEvent event) {
        var slots = timetable.list(null, null, event.semesterId(), null);
        Set<String> classIds = new LinkedHashSet<>();
        Set<String> teacherIds = new LinkedHashSet<>();
        slots.forEach(slot -> {
            if (slot.getClassId() != null) classIds.add(slot.getClassId());
            if (slot.getTeacherId() != null) teacherIds.add(slot.getTeacherId());
        });
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("resource", "TIMETABLE");
        payload.put("action", "PUBLISHED");
        payload.put("entityId", event.planId());
        payload.put("semesterId", event.semesterId());
        payload.put("version", event.versionNo());
        payload.put("occurredAt", Instant.now().toString());

        Set<String> recipients = new LinkedHashSet<>(teacherIds);
        for (String classId : classIds) {
            for (UserDto student : users.list("STUDENT", null, classId)) {
                recipients.add(student.id());
                recipients.addAll(users.parentIdsOf(student.id()));
            }
        }
        recipients.forEach(userId -> realtime.publish(userId, "TIMETABLE_PUBLISHED", payload));
    }
}
