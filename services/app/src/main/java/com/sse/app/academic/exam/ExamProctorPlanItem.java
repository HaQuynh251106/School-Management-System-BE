package com.sse.app.academic.exam;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "exam_proctor_plan_items")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class ExamProctorPlanItem {
    @Id private String id;
    @Column(nullable = false) private String planId;
    @Column(nullable = false) private String roomId;
    @Column(nullable = false) private String roomCode;
    @Column(nullable = false) private boolean locked;
    private String previousProctorOneId;
    private String previousProctorOneName;
    private String previousProctorTwoId;
    private String previousProctorTwoName;
    private String proposedProctorOneId;
    private String proposedProctorOneName;
    private String proposedProctorTwoId;
    private String proposedProctorTwoName;
    @Column(nullable = false) private String status;
    @Column(length = 1000) private String message;
    private Integer proctorOneDutyCount;
    private Integer proctorTwoDutyCount;
}
