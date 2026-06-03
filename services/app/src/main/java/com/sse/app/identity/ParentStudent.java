package com.sse.app.identity;

import jakarta.persistence.*;
import lombok.*;

/** Quan hệ n-n phụ huynh ↔ học sinh (S1) — nền tảng cho Switch Profile (D1). */
@Entity
@Table(name = "parent_student", uniqueConstraints =
        @UniqueConstraint(columnNames = {"parentId", "studentId"}))
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class ParentStudent {

    @Id
    private String id;

    @Column(nullable = false)
    private String parentId;

    @Column(nullable = false)
    private String studentId;

    /** PH liên hệ chính (nhận hóa đơn/cảnh báo mặc định). */
    private boolean primaryContact;
}
