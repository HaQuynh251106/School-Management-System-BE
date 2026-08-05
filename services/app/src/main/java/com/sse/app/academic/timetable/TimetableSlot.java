package com.sse.app.academic.timetable;

import jakarta.persistence.*;
import lombok.*;

/** A3/B2/C2: Một ô thời khóa biểu (lớp × thứ × tiết). */
@Entity
@Table(name = "timetable_slots", indexes = {
        @Index(name = "idx_tt_class", columnList = "classId"),
        @Index(name = "idx_tt_teacher", columnList = "teacherId")
})
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class TimetableSlot {
    @Id
    private String id;
    private String classId;
    private String subjectId;
    private String subjectName;
    private String teacherId;
    private String teacherName;
    private String roomCode;
    private String dayOfWeek;   // MON | TUE | WED | THU | FRI | SAT
    private int periodNo;
    private String startTime;   // "07:00"
    private String endTime;     // "07:45"
    private String semesterId;
    private String sourceScheduleId;
}
