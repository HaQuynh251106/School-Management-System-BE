package com.sse.app.club;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Column;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "club_registrations",
        uniqueConstraints = @UniqueConstraint(name = "uk_club_registration_student", columnNames = {"clubId", "studentId"}),
        indexes = {
                @Index(name = "idx_club_registration_club_status", columnList = "clubId,status"),
                @Index(name = "idx_club_registration_student", columnList = "studentId")
        })
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class ClubRegistration {
    @Id
    private String id;
    private String clubId;
    private String studentId;
    @Column(name = "registered_by")
    private String requestedBy;
    /** PENDING | WAITLIST | APPROVED | REJECTED | CANCELLED */
    private String status;
    private String invoiceId;
    private String decisionNote;
    @Column(name = "registered_at")
    private Instant createdAt;
    private Instant decidedAt;
    private Instant cancelledAt;
    @Version
    private long version;
}
