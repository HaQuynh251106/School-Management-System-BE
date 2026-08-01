package com.sse.app.dashboard;

import java.util.List;

public final class DashboardDtos {
    private DashboardDtos() {}

    public record Metric(String key, String label, double value, String format, String hint, String tone) {}

    public record Datum(String label, double value) {}

    public record Chart(String title, String subtitle, String type, String suffix, double max, List<Datum> data) {}

    public record Response(List<Metric> metrics, List<Chart> charts) {}
}
