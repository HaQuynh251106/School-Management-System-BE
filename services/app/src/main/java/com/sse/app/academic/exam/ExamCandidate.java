package com.sse.app.academic.exam;

import jakarta.persistence.*;
import lombok.*;

@Entity @Table(name = "exam_candidates", uniqueConstraints = {
        @UniqueConstraint(name = "uk_exam_candidate_student", columnNames = {"scheduleId", "studentId"}),
        @UniqueConstraint(name = "uk_exam_candidate_no", columnNames = {"scheduleId", "candidateNo"})
})
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class ExamCandidate {
    @Id private String id;
    @Column(nullable = false) private String examPeriodId;
    @Column(nullable = false) private String scheduleId;
    @Column(nullable = false) private String examRoomId;
    @Column(nullable = false) private String studentId;
    @Column(nullable = false) private String studentName;
    private String studentCode;
    @Column(nullable = false) private String classId;
    @Column(nullable = false) private String classCode;
    @Column(nullable = false) private String candidateNo;
    @Column(nullable = false) private int seatNo;
}
