package com.sse.app.academic.exam;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "exam_seating_plan_items")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class ExamSeatingPlanItem {
    @Id private String id;
    @Column(nullable = false) private String planId;
    @Column(nullable = false) private String rowType;
    @Column(nullable = false) private String studentId;
    @Column(nullable = false) private String studentName;
    private String studentCode;
    @Column(nullable = false) private String classId;
    @Column(nullable = false) private String classCode;
    @Column(nullable = false) private String candidateNo;
    private String examRoomId;
    private String roomCode;
    private Integer seatNo;
}
