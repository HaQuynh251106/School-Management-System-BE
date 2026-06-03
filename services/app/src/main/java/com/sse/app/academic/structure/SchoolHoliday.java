package com.sse.app.academic.structure;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;

/** S10: Ngày nghỉ / không dạy — dùng cảnh báo khi xếp TKB (A3). */
@Entity
@Table(name = "school_holidays")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class SchoolHoliday {
    @Id
    private String id;
    private LocalDate date;
    private String name;
    private String description;
}
