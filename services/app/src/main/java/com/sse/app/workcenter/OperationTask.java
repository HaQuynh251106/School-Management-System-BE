package com.sse.app.workcenter;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "operation_tasks")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class OperationTask {
    @Id private String id;
    @Column(nullable = false) private String title;
    @Column(length = 4000) private String description;
    @Column(nullable = false) private String module;
    @Column(nullable = false) private String priority;
    @Column(nullable = false) private String status;
    private String previousStatus;
    @Column(nullable = false) private String assignedRole;
    private String assignedTo;
    private String assignedToName;
    private String sourceType;
    private String sourceId;
    private String sourceKey;
    private String parentTaskId;
    private LocalDate dueDate;
    @Column(length = 4000) private String resolution;
    @Column(length = 2000) private String rejectionReason;
    @Column(length = 2000) private String delayReason;
    @Column(nullable = false) private String createdBy;
    private String creatorName;
    private Instant createdAt;
    private Instant updatedAt;
    private Instant resolvedAt;
    private Instant acceptedAt;
    private Instant startedAt;
    private Instant submittedAt;
    private Instant completedAt;
    private Instant rejectedAt;
    private Instant snoozedUntil;
    private Instant lastEscalatedAt;
    @Builder.Default private int progressPercent = 0;
    @Builder.Default private int priorityScore = 0;
    @Builder.Default private String slaLevel = "ON_TRACK";
    @Builder.Default private boolean autoManaged = false;
    private Boolean completedOnTime;
    @Version private long version;
}
