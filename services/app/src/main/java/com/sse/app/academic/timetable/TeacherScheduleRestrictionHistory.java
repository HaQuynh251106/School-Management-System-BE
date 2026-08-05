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

@Entity
@Table(name = "teacher_schedule_restriction_history")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class TeacherScheduleRestrictionHistory {
    @Id
    private String id;
    @Column(nullable = false)
    private String requestId;
    @Column(nullable = false)
    private String semesterId;
    @Column(nullable = false)
    private String teacherId;
    @Column(nullable = false)
    private String action;
    private String previousStatus;
    @Column(nullable = false)
    private String newStatus;
    @Column(length = 2000)
    private String details;
    @Column(nullable = false)
    private String actorId;
    @Column(nullable = false)
    private Instant createdAt;
}
