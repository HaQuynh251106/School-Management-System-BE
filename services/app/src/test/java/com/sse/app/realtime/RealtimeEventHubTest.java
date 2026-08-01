package com.sse.app.realtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RealtimeEventHubTest {

    @Test
    void subscriptionsDoNotExpireThroughSpringAsyncTimeout() {
        RealtimeEventHub hub = new RealtimeEventHub();

        var emitter = hub.subscribe("user-1");

        assertEquals(0L, emitter.getTimeout());
        assertEquals(1, hub.activeConnections("user-1"));
    }
}
