package com.sse.app.academic.attendance;

import com.sse.app.identity.UserService;
import com.sse.app.realtime.RealtimeEventHub;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AttendanceRealtimePublisherTest {
    @Mock AttendanceRepository records;
    @Mock UserService users;
    @Mock RealtimeEventHub realtime;

    @Test
    void publishesCommittedAttendanceOnlyToStudentAndLinkedParents() {
        AttendanceRecord record = AttendanceRecord.builder()
                .id("att-1").studentId("student-1").classId("class-1")
                .slotId("slot-1").date(LocalDate.of(2026, 8, 12))
                .status("LATE").build();
        when(records.findAllById(List.of("att-1"))).thenReturn(List.of(record));
        when(users.parentIdsOf("student-1")).thenReturn(List.of("parent-1"));

        new AttendanceRealtimePublisher(records, users, realtime)
                .publish(new AttendanceChangedEvent(List.of("att-1")));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
        verify(realtime).publish(eq("student-1"), eq("ATTENDANCE_UPDATED"), payload.capture());
        verify(realtime).publish(eq("parent-1"), eq("ATTENDANCE_UPDATED"), any());
        verify(realtime, never()).publish(eq("unrelated"), any(), any());
        assertEquals("att-1", payload.getValue().get("entityId"));
        assertEquals("student-1", payload.getValue().get("studentId"));
        assertEquals("LATE", payload.getValue().get("status"));
    }
}
