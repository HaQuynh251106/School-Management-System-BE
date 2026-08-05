package com.sse.app.academic.planning;

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
@Table(name = "teacher_subject_capabilities")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class TeacherSubjectCapability {
    @Id private String id;
    private String teacherId;
    private String subjectId;
    private boolean primarySubject;
    private boolean active;
    private Instant createdAt;
}
