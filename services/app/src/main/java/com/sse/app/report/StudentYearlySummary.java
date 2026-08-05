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
@Table(name = "student_yearly_summaries",
        uniqueConstraints = @UniqueConstraint(name = "uk_yearly_summary_student_year",
                columnNames = {"academicYearId", "studentId"}),
        indexes = {
                @Index(name = "idx_yearly_summary_class", columnList = "academicYearId,classId"),
                @Index(name = "idx_yearly_summary_status", columnList = "status")
        })
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class StudentYearlySummary {
    @Id
    private String id;
    private String academicYearId;
    private String classId;
    private String studentId;
    private String studentCode;
    private String studentName;
    private Double yearlyAverage;
    private Double attendanceRate;
    private String conductGrade;
    private String result;
    private String status;
    private String reason;
    private String reviewedBy;
    private Instant reviewedAt;
    private String finalizedBy;
    private Instant finalizedAt;
    private String progressionStatus;
    private String nextClassId;
    private String progressedBy;
    private Instant progressedAt;
    @Column(columnDefinition = "text")
    private String semesterResultsJson;
    @Column(columnDefinition = "text")
    private String subjectResultsJson;
    private Instant updatedAt;
}
