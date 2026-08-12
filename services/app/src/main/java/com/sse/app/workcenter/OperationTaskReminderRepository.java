package com.sse.app.workcenter;

import org.springframework.data.jpa.repository.JpaRepository;
import java.time.Instant;
import java.util.List;

public interface OperationTaskReminderRepository extends JpaRepository<OperationTaskReminder, String> {
    boolean existsByTaskIdAndReminderTypeAndScheduledFor(String taskId, String reminderType, Instant scheduledFor);
    List<OperationTaskReminder> findTop100ByStatusInAndScheduledForLessThanEqualOrderByScheduledForAsc(List<String> statuses, Instant now);
}
