package com.sse.app.workcenter;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface OperationTaskCommentRepository extends JpaRepository<OperationTaskComment, String> {
    List<OperationTaskComment> findByTaskIdOrderByCreatedAtAsc(String taskId);
}
