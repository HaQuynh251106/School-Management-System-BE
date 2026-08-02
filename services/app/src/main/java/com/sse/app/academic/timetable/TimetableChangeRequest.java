package com.sse.app.academic.timetable;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "timetable_change_requests")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class TimetableChangeRequest {
    @Id private String id;
    @Column(name = "slot_id", nullable = false) private String slotId;
    @Column(name = "occurrence_date", nullable = false) private LocalDate occurrenceDate;
    @Column(name = "request_type", nullable = false) private String requestType;
    @Column(name = "requested_by", nullable = false) private String requestedBy;
    @Column(name = "original_teacher_id", nullable = false) private String originalTeacherId;
    @Column(name = "substitute_teacher_id") private String substituteTeacherId;
    @Column(name = "proposed_date") private LocalDate proposedDate;
    @Column(name = "proposed_period_no") private Integer proposedPeriodNo;
    @Column(name = "proposed_start_time") private String proposedStartTime;
    @Column(name = "proposed_end_time") private String proposedEndTime;
    @Column(name = "proposed_room_code") private String proposedRoomCode;
    @Column(length = 2000, nullable = false) private String reason;
    @Column(nullable = false) private String status;
    @Column(name = "reviewed_by") private String reviewedBy;
    @Column(name = "review_note", length = 2000) private String reviewNote;
    @Column(name = "reviewed_at") private Instant reviewedAt;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;
    @Version private long version;
}
