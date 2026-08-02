package com.sse.app.academic.timetable;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "lesson_diaries", uniqueConstraints = {
        @UniqueConstraint(name = "uk_lesson_diary_slot_date", columnNames = {"slot_id", "session_date"})
})
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class LessonDiary {
    @Id private String id;
    @Column(name = "slot_id", nullable = false) private String slotId;
    @Column(name = "session_date", nullable = false) private LocalDate sessionDate;
    @Column(name = "teacher_id", nullable = false) private String teacherId;
    @Column(name = "actual_teacher_id", nullable = false) private String actualTeacherId;
    @Column(name = "class_id", nullable = false) private String classId;
    @Column(name = "subject_id") private String subjectId;
    @Column(length = 500) private String topic;
    @Column(name = "lesson_content", length = 4000) private String lessonContent;
    @Column(length = 2000) private String homework;
    @Column(name = "class_note", length = 2000) private String classNote;
    @Column(name = "attendance_summary", length = 1000) private String attendanceSummary;
    @Column(nullable = false) private String status;
    @Column(name = "submitted_at") private Instant submittedAt;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;
    @Version private long version;
}
