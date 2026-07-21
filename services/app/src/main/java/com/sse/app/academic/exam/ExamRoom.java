package com.sse.app.academic.exam;

import jakarta.persistence.*;
import lombok.*;

@Entity @Table(name = "exam_rooms", uniqueConstraints = @UniqueConstraint(name = "uk_exam_room_schedule", columnNames = {"scheduleId", "roomCode"}))
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class ExamRoom {
    @Id private String id;
    @Column(nullable = false) private String scheduleId;
    @Column(nullable = false) private String roomCode;
    @Column(nullable = false) private int capacity;
    private String proctorOneId;
    private String proctorOneName;
    private String proctorTwoId;
    private String proctorTwoName;
}
