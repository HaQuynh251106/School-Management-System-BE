package com.sse.app.academic.structure;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "subject_room_requirements",
        uniqueConstraints = @UniqueConstraint(name = "uq_subject_room_requirement", columnNames = {"subjectId", "roomType"}))
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class SubjectRoomRequirement {
    @Id
    private String id;
    @Column(nullable = false)
    private String subjectId;
    @Column(nullable = false)
    private String roomType;
    @Column(length = 1000)
    private String requiredEquipment;
    @Column(nullable = false)
    private int weeklyPeriods;
    @Column(nullable = false)
    private boolean mandatory;
    @Column(nullable = false)
    private int priority;
    @Column(nullable = false)
    private Instant createdAt;
    @Column(nullable = false)
    private Instant updatedAt;
}
