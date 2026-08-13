package com.sse.app.academic.assignment;

import com.sse.app.identity.UserDto;
import com.sse.app.identity.UserService;
import com.sse.app.realtime.RealtimeEventHub;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AssignmentRealtimePublisherTest {
    @Mock AssignmentRepository assignments;
    @Mock AssignmentSubmissionRepository submissions;
    @Mock UserService users;
    @Mock RealtimeEventHub realtime;

    @Test
    void publishedAssignmentInvalidatesStudentsAndLinkedParents() {
        Assignment assignment = assignment();
        UserDto student = student("student-1");
        when(assignments.findById("asg-1")).thenReturn(Optional.of(assignment));
        when(users.list("STUDENT", null, "class-1")).thenReturn(List.of(student));
        when(users.parentIdsOf("student-1")).thenReturn(List.of("parent-1"));

        new AssignmentRealtimePublisher(assignments, submissions, users, realtime)
                .publish(new AssignmentChangedEvent("asg-1", null, "PUBLISHED"));

        verify(realtime).publish(eq("student-1"), eq("ASSIGNMENT_UPDATED"), any());
        verify(realtime).publish(eq("parent-1"), eq("ASSIGNMENT_UPDATED"), any());
        verify(realtime, never()).publish(eq("teacher-1"), any(), any());
    }

    @Test
    void submissionInvalidatesOnlyAssignedTeacher() {
        Assignment assignment = assignment();
        AssignmentSubmission submission = AssignmentSubmission.builder()
                .id("sub-1").assignmentId("asg-1").studentId("student-1")
                .status("SUBMITTED").build();
        when(assignments.findById("asg-1")).thenReturn(Optional.of(assignment));
        when(submissions.findById("sub-1")).thenReturn(Optional.of(submission));

        new AssignmentRealtimePublisher(assignments, submissions, users, realtime)
                .publish(new AssignmentChangedEvent("asg-1", "sub-1", "SUBMITTED"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
        verify(realtime).publish(eq("teacher-1"), eq("ASSIGNMENT_UPDATED"), payload.capture());
        verify(realtime, never()).publish(eq("student-1"), any(), any());
        assertEquals("sub-1", payload.getValue().get("submissionId"));
    }

    @Test
    void gradeInvalidatesStudentAndParentsButNotUnrelatedUsers() {
        Assignment assignment = assignment();
        AssignmentSubmission submission = AssignmentSubmission.builder()
                .id("sub-1").assignmentId("asg-1").studentId("student-1")
                .status("GRADED").build();
        when(assignments.findById("asg-1")).thenReturn(Optional.of(assignment));
        when(submissions.findById("sub-1")).thenReturn(Optional.of(submission));
        when(users.parentIdsOf("student-1")).thenReturn(List.of("parent-1"));

        new AssignmentRealtimePublisher(assignments, submissions, users, realtime)
                .publish(new AssignmentChangedEvent("asg-1", "sub-1", "GRADED"));

        verify(realtime).publish(eq("student-1"), eq("ASSIGNMENT_UPDATED"), any());
        verify(realtime).publish(eq("parent-1"), eq("ASSIGNMENT_UPDATED"), any());
        verify(realtime, never()).publish(eq("unrelated"), any(), any());
    }

    private Assignment assignment() {
        return Assignment.builder().id("asg-1").classId("class-1")
                .subjectId("subject-1").teacherId("teacher-1")
                .status("PUBLISHED").build();
    }

    private UserDto student(String id) {
        UserDto student = mock(UserDto.class);
        when(student.id()).thenReturn(id);
        return student;
    }
}
