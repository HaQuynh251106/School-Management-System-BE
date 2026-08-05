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

@Entity
@Table(name = "timetable_draft_slots")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class TimetableDraftSlot {
    @Id
    private String id;
    private String scheduleId;
    private String assignmentId;
    private String classId;
    private String subjectId;
    private String subjectName;
    private String teacherId;
    private String teacherName;
    private String roomId;
    private String roomCode;
    private String requiredRoomType;
    private String dayOfWeek;
    private int periodNo;
    private String startTime;
    private String endTime;
    private String semesterId;
    private int lessonIndex;
    private String source;
    private boolean pinned;
    private Instant createdAt;
    private Instant updatedAt;
}
