package com.sse.app.academic.grade;

import jakarta.persistence.*;
import lombok.*;

/** A4/S6: Cấu hình loại điểm + hệ số (miệng/15p/giữa kỳ/cuối kỳ). */
@Entity
@Table(name = "exam_categories")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class ExamCategory {
    @Id
    private String id;
    @Column(unique = true)
    private String code;        // ORAL | 15M | MID | FINAL
    private String name;        // Miệng | 15 phút | Giữa kỳ | Cuối kỳ
    private double weight;      // hệ số (vd 1, 1, 2, 3)
    /** Số đầu điểm bắt buộc của loại này trong một học kỳ. */
    @Builder.Default
    private int requiredCount = 1;
}
