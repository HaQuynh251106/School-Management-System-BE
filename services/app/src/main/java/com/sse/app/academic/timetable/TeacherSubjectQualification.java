package com.sse.app.academic.timetable;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "teacher_subject_qualifications", uniqueConstraints =
        @UniqueConstraint(name = "uk_teacher_subject_qualification", columnNames = {"teacher_id", "subject_id"}))
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TeacherSubjectQualification {
    @Id
    private String id;
    @Column(nullable = false)
    private String teacherId;
    @Column(nullable = false)
    private String subjectId;
    @Column(nullable = false)
    private Instant createdAt;
}
