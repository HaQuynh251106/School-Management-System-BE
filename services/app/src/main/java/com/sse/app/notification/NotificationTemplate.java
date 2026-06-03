package com.sse.app.notification;

import jakarta.persistence.*;
import lombok.*;

/** S12/E2: Mẫu thông báo (Handlebars-like) theo kênh gửi. */
@Entity
@Table(name = "notification_templates")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class NotificationTemplate {
    @Id
    private String id;
    @Column(unique = true)
    private String code;        // ATTENDANCE_ABSENT, GRADE_PUBLISHED, INVOICE_ISSUED ...
    private String name;
    private String channel;     // PUSH | EMAIL | IN_APP
    private String titleTemplate;
    @Column(length = 2000)
    private String bodyTemplate;
    private boolean active;
}
