package com.sse.app.academic.structure;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;

/** A2: Học kỳ thuộc một năm học. */
@Entity
@Table(name = "semesters")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Semester {
    @Id
    private String id;
    private String academicYearId;
    private String code;        // HK1 | HK2
    private String name;
    private int sequence;
    private LocalDate startDate;
    private LocalDate endDate;
    private String status;      // ACTIVE | PLANNED | CLOSED
}
