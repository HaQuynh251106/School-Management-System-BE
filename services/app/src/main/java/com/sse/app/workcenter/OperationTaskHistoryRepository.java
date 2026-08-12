package com.sse.app.workcenter;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface OperationTaskHistoryRepository extends JpaRepository<OperationTaskHistory, String> {
    List<OperationTaskHistory> findByTaskIdOrderByCreatedAtDesc(String taskId);
}
