package com.sse.app.realtime;

import com.sse.app.security.CurrentUserHolder;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequiredArgsConstructor
public class RealtimeController {
    private final RealtimeEventHub realtime;

    @GetMapping(value = "/realtime/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events() {
        return realtime.connect(CurrentUserHolder.require().id());
    }
}
