package com.sse.app.academic.assignment;

import com.sse.app.academic.assignment.AssignmentDtos.CreateAssignmentRequest;
import com.sse.app.academic.assignment.AssignmentDtos.SubmitRequest;
import com.sse.app.academic.structure.StructureService;
import com.sse.app.academic.teaching.TeachingAssignmentService;
import com.sse.app.audit.AuditService;
import com.sse.app.common.ApiException;
import com.sse.app.event.DomainEventPublisher;
import com.sse.app.file.FileStorageService;
import com.sse.app.identity.User;
import com.sse.app.identity.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AssignmentServiceTest {

    @Mock private AssignmentRepository assignments;
    @Mock private AssignmentSubmissionRepository submissions;
    @Mock private StructureService structure;
    @Mock private UserService users;
    @Mock private DomainEventPublisher events;
    @Mock private FileStorageService files;
    @Mock private TeachingAssignmentService teachingAssignments;
    @Mock private AuditService audit;

    private AssignmentService service;

    @BeforeEach
    void setUp() {
        service = new AssignmentService(assignments, submissions, structure, users, events,
                files, teachingAssignments, audit);
    }

    @Test
    void createRejectsMissingTitle() {
        CreateAssignmentRequest request = new CreateAssignmentRequest(
                null, "class-1", "subject-1", "  ", null, null, false,
                null, false, "file-1");

        ApiException error = assertThrows(ApiException.class,
                () -> service.create(request, "teacher-1"));

        assertEquals(HttpStatus.BAD_REQUEST, error.getStatus());
        assertEquals("Bắt buộc nhập tên đề bài", error.getMessage());
        verify(assignments, never()).save(any());
    }

    @Test
    void createRejectsMissingAssignmentFile() {
        CreateAssignmentRequest request = new CreateAssignmentRequest(
                null, "class-1", "subject-1", "Bài tập", null, null, false,
                null, false, null);

        ApiException error = assertThrows(ApiException.class,
                () -> service.create(request, "teacher-1"));

        assertEquals(HttpStatus.BAD_REQUEST, error.getStatus());
        assertEquals("Bắt buộc đính kèm file đề bài", error.getMessage());
        verify(assignments, never()).save(any());
    }

    @Test
    void createRejectsClassSubjectOutsideTeacherAssignment() {
        CreateAssignmentRequest request = new CreateAssignmentRequest(
                null, "class-1", "subject-1", "Assignment", null, null, false,
                null, false, "file-1");
        when(teachingAssignments.teacherAssignedToClassSubject(
                "teacher-1", "class-1", "subject-1")).thenReturn(false);

        ApiException error = assertThrows(ApiException.class,
                () -> service.create(request, "teacher-1"));

        assertEquals(HttpStatus.FORBIDDEN, error.getStatus());
        assertEquals("Ban chi co the giao bai cho lop va mon dang duoc phan cong", error.getMessage());
        verify(files, never()).requireReadyOwnedFile(any(), any(), any());
        verify(assignments, never()).save(any());
    }

    @Test
    void publishRejectsLegacyDraftWithoutAssignmentFile() {
        Assignment draft = Assignment.builder()
                .id("assignment-1")
                .teacherId("teacher-1")
                .status("DRAFT")
                .build();
        when(assignments.findById("assignment-1")).thenReturn(Optional.of(draft));

        ApiException error = assertThrows(ApiException.class,
                () -> service.publish("assignment-1", "teacher-1", false));

        assertEquals(HttpStatus.BAD_REQUEST, error.getStatus());
        assertEquals("Bắt buộc đính kèm file đề bài trước khi phát hành", error.getMessage());
        verify(assignments, never()).save(any());
    }

    @Test
    void publishRejectsDraftAfterTeachingAssignmentWasRemoved() {
        Assignment draft = Assignment.builder()
                .id("assignment-1")
                .teacherId("teacher-1")
                .classId("class-1")
                .subjectId("subject-1")
                .attachmentFileId("file-1")
                .status("DRAFT")
                .build();
        when(assignments.findById("assignment-1")).thenReturn(Optional.of(draft));
        when(teachingAssignments.teacherAssignedToClassSubject(
                "teacher-1", "class-1", "subject-1")).thenReturn(false);

        ApiException error = assertThrows(ApiException.class,
                () -> service.publish("assignment-1", "teacher-1", false));

        assertEquals(HttpStatus.FORBIDDEN, error.getStatus());
        verify(assignments, never()).save(any());
    }

    @Test
    void firstSubmissionRejectsNoteWithoutFile() {
        preparePublishedAssignmentAndStudent();
        when(submissions.findByAssignmentIdAndStudentId("assignment-1", "student-1"))
                .thenReturn(Optional.empty());

        ApiException error = assertThrows(ApiException.class,
                () -> service.submit("assignment-1", "student-1",
                        new SubmitRequest("Chỉ có ghi chú", null, null)));

        assertEquals(HttpStatus.BAD_REQUEST, error.getStatus());
        assertEquals("Bắt buộc đính kèm file bài làm trước khi nộp", error.getMessage());
        verify(submissions, never()).save(any());
    }

    @Test
    void resubmissionMayKeepExistingFileWhenUpdatingNote() {
        preparePublishedAssignmentAndStudent();
        AssignmentSubmission existing = AssignmentSubmission.builder()
                .id("submission-1")
                .assignmentId("assignment-1")
                .studentId("student-1")
                .attachmentFileId("file-1")
                .attachmentName("bai-lam.pdf")
                .build();
        when(submissions.findByAssignmentIdAndStudentId("assignment-1", "student-1"))
                .thenReturn(Optional.of(existing));
        when(users.fullNameOf("student-1")).thenReturn("Học sinh 1");
        when(submissions.save(any(AssignmentSubmission.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        AssignmentSubmission saved = service.submit("assignment-1", "student-1",
                new SubmitRequest("Cập nhật ghi chú", null, null));

        assertEquals("file-1", saved.getAttachmentFileId());
        assertEquals("Cập nhật ghi chú", saved.getContent());
        assertEquals("SUBMITTED", saved.getStatus());
    }

    private void preparePublishedAssignmentAndStudent() {
        Assignment assignment = Assignment.builder()
                .id("assignment-1")
                .classId("class-1")
                .status("PUBLISHED")
                .build();
        User student = User.builder()
                .id("student-1")
                .classId("class-1")
                .role("STUDENT")
                .status("ACTIVE")
                .build();
        when(assignments.findById("assignment-1")).thenReturn(Optional.of(assignment));
        when(users.getById("student-1")).thenReturn(student);
    }
}
