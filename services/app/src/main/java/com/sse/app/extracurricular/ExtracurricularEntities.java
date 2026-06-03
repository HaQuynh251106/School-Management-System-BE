package com.sse.app.extracurricular;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/** A5/C6/D5: CLB ngoại khóa + đăng ký. */
public final class ExtracurricularEntities {
    private ExtracurricularEntities() {}
}

@Entity
@Table(name = "clubs")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
class Club {
    @Id
    private String id;
    private String name;
    @Column(length = 2000)
    private String description;
    private int capacity;
    private String schedule;     // "Chiều thứ 4"
    private long fee;
    /** OPEN | CLOSED */
    private String status;
    private Instant createdAt;
}

@Entity
@Table(name = "club_registrations", indexes = {
        @Index(name = "idx_reg_club", columnList = "clubId"),
        @Index(name = "idx_reg_student", columnList = "studentId")
})
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
class ClubRegistration {
    @Id
    private String id;
    private String clubId;
    private String clubName;
    private String studentId;
    private String studentName;
    private String registeredBy;
    /** REGISTERED | CANCELLED */
    private String status;
    private Instant registeredAt;
}
