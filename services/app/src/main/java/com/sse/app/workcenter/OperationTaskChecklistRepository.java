package com.sse.app.workcenter;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface OperationTaskChecklistRepository extends JpaRepository<OperationTaskChecklistItem, String> {
    List<OperationTaskChecklistItem> findByTaskIdOrderByPositionAscCreatedAtAsc(String taskId);
    long countByTaskId(String taskId);
    long countByTaskIdAndCompletedTrue(String taskId);
}
