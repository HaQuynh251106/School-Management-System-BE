package com.sse.app.operations;

import com.sse.app.common.SchedulerExecutionRegistry;

import java.time.Instant;
import java.util.List;

public final class AdminOperationsDtos {
    private AdminOperationsDtos() {}

    public record ComponentStatus(String key, String label, String status,
                                  String detail, Instant checkedAt) {}

    public record DeliverySummary(int pending, int retrying, int failed,
                                  int deliveredToday, Instant latestFailureAt) {}

    public record ImportSummary(int completedRuns, Instant latestCompletedAt,
                                String latestDetail) {}

    public record BackupSummary(String status, String latestFile, Instant latestBackupAt,
                                long latestSizeBytes, String detail) {}

    public record StorageSummary(long usableBytes, long totalBytes, long uploadBytes) {}

    public record ActionItem(String key, String severity, String title, String detail,
                             long value, String pageCode) {}

    public record Snapshot(Instant generatedAt, List<ComponentStatus> components,
                           List<SchedulerExecutionRegistry.JobState> scheduledJobs,
                           DeliverySummary deliveries, ImportSummary imports,
                           BackupSummary backup, StorageSummary storage,
                           List<ActionItem> actionItems) {}
}
