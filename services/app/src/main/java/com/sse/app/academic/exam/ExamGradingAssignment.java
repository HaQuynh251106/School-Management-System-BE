package com.sse.app.academic.exam;

import jakarta.persistence.Column;
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

/** Giáo viên được giao chấm một môn thi cho một lớp cụ thể. */
@Entity
@Table(name = "exam_grading_assignments",
        uniqueConstraints = @UniqueConstraint(name = "uk_exam_grader_schedule_class",
                columnNames = {"schedule_id", "class_id"}),
        indexes = {
                @Index(name = "idx_exam_grader_teacher", columnList = "teacher_id,schedule_id"),
                @Index(name = "idx_exam_grader_period", columnList = "exam_period_id,schedule_id")
        })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ExamGradingAssignment {
    @Id
    private String id;

    @Column(name = "exam_period_id", nullable = false)
    private String examPeriodId;

    @Column(name = "schedule_id", nullable = false)
    private String scheduleId;

    @Column(name = "class_id", nullable = false)
    private String classId;

    @Column(name = "class_code", nullable = false)
    private String classCode;

    @Column(name = "subject_id", nullable = false)
    private String subjectId;

    @Column(name = "subject_name", nullable = false)
    private String subjectName;

    @Column(name = "teacher_id", nullable = false)
    private String teacherId;

    @Column(name = "teacher_name", nullable = false)
    private String teacherName;

    @Column(name = "assigned_at", nullable = false)
    private Instant assignedAt;

    @Column(name = "assigned_by")
    private String assignedBy;
}
