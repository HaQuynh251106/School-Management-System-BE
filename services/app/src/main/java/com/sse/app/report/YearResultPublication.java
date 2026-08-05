package com.sse.app.report;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Column;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "year_result_publications",
        uniqueConstraints = @UniqueConstraint(name = "uk_year_result_publication_class",
                columnNames = {"academicYearId", "classId"}),
        indexes = @Index(name = "idx_year_result_publication_status",
                columnList = "academicYearId,status"))
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class YearResultPublication {
    @Id
    private String id;
    private String academicYearId;
    private String classId;
    private String status;
    private Integer studentCount;
    private Integer publicationVersion;
    @Column(columnDefinition = "text")
    private String lastPublishReason;
    private String publishedBy;
    private Instant publishedAt;
    private String withdrawnBy;
    private Instant withdrawnAt;
    @Column(columnDefinition = "text")
    private String withdrawalReason;
    private Instant updatedAt;
}
