package com.sse.app.chat;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatRealtimeServiceTest {

    @Test
    void tracksOnlineUsersOnlyInsideRequestedContactSet() {
        ChatRealtimeService realtime = new ChatRealtimeService();

        realtime.connect("teacher-1", List.of("parent-1", "student-1"));

        assertTrue(realtime.isOnline("teacher-1"));
        assertEquals(Set.of("teacher-1"),
                realtime.onlineAmong(List.of("teacher-1", "teacher-2")));
        assertTrue(realtime.onlineAmong(List.of("parent-1")).isEmpty());
    }

    @Test
    void messageAndReadEventsAcceptMultipleBrowserConnections() {
        ChatRealtimeService realtime = new ChatRealtimeService();
        realtime.connect("teacher-1", List.of("parent-1"));
        realtime.connect("teacher-1", List.of("parent-1"));
        realtime.connect("parent-1", List.of("teacher-1"));

        ChatMessage message = ChatMessage.builder().id("message-1")
                .senderId("parent-1").recipientId("teacher-1")
                .body("Xin chào").createdAt(java.time.Instant.now()).build();

        realtime.publishMessage(message);
        realtime.publishRead("teacher-1", "parent-1", List.of("message-1"));

        assertTrue(realtime.isOnline("teacher-1"));
        assertTrue(realtime.isOnline("parent-1"));
    }
}
