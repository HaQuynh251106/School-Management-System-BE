package com.sse.app.academic.structure;

import jakarta.persistence.*;
import lombok.*;

/** A2: Phòng học (phục vụ xếp TKB + kiểm tra trùng phòng). */
@Entity
@Table(name = "rooms")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Room {
    @Id
    private String id;
    @Column(unique = true)
    private String code;    // P201
    private String name;
    private Integer capacity;
    @Builder.Default
    @Column(nullable = false)
    private boolean supportsMorning = true;
    @Builder.Default
    @Column(nullable = false)
    private boolean supportsAfternoon = true;
}
