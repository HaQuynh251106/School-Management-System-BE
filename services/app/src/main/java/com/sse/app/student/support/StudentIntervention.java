package com.sse.app.student.support;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "student_interventions", indexes = {
        @Index(name = "idx_intervention_class_status", columnList = "classId,status,updatedAt"),
        @Index(name = "idx_intervention_student_history", columnList = "studentId,createdAt"),
        @Index(name = "idx_intervention_teacher", columnList = "teacherId,updatedAt")
})
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class StudentIntervention {
    @Id private String id;
    @Column(nullable = false) private String studentId;
    @Column(nullable = false) private String classId;
    @Column(nullable = false) private String teacherId;
    @Column(nullable = false, length = 40) private String category;
    @Column(nullable = false, length = 20) private String severity;
    @Column(nullable = false, length = 300) private String title;
    @Column(nullable = false, length = 3000) private String description;
    @Column(length = 3000) private String actionTaken;
    private LocalDate followUpDate;
    @Column(nullable = false, length = 30) private String status;
    @Column(nullable = false) private boolean parentContacted;
    private Instant parentContactedAt;
    private Instant resolvedAt;
    @Column(nullable = false) private Instant createdAt;
    @Column(nullable = false) private Instant updatedAt;
    @Version private long version;
}
