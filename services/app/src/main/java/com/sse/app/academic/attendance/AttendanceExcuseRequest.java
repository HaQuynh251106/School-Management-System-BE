package com.sse.app.academic.attendance;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "attendance_excuse_requests", indexes = {
        @Index(name = "idx_att_excuse_student", columnList = "studentId"),
        @Index(name = "idx_att_excuse_status", columnList = "status")
})
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class AttendanceExcuseRequest {
    @Id
    private String id;
    private String attendanceRecordId;
    private String studentId;
    private String requestedBy;
    private String requesterRole;
    private String reason;
    private String status;
    private String reviewedBy;
    private String reviewNote;
    private Instant requestedAt;
    private Instant reviewedAt;
}
