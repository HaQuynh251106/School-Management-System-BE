package com.sse.app.academic.structure;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;

/** A2: Năm học. */
@Entity
@Table(name = "academic_years")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class AcademicYear {
    @Id
    private String id;
    @Column(unique = true)
    private String code;       // 2025-2026
    private String name;
    private LocalDate startDate;
    private LocalDate endDate;
    private String status;     // ACTIVE | CLOSED | PLANNED
}
