package com.sse.app.academic.leave;

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
class LeaveRequestRealtimePublisher {
    private final LeaveRequestRepository requests;
    private final UserService users;
    private final RealtimeEventHub realtime;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void publish(LeaveRequestChangedEvent event) {
        requests.findById(event.requestId()).ifPresent(request -> {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("resource", "LEAVE_REQUEST");
            payload.put("action", event.action());
            payload.put("entityId", request.getId());
            payload.put("studentId", request.getStudentId());
            payload.put("classId", request.getClassId());
            payload.put("status", request.getStatus());
            payload.put("occurredAt", Instant.now().toString());

            Set<String> recipients = new LinkedHashSet<>();
            recipients.add(request.getStudentId());
            recipients.addAll(users.parentIdsOf(request.getStudentId()));
            if (request.getHomeroomTeacherId() != null) {
                recipients.add(request.getHomeroomTeacherId());
            }
            recipients.forEach(userId ->
                    realtime.publish(userId, "LEAVE_UPDATED", payload));
        });
    }
}
