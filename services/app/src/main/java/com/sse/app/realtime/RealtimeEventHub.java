package com.sse.app.realtime;

import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Authenticated, user-scoped SSE hub shared by Web and Mobile invalidation events.
 * Domain broadcasts contain no business payload; clients always refetch through
 * their normal RBAC-protected REST endpoint.
 */
@Component
public class RealtimeEventHub {
    private static final long CONNECTION_TIMEOUT_MS = 30 * 60 * 1000L;

    private final Map<String, Map<String, Connection>> connections =
            new ConcurrentHashMap<>();

    public SseEmitter connect(String userId) {
        String connectionId = UUID.randomUUID().toString();
        SseEmitter emitter = new SseEmitter(CONNECTION_TIMEOUT_MS);
        Connection connection = new Connection(connectionId, userId, emitter);
        connections.computeIfAbsent(userId, ignored -> new ConcurrentHashMap<>())
                .put(connectionId, connection);

        emitter.onCompletion(() -> disconnect(connection));
        emitter.onTimeout(() -> disconnect(connection));
        emitter.onError(error -> disconnect(connection));
        send(connection, "CONNECTED", Map.of("connectedAt", Instant.now().toString()));
        return emitter;
    }

    public void publish(String userId, String eventName, Map<String, Object> data) {
        Map<String, Connection> current = connections.get(userId);
        if (current == null) return;
        for (Connection connection : new ArrayList<>(current.values())) {
            send(connection, eventName, data == null ? Map.of() : data);
        }
    }

    public void broadcastInvalidation(String eventName, String resource, String action) {
        Map<String, Object> data = Map.of(
                "resource", resource == null ? "" : resource,
                "action", action == null ? "" : action,
                "changedAt", Instant.now().toString());
        for (String userId : new ArrayList<>(connections.keySet())) {
            publish(userId, eventName, data);
        }
    }

    private void send(Connection connection, String eventName, Object data) {
        try {
            connection.emitter().send(SseEmitter.event()
                    .id(UUID.randomUUID().toString())
                    .name(eventName)
                    .data(data));
        } catch (IOException | IllegalStateException error) {
            disconnect(connection);
        }
    }

    private void disconnect(Connection connection) {
        Map<String, Connection> current = connections.get(connection.userId());
        if (current == null || current.remove(connection.id()) == null) return;
        if (current.isEmpty()) connections.remove(connection.userId(), current);
    }

    private record Connection(String id, String userId, SseEmitter emitter) {}
}
