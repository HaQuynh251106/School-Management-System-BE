package com.sse.app.academic.timetable;

import com.sse.app.identity.UserDto;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TeachingProgressRealtimePublisherTest {
    @Mock TeachingProgressRepository progress;
    @Mock UserService users;
    @Mock RealtimeEventHub realtime;

    @Test
    void reviewedMakeupInvalidatesTheTeacherAndAdmins() {
        var item = TeachingProgress.builder()
                .id("progress-1").teacherId("teacher-1").classId("class-1")
                .subjectId("subject-1").semesterId("semester-1")
                .makeupStatus("APPROVED").build();
        UserDto admin = mock(UserDto.class);
        when(admin.id()).thenReturn("admin-1");
        when(progress.findById("progress-1")).thenReturn(Optional.of(item));
        when(users.list("ADMIN", null, null)).thenReturn(List.of(admin));

        new TeachingProgressRealtimePublisher(progress, users, realtime)
                .publish(new TeachingProgressChangedEvent("progress-1", "MAKEUP_REVIEWED"));

        verify(realtime).publish(eq("teacher-1"), eq("TEACHING_PROGRESS_UPDATED"), any());
        verify(realtime).publish(eq("admin-1"), eq("TEACHING_PROGRESS_UPDATED"), any());
    }
}
