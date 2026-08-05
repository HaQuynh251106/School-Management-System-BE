package com.sse.app.academic.timetable;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Entity
@Table(name = "teacher_load_registration_history")
@Getter @NoArgsConstructor @AllArgsConstructor @Builder
public class TeacherLoadRegistrationHistory {
    @Id
    private String id;
    @Column(nullable = false)
    private String registrationId;
    @Column(nullable = false)
    private String semesterId;
    @Column(nullable = false)
    private String teacherId;
    @Column(nullable = false)
    private String action;
    private String previousStatus;
    private String newStatus;
    @Column(length = 2000)
    private String details;
    private String actorId;
    @Column(nullable = false)
    private Instant createdAt;
}
