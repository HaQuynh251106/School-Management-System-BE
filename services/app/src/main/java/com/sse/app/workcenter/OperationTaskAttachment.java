package com.sse.app.workcenter;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;

@Entity @Table(name = "operation_task_attachments")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class OperationTaskAttachment {
    @Id private String id;
    @Column(nullable = false) private String taskId;
    @Column(nullable = false, length = 500) private String fileName;
    @Column(nullable = false, length = 2000) private String fileUrl;
    private String contentType;
    private Long fileSize;
    @Column(nullable = false) private String uploadedBy;
    private Instant uploadedAt;
}
