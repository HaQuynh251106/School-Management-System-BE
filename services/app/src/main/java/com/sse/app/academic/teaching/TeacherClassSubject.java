package com.sse.app.academic.teaching;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "teacher_class_subjects", indexes = {
        @Index(name = "idx_tcs_teacher", columnList = "teacherId"),
        @Index(name = "idx_tcs_class", columnList = "classId"),
        @Index(name = "idx_tcs_semester", columnList = "semesterId"),
        @Index(name = "idx_tcs_scope", columnList = "classId,subjectId,semesterId")
})
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class TeacherClassSubject {
    @Id
    private String id;

    @Column(nullable = false)
    private String teacherId;
    private String teacherName;

    @Column(nullable = false)
    private String classId;
    private String classCode;

    @Column(nullable = false)
    private String subjectId;
    private String subjectName;

    @Column(nullable = false)
    private String semesterId;

    /** Planned periods for this class/subject in one teaching week. */
    private Integer weeklyPeriods;

    /** Periods per week that must use the subject's specialized room type. */
    private Integer specializedRoomPeriods;

    @Column(nullable = false)
    private String status;

    private Instant createdAt;
    private Instant updatedAt;
}
