package com.sse.app.academic.assignment;

import com.sse.app.academic.assignment.AssignmentDtos.RequestResubmissionRequest;
import com.sse.app.academic.structure.StructureService;
import com.sse.app.academic.teaching.TeachingAssignmentService;
import com.sse.app.audit.AuditService;
import com.sse.app.event.DomainEventPublisher;
import com.sse.app.file.FileStorageService;
import com.sse.app.identity.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AssignmentAdvancedServiceTest {
    @Mock private AssignmentRepository assignments;
    @Mock private AssignmentSubmissionRepository submissions;
    @Mock private StructureService structure;
    @Mock private UserService users;
    @Mock private DomainEventPublisher events;
    @Mock private FileStorageService files;
    @Mock private TeachingAssignmentService teachingAssignments;
    @Mock private AuditService audit;
    @Mock private AssignmentSubmissionVersionRepository versions;
    @Mock private SubmissionResubmissionRequestRepository resubmissionRequests;
    @Mock private AssignmentSubmissionExcelExporter excel;

    private AssignmentService service;

    @BeforeEach
    void setUp() {
        service = new AssignmentService(assignments, submissions, structure,
                users, events, files, teachingAssignments, audit,
                versions, resubmissionRequests, excel);
    }

    @Test
    void teacherCanRequestResubmissionOnlyForGradedWork() {
        Assignment assignment = Assignment.builder().id("assignment-1")
                .teacherId("teacher-1").build();
        AssignmentSubmission submission = AssignmentSubmission.builder()
                .id("submission-1").assignmentId("assignment-1")
                .studentId("student-1").status("GRADED").build();
        when(assignments.findById("assignment-1"))
                .thenReturn(Optional.of(assignment));
        when(submissions.findById("submission-1"))
                .thenReturn(Optional.of(submission));
        when(resubmissionRequests
                .findFirstBySubmissionIdAndStatusOrderByRequestedAtDesc(
                        "submission-1", "OPEN")).thenReturn(Optional.empty());
        when(resubmissionRequests.save(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));

        var result = service.requestResubmission(
                "submission-1",
                new RequestResubmissionRequest(
                        "File bị lỗi", Instant.now().plusSeconds(3600)),
                "teacher-1", false);

        assertEquals("OPEN", result.getStatus());
        assertEquals("student-1", result.getStudentId());
    }
}
