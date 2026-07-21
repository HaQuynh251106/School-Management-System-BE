package com.sse.app.academic.exam;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDate;
import java.util.*;

@Entity @Table(name = "exam_schedules", indexes = @Index(name = "idx_exam_schedule_period", columnList = "examPeriodId"))
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class ExamSchedule {
    @Id private String id;
    @Column(nullable = false) private String examPeriodId;
    @Column(nullable = false) private String subjectId;
    @Column(nullable = false) private String subjectName;
    @Column(nullable = false) private LocalDate examDate;
    @Column(nullable = false) private String startTime;
    @Column(nullable = false) private int durationMinutes;
    @Column(length = 1000) private String notes;
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "exam_schedule_classes", joinColumns = @JoinColumn(name = "schedule_id"))
    @Column(name = "class_id", nullable = false)
    @Builder.Default
    private Set<String> classIds = new LinkedHashSet<>();
}
