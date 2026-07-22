package com.sse.app.academic.structure;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/** A2: Lớp học (10A1, 8A1, ...). */
@Entity
@Table(name = "classes", uniqueConstraints =
        @UniqueConstraint(name = "uk_classes_year_code", columnNames = {"academicYearId", "code"}))
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class SchoolClass {
    @Id
    private String id;
    private String code;            // 10A1
    private String name;            // Lớp 10A1
    private String gradeLevel;      // K10
    @Column(nullable = false)
    private String academicYearId;
    private String homeroomTeacherId;
    private String homeroomTeacherName;
    private Instant homeroomAssignedAt;
    private String homeroomAssignedBy;
    @Builder.Default
    @Column(nullable = false)
    private int capacity = 45;
    private int studentCount;
}
