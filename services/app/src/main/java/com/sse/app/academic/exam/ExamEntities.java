package com.sse.app.academic.exam;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;

@Entity
@Table(name = "exam_periods")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
class ExamPeriod {
    @Id private String id;
    private String code;
    private String name;
    private String academicYearId;
    private String semesterId;
    private String examType;
    private String status;
    private String scopeGrades;
    private boolean allowSubjectTeacherProctor;
    private LocalDate startDate;
    private LocalDate endDate;
    private String publishedVersionId;
    private String createdBy;
    private Instant createdAt;
    private Instant updatedAt;
}

@Entity
@Table(name = "exam_schedule_versions")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
class ExamScheduleVersion {
    @Id private String id;
    private String examPeriodId;
    private int versionNo;
    private String status;
    private String basedOnVersionId;
    @Column(length = 1000) private String changeReason;
    private String createdBy;
    private Instant createdAt;
    private Instant contentUpdatedAt;
    private Instant lastValidatedAt;
    private Integer lastValidationErrorCount;
    private Integer lastValidationWarningCount;
    private String publishedBy;
    private Instant publishedAt;
}

@Entity
@Table(name = "exam_sessions")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
class ExamSession {
    @Id private String id;
    private String versionId;
    private String sourceAssessmentPlanId;
    private String sourceTrainingPlanId;
    private Integer sourcePlanVersion;
    private String sourcePlanName;
    private String sourcePlanStatus;
    private String sourceAssessmentName;
    private String sourceAssessmentType;
    private String sourceAssessmentForm;
    private Integer sourceAssessmentWeek;
    private LocalDate sourcePlannedStartDate;
    private LocalDate sourcePlannedEndDate;
    private Instant sourceSyncedAt;
    private Instant sourceUpdatedAt;
    private String subjectId;
    private String gradeLevel;
    private LocalDate examDate;
    private LocalTime startTime;
    private int durationMinutes;
    @Column(length = 1000) private String scheduleDeviationReason;
    @Column(length = 1000) private String notes;
    private Instant createdAt;
    private Instant updatedAt;
}

@Entity
@Table(name = "exam_room_assignments")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
class ExamRoomAssignment {
    @Id private String id;
    private String sessionId;
    private String roomId;
    private int capacitySnapshot;
    private String primaryProctorId;
    private String backupProctorId;
    private Instant createdAt;
    private Instant updatedAt;
}

@Entity
@Table(name = "exam_room_students")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
class ExamRoomStudent {
    @Id private String id;
    private String sessionId;
    private String roomAssignmentId;
    private String studentId;
    private String studentCode;
    private String studentName;
    private String classId;
    private String classCode;
    private int seatNo;
}

@Entity
@Table(name = "exam_teacher_unavailability")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
class ExamTeacherUnavailability {
    @Id private String id;
    private String examPeriodId;
    private String teacherId;
    private LocalDate unavailableDate;
    private LocalDate endDate;
    private LocalTime startTime;
    private LocalTime endTime;
    private String unavailabilityType;
    private String status;
    @Column(length = 1000) private String reason;
    private String createdBy;
    private Instant createdAt;
}
