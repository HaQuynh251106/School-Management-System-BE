package com.sse.app.event;

import java.time.Instant;
import java.util.Map;

/** Lightweight monolith event envelope. RabbitMQ can replace the publisher later. */
public record DomainEvent(
        String name,
        String actorUserId,
        String entityType,
        String entityId,
        Map<String, Object> payload,
        Instant occurredAt
) {
    public static DomainEvent of(String name, String actorUserId, String entityType,
                                 String entityId, Map<String, Object> payload) {
        return new DomainEvent(name, actorUserId, entityType, entityId,
                payload == null ? Map.of() : Map.copyOf(payload), Instant.now());
    }
}
