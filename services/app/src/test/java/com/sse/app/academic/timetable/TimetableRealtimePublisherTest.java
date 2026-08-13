package com.sse.app.academic.timetable;

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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TimetableRealtimePublisherTest {
    @Mock TimetableService timetable;
    @Mock UserService users;
    @Mock RealtimeEventHub realtime;

    @Test
    void publishesCommittedVersionToTeachersStudentsAndLinkedParents() {
        TimetableSlot slot = TimetableSlot.builder()
                .id("slot-1").classId("class-1").teacherId("teacher-1")
                .semesterId("semester-1").build();
        UserDto student = new UserDto(
                "student-1", null, null, null, "STUDENT", "ACTIVE", "LOCAL",
                false, null, null, null, null, null, "class-1", null, null,
                null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null);
        when(timetable.list(null, null, "semester-1", null)).thenReturn(List.of(slot));
        when(users.list("STUDENT", null, "class-1")).thenReturn(List.of(student));
        when(users.parentIdsOf("student-1")).thenReturn(List.of("parent-1"));

        new TimetableRealtimePublisher(timetable, users, realtime)
                .publish(new TimetablePublishedEvent("plan-2", "semester-1", 2));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
        verify(realtime).publish(eq("teacher-1"), eq("TIMETABLE_PUBLISHED"), payload.capture());
        verify(realtime).publish(eq("student-1"), eq("TIMETABLE_PUBLISHED"), any());
        verify(realtime).publish(eq("parent-1"), eq("TIMETABLE_PUBLISHED"), any());
        assertEquals("plan-2", payload.getValue().get("entityId"));
        assertEquals(2, payload.getValue().get("version"));
    }
}
