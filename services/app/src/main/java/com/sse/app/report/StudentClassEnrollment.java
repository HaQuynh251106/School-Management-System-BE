package com.sse.app.report;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Column;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "student_class_enrollments",
        uniqueConstraints = @UniqueConstraint(name = "uk_student_enrollment_year",
                columnNames = {"academicYearId", "studentId"}),
        indexes = {
                @Index(name = "idx_student_enrollment_class", columnList = "academicYearId,classId"),
                @Index(name = "idx_student_enrollment_source", columnList = "sourceAcademicYearId,sourceClassId")
        })
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class StudentClassEnrollment {
    @Id
    private String id;
    private String academicYearId;
    private String classId;
    private String studentId;
    private String studentCode;
    private String studentName;
    private String sourceAcademicYearId;
    private String sourceClassId;
    private String sourceSummaryId;
    private String enrollmentType;
    private String status;
    private String enrolledBy;
    private Instant enrolledAt;
    private String revertedBy;
    private Instant revertedAt;
    @Column(columnDefinition = "text")
    private String revertReason;
}
