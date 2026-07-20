package com.sse.app.academic.leave;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "leave_requests", indexes = {
        @Index(name = "idx_leave_student", columnList = "studentId,createdAt"),
        @Index(name = "idx_leave_class_status", columnList = "classId,status")
})
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class LeaveRequest {
    @Id
    private String id;
    @Column(nullable = false)
    private String studentId;
    private String studentName;
    @Column(nullable = false)
    private String classId;
    private String classCode;
    @Column(nullable = false)
    private LocalDate startDate;
    @Column(nullable = false)
    private LocalDate endDate;
    @Column(nullable = false, length = 2000)
    private String reason;
    @Column(nullable = false)
    private String status;
    private String parentId;
    private String parentName;
    private Instant parentConfirmedAt;
    private String homeroomTeacherId;
    private String homeroomTeacherName;
    private Instant decidedAt;
    @Column(length = 1000)
    private String decisionNote;
    @Column(nullable = false)
    private Instant createdAt;
    private Instant updatedAt;
}
