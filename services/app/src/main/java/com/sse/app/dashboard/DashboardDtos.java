package com.sse.app.dashboard;

import java.util.List;

/** Dữ liệu tổng hợp cho dashboard, luôn được tính từ dữ liệu nghiệp vụ hiện có. */
public final class DashboardDtos {
    private DashboardDtos() {}

    public record DashboardResponse(List<DashboardMetric> metrics, List<DashboardChart> charts,
                                    List<DashboardShortcut> shortcuts) {
        public DashboardResponse(List<DashboardMetric> metrics, List<DashboardChart> charts) {
            this(metrics, charts, List.of());
        }
    }

    public record DashboardMetric(String key, String label, double value, String format,
                                  String hint, String tone) {}

    public record DashboardChart(String title, String subtitle, String type,
                                 String suffix, double max, List<DashboardDatum> data) {}

    public record DashboardDatum(String label, double value) {}
    public record DashboardShortcut(String key, String label, long count,
                                    String pageId, String filter, String tone) {}
}
