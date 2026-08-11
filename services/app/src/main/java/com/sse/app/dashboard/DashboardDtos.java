package com.sse.app.dashboard;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public final class DashboardDtos {
    private DashboardDtos() {}

    public record Trend(String direction, Double change, String label) {}

    public record Metric(String key, String label, double value, String format, String hint, String tone,
                         Trend trend) {
        public Metric(String key, String label, double value, String format, String hint, String tone) {
            this(key, label, value, format, hint, tone,
                    new Trend("NONE", null, "Chưa đủ dữ liệu kỳ trước"));
        }
    }

    public record Datum(String label, double value) {}

    public record Chart(String title, String subtitle, String type, String suffix, double max, List<Datum> data) {}

    public record Scope(String role, String objectType, List<String> objectIds) {}

    public record Shortcut(String key, String label, String target, Map<String, String> filters) {}

    public record WidgetError(String widget, String code, String message, boolean retryable) {}

    public record Response(Instant asOf, Scope scope, List<Metric> metrics, List<Chart> charts,
                           List<Shortcut> shortcuts, List<WidgetError> errors) {}
}
