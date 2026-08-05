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
    // Mã lớp chỉ duy nhất trong một năm học: 10A1 có thể xuất hiện lại ở năm sau.
    // Unique index (academic_year_id, code) được áp dụng bởi db/structure-schema-patch.sql.
    @Column(nullable = false)
    private String code;            // 10A1
    private String name;            // Lớp 10A1
    private String gradeLevel;      // K10
    private String academicYearId;
    private String homeroomTeacherId;
    private String homeRoomId;
    private int studentCount;
    /** Maximum active students. Null means the school default (45). */
    private Integer maxStudents;
    private Integer expectedStudentCount;
    private String status;
}
