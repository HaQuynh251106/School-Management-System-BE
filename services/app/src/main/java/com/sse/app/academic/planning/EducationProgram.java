package com.sse.app.academic.planning;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "education_programs")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class EducationProgram {
    @Id private String id;
    private String code;
    private String name;
    private int startYear;
    private String description;
    private String status;
    private Instant createdAt;
    private Instant updatedAt;
}
