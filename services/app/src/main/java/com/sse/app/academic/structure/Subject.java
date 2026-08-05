package com.sse.app.academic.structure;

import jakarta.persistence.*;
import lombok.*;

/** A2: Môn học. */
@Entity
@Table(name = "subjects")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Subject {
    @Id
    private String id;
    @Column(unique = true)
    private String code;    // MATH
    private String name;    // Toán
    private double coefficient;
    private String requiredRoomType;
    private String subjectType;
    private String departmentName;
    private String assessmentMethod;
    private String facilityNote;
    private boolean active;
}
