package com.sse.app.academic.attendance;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.time.LocalDate;

/** Trạng thái nhắc và mở khóa cho một tiết học theo ngày thực tế. */
@Entity
@Table(name = "attendance_session_access", uniqueConstraints = {
        @UniqueConstraint(name = "uk_att_session_slot_date", columnNames = {"slotId", "sessionDate"})
}, indexes = {
        @Index(name = "idx_att_session_teacher_date", columnList = "teacherId,sessionDate")
})
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class AttendanceSessionAccess {
    @Id
    private String id;
    private String slotId;
    private LocalDate sessionDate;
    private String teacherId;
    private String classId;
    private Instant reminderSentAt;
    @Column(length = 1000)
    private String unlockReason;
    private Instant unlockedAt;
    private String unlockedBy;
    private Instant lateAttendanceSavedAt;
}
