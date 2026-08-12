package com.sse.app.workcenter;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;

@Entity @Table(name = "operation_task_history")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class OperationTaskHistory {
    @Id private String id;
    @Column(nullable = false) private String taskId;
    @Column(nullable = false) private String actorId;
    private String actorName;
    @Column(nullable = false) private String action;
    private String fromStatus;
    private String toStatus;
    @Column(length = 4000) private String detail;
    private Instant createdAt;
}
