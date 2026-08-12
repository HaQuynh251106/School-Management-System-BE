package com.sse.app.workcenter;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;

@Entity @Table(name = "operation_task_checklist_items")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class OperationTaskChecklistItem {
    @Id private String id;
    @Column(nullable = false) private String taskId;
    @Column(nullable = false, length = 500) private String title;
    private boolean completed;
    private int position;
    private String completedBy;
    private Instant completedAt;
    private Instant createdAt;
}
