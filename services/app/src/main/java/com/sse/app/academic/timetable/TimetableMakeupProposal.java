package com.sse.app.academic.timetable;

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
@Table(name = "timetable_makeup_proposals")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class TimetableMakeupProposal {
    @Id
    private String id;
    private String scheduleId;
    private String classId;
    private String subjectId;
    private String teacherId;
    private String roomCode;
    private LocalDate missedDate;
    private int missedPeriodNo;
    private LocalDate proposedDate;
    private Integer proposedPeriodNo;
    private String reason;
    private String status;
    private String reviewNote;
    private String reviewedBy;
    private Instant reviewedAt;
    private Instant createdAt;
}
