package com.sse.app.academic.assignment;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/** B5: Bài tập do GV giao cho lớp. */
@Entity
@Table(name = "assignments", indexes = {
        @Index(name = "idx_asg_class", columnList = "classId"),
        @Index(name = "idx_asg_teacher", columnList = "teacherId")
})
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Assignment {
    @Id
    private String id;
    private String classId;
    private String subjectId;
    private String subjectName;
    private String teacherId;
    private String teacherName;
    private String title;
    @Column(length = 4000)
    private String description;
    /** DRAFT | PUBLISHED | CLOSED */
    private String status;
    private Instant deadline;
    private boolean allowLate;
    private String attachmentFileId;
    private String attachmentName;
    private Instant createdAt;
    private Instant updatedAt;
    @Transient
    private int submissionCount;
    @Transient
    private int studentCount;
}
