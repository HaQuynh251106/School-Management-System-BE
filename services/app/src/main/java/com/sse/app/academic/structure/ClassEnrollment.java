package com.sse.app.academic.structure;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/** Lịch sử xếp lớp theo năm học; users.classId chỉ là lớp hiện tại để truy vấn nhanh. */
@Entity
@Table(name = "class_enrollments", uniqueConstraints =
        @UniqueConstraint(columnNames = {"academicYearId", "classId", "studentId"}), indexes = {
        @Index(name = "idx_enrollment_student", columnList = "studentId,status"),
        @Index(name = "idx_enrollment_class", columnList = "classId,status")
})
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class ClassEnrollment {
    @Id private String id;
    private String studentId;
    private String classId;
    private String academicYearId;
    private String cohortId;
    private String status;
    private Instant enrolledAt;
    private Instant endedAt;
}
