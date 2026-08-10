package com.sse.app.academic.planning;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "teacher_staffing_policies")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TeacherStaffingPolicy {
    @Id
    private String id;
    private String academicYearId;
    private String schoolType;
    private int weeklyTeachingNorm;
    private int teachingWeeks;
    private BigDecimal teacherClassRatio;
    private Instant createdAt;
    private Instant updatedAt;
}

