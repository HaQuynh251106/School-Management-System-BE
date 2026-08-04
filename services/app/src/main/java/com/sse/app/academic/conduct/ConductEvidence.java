package com.sse.app.academic.conduct;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "conduct_evidence")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class ConductEvidence {
    @Id private String id;
    @Column(nullable = false) private String academicYearId;
    private String semesterId;
    @Column(nullable = false) private String studentId;
    @Column(nullable = false) private String classId;
    @Column(nullable = false) private String teacherId;
    @Column(nullable = false, length = 30) private String category;
    @Column(nullable = false) private double impactPoints;
    @Column(nullable = false, length = 300) private String title;
    @Column(length = 3000) private String description;
    @Column(nullable = false) private LocalDate occurredOn;
    @Column(nullable = false, length = 30) private String sourceType;
    @Column(nullable = false, length = 160) private String sourceRef;
    @Column(nullable = false) private Instant createdAt;
    @Column(nullable = false) private Instant updatedAt;
    @Version private long version;
}
