package com.sse.app.academic.structure;

import jakarta.persistence.*;
import lombok.*;

/** A2: Lớp học (10A1, 8A1, ...). */
@Entity
@Table(name = "classes")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class SchoolClass {
    @Id
    private String id;
    @Column(unique = true)
    private String code;            // 10A1
    private String name;            // Lớp 10A1
    private String gradeLevel;      // K10
    private String academicYearId;
    private String homeroomTeacherId;
    private int studentCount;
}
