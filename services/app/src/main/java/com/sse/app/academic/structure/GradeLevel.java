package com.sse.app.academic.structure;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "grade_levels")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class GradeLevel {
    @Id
    private String code;
    private String name;
    private int numericLevel;
    private int displayOrder;
    private boolean active;
}
