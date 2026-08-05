package com.sse.app.common;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/** Runtime telemetry for recurring jobs. It never swallows a job failure. */
@Component
public class SchedulerExecutionRegistry {
    private final ConcurrentHashMap<String, MutableState> states = new ConcurrentHashMap<>();

    public void run(String key, String label, Runnable task) {
        MutableState state = states.computeIfAbsent(key, ignored -> new MutableState(label));
        state.label = label;
        state.running = true;
        state.lastStartedAt = Instant.now();
        try {
            task.run();
            state.lastSucceededAt = Instant.now();
            state.lastError = null;
        } catch (RuntimeException exception) {
            state.lastFailedAt = Instant.now();
            state.lastError = abbreviate(exception.getMessage());
            throw exception;
        } finally {
            state.running = false;
        }
    }

    public List<JobState> snapshot() {
        return states.entrySet().stream()
                .map(entry -> entry.getValue().view(entry.getKey()))
                .sorted(Comparator.comparing(JobState::key))
                .toList();
    }

    private static String abbreviate(String value) {
        if (value == null || value.isBlank()) return "Không xác định được nguyên nhân";
        return value.length() <= 500 ? value : value.substring(0, 500);
    }

    private static final class MutableState {
        private volatile String label;
        private volatile boolean running;
        private volatile Instant lastStartedAt;
        private volatile Instant lastSucceededAt;
        private volatile Instant lastFailedAt;
        private volatile String lastError;

        private MutableState(String label) { this.label = label; }

        private JobState view(String key) {
            String status = running ? "RUNNING" : lastError != null ? "FAILED"
                    : lastSucceededAt != null ? "HEALTHY" : "WAITING";
            return new JobState(key, label, status, running, lastStartedAt,
                    lastSucceededAt, lastFailedAt, lastError);
        }
    }

    public record JobState(String key, String label, String status, boolean running,
                           Instant lastStartedAt, Instant lastSucceededAt,
                           Instant lastFailedAt, String lastError) {}
}
