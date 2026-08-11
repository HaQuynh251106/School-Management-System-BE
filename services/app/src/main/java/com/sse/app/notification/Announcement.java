package com.sse.app.notification;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.time.LocalDate;

/** B6a/A: Thông báo/broadcast theo nhóm đối tượng. */
@Entity
@Table(name = "announcements")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Announcement {
    @Id
    private String id;
    private String title;
    @Column(length = 4000)
    private String body;
    private Instant createdAt;
    private String createdBy;
    private String audience;    // ALL | PARENT | STUDENT | TEACHER | CLASS:<classId>
    private String category;    // GENERAL | HOLIDAY | GRADE | EVENT | STUDENT_STATUS | ATTENDANCE | PARENT_MEETING
    private String priority;    // NORMAL | IMPORTANT | URGENT
    private String status;      // SENT
    private int recipientCount;
    @Column(length = 100)
    private String idempotencyKey;
    private LocalDate holidayStartDate;
    private LocalDate holidayEndDate;
}
