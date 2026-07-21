package com.sse.app.academic.exam;

import jakarta.persistence.*;
import lombok.*;
import java.time.*;

@Entity @Table(name = "exam_periods")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class ExamPeriod {
    @Id private String id;
    @Column(nullable = false, unique = true) private String code;
    @Column(nullable = false) private String name;
    @Column(nullable = false) private String academicYearId;
    @Column(nullable = false) private String semesterId;
    private String gradeLevel;
    @Column(nullable = false) private LocalDate startDate;
    @Column(nullable = false) private LocalDate endDate;
    @Column(nullable = false) private String status;
    @Builder.Default @Column(nullable = false) private boolean scoreEntryLocked = false;
    @Builder.Default @Column(nullable = false) private boolean schedulePublished = false;
    @Builder.Default @Column(nullable = false) private int scheduleRevision = 0;
    private Instant schedulePublishedAt;
    private String schedulePublishedBy;
    private Instant confirmedAt;
    private String confirmedBy;
    @Column(nullable = false) private Instant createdAt;
    private String createdBy;
    @Column(nullable = false) private Instant updatedAt;
}
