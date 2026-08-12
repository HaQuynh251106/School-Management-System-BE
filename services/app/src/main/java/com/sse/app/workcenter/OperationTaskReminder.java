package com.sse.app.workcenter;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;

@Entity @Table(name = "operation_task_reminders")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class OperationTaskReminder {
    @Id private String id;
    @Column(nullable = false) private String taskId;
    @Column(nullable = false) private String reminderType;
    @Column(nullable = false) private Instant scheduledFor;
    @Column(nullable = false) private String status;
    private int attempts;
    @Column(length = 2000) private String lastError;
    private Instant sentAt;
    private Instant createdAt;
    private Instant updatedAt;
}
