package com.sse.app.realtime;

import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Kênh sự kiện nhẹ cho chuông thông báo và chat. Dữ liệu nghiệp vụ vẫn được lưu
 * trong PostgreSQL; SSE chỉ báo cho client tải lại dữ liệu mới.
 */
@Service
public class RealtimeEventHub {
    private static final long TIMEOUT_MS = 30 * 60 * 1000L;
    private final Map<String, Set<SseEmitter>> clients = new ConcurrentHashMap<>();

    public SseEmitter subscribe(String userId) {
        SseEmitter emitter = new SseEmitter(TIMEOUT_MS);
        clients.computeIfAbsent(userId, ignored -> ConcurrentHashMap.newKeySet()).add(emitter);
        Runnable remove = () -> remove(userId, emitter);
        emitter.onCompletion(remove);
        emitter.onTimeout(remove);
        emitter.onError(ignored -> remove.run());
        send(emitter, "CONNECTED", Map.of("at", Instant.now().toString()));
        return emitter;
    }

    public void publish(String userId, String type, Map<String, ?> payload) {
        if (userId == null || userId.isBlank()) return;
        for (SseEmitter emitter : clients.getOrDefault(userId, Set.of())) {
            if (!send(emitter, type, payload)) remove(userId, emitter);
        }
    }

    private boolean send(SseEmitter emitter, String type, Map<String, ?> payload) {
        try {
            emitter.send(SseEmitter.event().name(type).data(payload).reconnectTime(3000));
            return true;
        } catch (IOException | IllegalStateException exception) {
            return false;
        }
    }

    private void remove(String userId, SseEmitter emitter) {
        Set<SseEmitter> emitters = clients.get(userId);
        if (emitters == null) return;
        emitters.remove(emitter);
        if (emitters.isEmpty()) clients.remove(userId, emitters);
    }
}
