package com.sse.app.realtime;

import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Scheduled;
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
    /**
     * Không để Spring kết thúc request SSE bằng async-timeout. Kết nối được giữ
     * sống bằng heartbeat và sẽ tự loại bỏ ngay khi client đóng hoặc gửi lỗi.
     */
    private static final long NO_TIMEOUT = 0L;
    private final Map<String, Set<SseEmitter>> clients = new ConcurrentHashMap<>();

    public SseEmitter subscribe(String userId) {
        SseEmitter emitter = new SseEmitter(NO_TIMEOUT);
        clients.computeIfAbsent(userId, ignored -> ConcurrentHashMap.newKeySet()).add(emitter);
        Runnable remove = () -> remove(userId, emitter);
        emitter.onCompletion(remove);
        emitter.onTimeout(() -> {
            remove.run();
            emitter.complete();
        });
        emitter.onError(ignored -> remove.run());
        send(emitter, "CONNECTED", Map.of("at", Instant.now().toString()));
        return emitter;
    }

    /** Giữ kết nối qua proxy/load balancer và đồng thời dọn client đã ngắt. */
    @Scheduled(fixedRateString = "${sse.realtime.heartbeat-ms:25000}")
    public void heartbeat() {
        for (var entry : clients.entrySet()) {
            String userId = entry.getKey();
            for (SseEmitter emitter : entry.getValue()) {
                if (!send(emitter, "HEARTBEAT", Map.of("at", Instant.now().toString()))) {
                    remove(userId, emitter);
                }
            }
        }
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

    int activeConnections(String userId) {
        return clients.getOrDefault(userId, Set.of()).size();
    }
}
