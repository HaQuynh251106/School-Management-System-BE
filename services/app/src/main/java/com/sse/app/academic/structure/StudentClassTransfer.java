package com.sse.app.academic.structure;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.time.LocalDate;

/** Lịch sử điều chuyển lớp có kiểm soát tại ranh giới học kỳ. */
@Entity
@Table(name = "student_class_transfers", indexes = {
        @Index(name = "idx_class_transfer_year_created", columnList = "academicYearId,createdAt"),
        @Index(name = "idx_class_transfer_student_created", columnList = "studentId,createdAt"),
        @Index(name = "idx_class_transfer_status", columnList = "status")
})
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class StudentClassTransfer {
    @Id private String id;
    private String academicYearId;
    private String studentId;
    private String studentName;
    private String sourceClassId;
    private String sourceClassCode;
    private String targetClassId;
    private String targetClassCode;
    private LocalDate effectiveDate;
    @Column(length = 1000, nullable = false)
    private String reason;
    private String status; // APPLIED | ROLLED_BACK
    private Instant createdAt;
    private String createdBy;
    private String createdByName;
    private Instant rolledBackAt;
    private String rolledBackBy;
    @Column(length = 1000)
    private String rollbackReason;
}
