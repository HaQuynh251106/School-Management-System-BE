package com.sse.app.realtime;

import com.sse.app.security.CurrentUserHolder;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
public class RealtimeController {
    private final RealtimeEventHub events;

    public RealtimeController(RealtimeEventHub events) {
        this.events = events;
    }

    @GetMapping(path = "/realtime/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream() {
        return events.subscribe(CurrentUserHolder.require().id());
    }
}
