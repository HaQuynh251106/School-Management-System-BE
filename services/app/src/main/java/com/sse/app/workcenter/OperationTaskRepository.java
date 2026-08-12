package com.sse.app.workcenter;

import org.springframework.data.jpa.repository.*;
import java.time.LocalDate;
import java.util.*;

public interface OperationTaskRepository extends JpaRepository<OperationTask, String>, JpaSpecificationExecutor<OperationTask> {
    Optional<OperationTask> findBySourceKey(String sourceKey);
    List<OperationTask> findBySourceTypeAndAutoManagedTrueAndStatusNotIn(String sourceType, Collection<String> statuses);
    List<OperationTask> findByStatusInAndDueDateBefore(Collection<String> statuses, LocalDate date);
    long countByStatus(String status);
    long countByAssignedRoleAndStatus(String role, String status);
}
