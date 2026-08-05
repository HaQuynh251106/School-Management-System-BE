package com.sse.app.academic.timetable;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "teacher_load_registration_windows")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class TeacherLoadRegistrationWindow {
    @Id
    private String id;
    @Column(nullable = false, unique = true)
    private String semesterId;
    @Column(nullable = false)
    private LocalDate opensOn;
    @Column(nullable = false)
    private LocalDate closesOn;
    @Column(nullable = false)
    private String status;
    private String configuredBy;
    @Column(nullable = false)
    private Instant createdAt;
    @Column(nullable = false)
    private Instant updatedAt;
}
