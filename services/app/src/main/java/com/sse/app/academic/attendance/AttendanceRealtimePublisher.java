package com.sse.app.academic.attendance;

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
class AttendanceRealtimePublisher {
    private final AttendanceRepository records;
    private final UserService users;
    private final RealtimeEventHub realtime;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void publish(AttendanceChangedEvent event) {
        for (AttendanceRecord record : records.findAllById(event.recordIds())) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("resource", "ATTENDANCE");
            payload.put("action", "UPSERTED");
            payload.put("entityId", record.getId());
            payload.put("studentId", record.getStudentId());
            payload.put("classId", record.getClassId());
            payload.put("slotId", record.getSlotId());
            payload.put("date", record.getDate().toString());
            payload.put("status", record.getStatus());
            payload.put("occurredAt", Instant.now().toString());
            Set<String> recipients = new LinkedHashSet<>();
            recipients.add(record.getStudentId());
            recipients.addAll(users.parentIdsOf(record.getStudentId()));
            recipients.forEach(userId -> realtime.publish(userId, "ATTENDANCE_UPDATED", payload));
        }
    }
}
