package com.sse.app.workcenter;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;

@Entity @Table(name = "operation_task_comments")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class OperationTaskComment {
    @Id private String id;
    @Column(nullable = false) private String taskId;
    @Column(nullable = false) private String authorId;
    private String authorName;
    @Column(nullable = false, length = 4000) private String body;
    private Instant createdAt;
}
