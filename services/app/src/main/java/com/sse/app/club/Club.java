package com.sse.app.club;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Column;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "clubs")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Club {
    @Id
    private String id;
    private String code;
    private String name;
    private String description;
    private String schedule;
    private int capacity;
    @Column(name = "fee")
    private long feeAmount;
    private boolean approvalRequired;
    private LocalDate registrationStart;
    private LocalDate registrationEnd;
    private boolean active;
    private Instant createdAt;
}
