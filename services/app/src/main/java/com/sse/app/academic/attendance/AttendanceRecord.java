package com.sse.app.academic.attendance;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;

/** B3/C3/D2: Một lượt điểm danh của 1 HS trong 1 tiết. */
@Entity
@Table(name = "attendance_records", uniqueConstraints = {
        @UniqueConstraint(name = "uk_att_slot_date_student", columnNames = {"slotId", "date", "studentId"})
}, indexes = {
        @Index(name = "idx_att_student", columnList = "studentId"),
        @Index(name = "idx_att_class_date", columnList = "classId,date")
})
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class AttendanceRecord {
    @Id
    private String id;
    private String studentId;
    private String classId;
    private String slotId;
    private LocalDate date;
    /** PRESENT | ABSENT_EXCUSED | ABSENT_UNEXCUSED | LATE */
    private String status;
    private String note;
    private String subjectName;
    private Integer periodNo;
    @Version @Builder.Default
    private Long version = 0L;
}
