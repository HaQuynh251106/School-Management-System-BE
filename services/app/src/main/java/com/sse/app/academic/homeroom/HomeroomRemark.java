package com.sse.app.academic.homeroom;

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
@Table(name = "homeroom_remarks")
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class HomeroomRemark {
    @Id
    private String id;
    private String studentId;
    private String classId;
    private String academicYearId;
    private String semesterId;
    private String teacherId;
    private String body;
    private String status;
    private Instant publishedAt;
    private Instant createdAt;
    private Instant updatedAt;
}
