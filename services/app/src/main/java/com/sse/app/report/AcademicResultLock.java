package com.sse.app.report;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "academic_result_locks",
        uniqueConstraints = @UniqueConstraint(name = "uk_result_lock_class_semester",
                columnNames = {"classId", "semesterId"}),
        indexes = @Index(name = "idx_result_lock_year_class", columnList = "academicYearId,classId"))
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class AcademicResultLock {
    @Id
    private String id;
    private String academicYearId;
    private String semesterId;
    private String classId;
    private String lockedBy;
    private Instant lockedAt;
    private String reason;
}
