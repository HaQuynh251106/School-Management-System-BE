package com.sse.app.academic.timetable;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.time.LocalDate;

/** F06/F07: nhật ký tiết thực dạy và đề xuất học bù dùng chung cho Web/Mobile. */
@Entity
@Table(name = "teaching_progress", uniqueConstraints =
        @UniqueConstraint(name = "uk_teaching_progress_slot_date",
                columnNames = {"timetableSlotId", "lessonDate"}), indexes = {
        @Index(name = "idx_teaching_progress_scope",
                columnList = "semesterId,subjectId,classId,lessonDate"),
        @Index(name = "idx_teaching_progress_teacher",
                columnList = "teacherId,lessonDate")
})
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class TeachingProgress {
    @Id private String id;
    @Column(nullable = false) private String timetableSlotId;
    @Column(nullable = false) private String classId;
    @Column(nullable = false) private String classCode;
    @Column(nullable = false) private String subjectId;
    @Column(nullable = false) private String subjectName;
    @Column(nullable = false) private String semesterId;
    @Column(nullable = false) private String teacherId;
    @Column(nullable = false) private String teacherName;
    @Column(nullable = false) private LocalDate lessonDate;
    @Column(nullable = false) private int completedPeriods;
    @Column(nullable = false, length = 1000) private String topic;
    @Column(nullable = false) private String status;
    @Column(length = 1000) private String reason;
    private LocalDate makeupDate;
    @Column(nullable = false) private String makeupStatus;
    @Column(length = 1000) private String reviewNote;
    private String reviewedBy;
    private Instant reviewedAt;
    @Column(nullable = false) private Instant createdAt;
    @Column(nullable = false) private Instant updatedAt;
    @Version private long version;
}
