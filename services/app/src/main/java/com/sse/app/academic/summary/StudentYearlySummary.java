package com.sse.app.academic.summary;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/** Kết quả tổng kết năm, hạnh kiểm và trạng thái lên lớp của một học sinh. */
@Entity
@Table(name = "student_yearly_summaries", uniqueConstraints =
        @UniqueConstraint(columnNames = {"academicYearId", "studentId"}), indexes = {
        @Index(name = "idx_yearly_summary_year", columnList = "academicYearId"),
        @Index(name = "idx_yearly_summary_student", columnList = "studentId")
})
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class StudentYearlySummary {
    @Id
    private String id;
    private String academicYearId;
    private String studentId;
    private String studentName;
    private String classId;
    private Double semesterOneAverage;
    private Double semesterTwoAverage;
    private Double averageScore;
    private String conductGrade;
    private String promotionStatus;
    @Column(length = 2000)
    private String missingRequirements;
    private String nextClassId;
    private Instant updatedAt;
    private Instant finalizedAt;
    private String finalizedBy;
}
