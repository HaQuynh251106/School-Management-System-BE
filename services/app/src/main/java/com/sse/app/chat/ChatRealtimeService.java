package com.sse.app.chat;

import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Authenticated server-sent events for chat messages, read receipts and presence. */
@Service
public class ChatRealtimeService {
    private static final long CONNECTION_TIMEOUT_MS = 30 * 60 * 1000L;

    private final Map<String, Map<String, Connection>> connections =
            new ConcurrentHashMap<>();

    public SseEmitter connect(String userId, Collection<String> contactIds) {
        String connectionId = UUID.randomUUID().toString();
        SseEmitter emitter = new SseEmitter(CONNECTION_TIMEOUT_MS);
        Set<String> audience = new LinkedHashSet<>(contactIds == null
                ? List.of() : contactIds);
        Connection connection = new Connection(connectionId, userId, audience, emitter);
        connections.computeIfAbsent(userId, ignored -> new ConcurrentHashMap<>())
                .put(connectionId, connection);

        emitter.onCompletion(() -> disconnect(connection));
        emitter.onTimeout(() -> disconnect(connection));
        emitter.onError(error -> disconnect(connection));

        send(connection, "CONNECTED", Map.of(
                "type", "CONNECTED",
                "onlineUserIds", onlineAmong(audience),
                "connectedAt", Instant.now().toString()));
        publishPresence(userId, true, audience);
        return emitter;
    }

    public void publishMessage(ChatMessage message) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("type", "MESSAGE");
        event.put("conversationUserId", message.getSenderId());
        event.put("messageId", message.getId());
        event.put("senderId", message.getSenderId());
        event.put("recipientId", message.getRecipientId());
        event.put("createdAt", message.getCreatedAt());
        publishTo(Set.of(message.getSenderId(), message.getRecipientId()), event);
    }

    public void publishRead(String readerId, String otherId, Collection<String> messageIds) {
        if (messageIds == null || messageIds.isEmpty()) return;
        publishTo(Set.of(readerId, otherId), Map.of(
                "type", "READ",
                "conversationUserId", readerId,
                "readerId", readerId,
                "otherId", otherId,
                "messageIds", List.copyOf(messageIds),
                "readAt", Instant.now().toString()));
    }

    public Set<String> onlineAmong(Collection<String> userIds) {
        Set<String> online = new LinkedHashSet<>();
        if (userIds == null) return online;
        for (String userId : userIds) {
            Map<String, Connection> current = connections.get(userId);
            if (current != null && !current.isEmpty()) online.add(userId);
        }
        return online;
    }

    public boolean isOnline(String userId) {
        return onlineAmong(List.of(userId)).contains(userId);
    }

    private void publishPresence(String userId, boolean online, Set<String> audience) {
        Map<String, Object> event = Map.of(
                "type", "PRESENCE",
                "userId", userId,
                "online", online,
                "changedAt", Instant.now().toString());
        publishTo(audience, event);
    }

    private void publishTo(Collection<String> userIds, Map<String, Object> event) {
        for (String userId : userIds) {
            Map<String, Connection> current = connections.get(userId);
            if (current == null) continue;
            for (Connection connection : new ArrayList<>(current.values())) {
                send(connection, "chat", event);
            }
        }
    }

    private void send(Connection connection, String eventName, Object payload) {
        try {
            connection.emitter().send(SseEmitter.event()
                    .id(UUID.randomUUID().toString())
                    .name(eventName)
                    .data(payload));
        } catch (IOException | IllegalStateException error) {
            disconnect(connection);
        }
    }

    private void disconnect(Connection connection) {
        Map<String, Connection> current = connections.get(connection.userId());
        if (current == null || current.remove(connection.id()) == null) return;
        if (current.isEmpty()) {
            connections.remove(connection.userId(), current);
            publishPresence(connection.userId(), false, connection.contactIds());
        }
    }

    private record Connection(String id, String userId, Set<String> contactIds,
                              SseEmitter emitter) {}
}
