package com.sse.app.report;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "year_result_publication_history",
        indexes = @Index(name = "idx_year_result_history_class",
                columnList = "academicYearId,classId,occurredAt"))
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class YearResultPublicationHistory {
    @Id
    private String id;
    private String publicationId;
    private String academicYearId;
    private String classId;
    private Integer publicationVersion;
    private String action;
    private Integer studentCount;
    private String actorId;
    @Column(columnDefinition = "text")
    private String reason;
    private Instant occurredAt;
}
