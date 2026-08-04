package com.sse.app.academic.conduct;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "conduct_rule_sets")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class ConductRuleSet {
    @Id private String id;
    @Column(nullable = false) private String academicYearId;
    private String semesterId;
    @Column(nullable = false) private String scopeKey;
    @Column(nullable = false) private int versionNo;
    @Column(nullable = false, length = 20) private String status;
    @Column(nullable = false) private double attendanceWeight;
    @Column(nullable = false) private double disciplineWeight;
    @Column(nullable = false) private double responsibilityWeight;
    @Column(nullable = false) private double participationWeight;
    @Column(nullable = false) private double goodMin;
    @Column(nullable = false) private double fairMin;
    @Column(nullable = false) private double averageMin;
    @Column(nullable = false) private int minAttendanceRecords;
    @Column(nullable = false) private int minParticipationEvidence;
    @Column(nullable = false) private String createdBy;
    @Column(nullable = false) private Instant createdAt;
    private Instant activatedAt;
    @Version private long version;
}
