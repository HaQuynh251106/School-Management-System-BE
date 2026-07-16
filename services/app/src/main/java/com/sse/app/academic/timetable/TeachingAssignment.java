package com.sse.app.academic.timetable;

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

/** Phân công một giáo viên bộ môn phụ trách một môn của một lớp trong một học kỳ. */
@Entity
@Table(name = "teaching_assignments",
        uniqueConstraints = @UniqueConstraint(name = "uk_teaching_assignment_scope",
                columnNames = {"class_id", "subject_id", "semester_id"}),
        indexes = {
                @Index(name = "idx_ta_teacher_semester", columnList = "teacher_id,semester_id"),
                @Index(name = "idx_ta_class_semester", columnList = "class_id,semester_id")
        })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TeachingAssignment {
    @Id
    private String id;

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

    @Column(name = "semester_id", nullable = false)
    private String semesterId;

    @Column(name = "weekly_periods", nullable = false)
    private int weeklyPeriods;

    @Column(name = "assigned_at", nullable = false)
    private Instant assignedAt;

    @Column(name = "assigned_by")
    private String assignedBy;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
