package com.sse.app.notification;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sse.app.identity.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NotificationChannelDispatcherTest {
    @Mock NotificationRepository notifications;
    @Mock NotificationDeliveryLogRepository logs;
    @Mock UserService users;
    NotificationChannelDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        dispatcher = new NotificationChannelDispatcher(notifications, logs, users, new ObjectMapper());
        when(notifications.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(logs.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        ReflectionTestUtils.setField(dispatcher, "maxAttempts", 3);
    }

    @Test
    void mockProviderRecordsSuccessfulAttempt() {
        ReflectionTestUtils.setField(dispatcher, "providerMode", "mock");

        Notification row = dispatcher.dispatch("student-1", "GRADE", "EMAIL",
                "Có điểm", "9.0", "GRADE", "grade-1", "/grades", "ACADEMIC");

        assertEquals("SENT", row.getStatus());
        assertEquals(1, row.getAttemptCount());
        ArgumentCaptor<NotificationDeliveryLog> captor = ArgumentCaptor.forClass(NotificationDeliveryLog.class);
        verify(logs).save(captor.capture());
        assertEquals("SENDGRID", captor.getValue().getProvider());
    }

    @Test
    void missingSendGridCredentialRetriesExactlyThreeTimes() {
        ReflectionTestUtils.setField(dispatcher, "providerMode", "real");
        ReflectionTestUtils.setField(dispatcher, "sendGridApiKey", "");

        Notification row = dispatcher.dispatch("student-1", "GRADE", "EMAIL",
                "Có điểm", "9.0", "GRADE", "grade-1", "/grades", "ACADEMIC");

        assertEquals("FAILED", row.getStatus());
        assertEquals(3, row.getAttemptCount());
        verify(logs, times(3)).save(any());
    }
}
