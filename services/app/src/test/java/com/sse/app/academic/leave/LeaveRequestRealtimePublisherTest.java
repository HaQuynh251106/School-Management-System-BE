package com.sse.app.academic.leave;

import com.sse.app.identity.UserService;
import com.sse.app.realtime.RealtimeEventHub;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LeaveRequestRealtimePublisherTest {
    @Mock LeaveRequestRepository requests;
    @Mock UserService users;
    @Mock RealtimeEventHub realtime;

    @Test
    void leaveChangeInvalidatesStudentParentsAndHomeroomTeacherOnly() {
        var request = LeaveRequest.builder()
                .id("leave-1").studentId("student-1").classId("class-1")
                .homeroomTeacherId("teacher-1").status("PENDING_HOMEROOM")
                .build();
        when(requests.findById("leave-1")).thenReturn(Optional.of(request));
        when(users.parentIdsOf("student-1")).thenReturn(List.of("parent-1"));

        new LeaveRequestRealtimePublisher(requests, users, realtime)
                .publish(new LeaveRequestChangedEvent("leave-1", "PARENT_CONFIRMED"));

        verify(realtime).publish(eq("student-1"), eq("LEAVE_UPDATED"), any());
        verify(realtime).publish(eq("parent-1"), eq("LEAVE_UPDATED"), any());
        verify(realtime).publish(eq("teacher-1"), eq("LEAVE_UPDATED"), any());
        verify(realtime, never()).publish(eq("unrelated"), any(), any());
    }
}
