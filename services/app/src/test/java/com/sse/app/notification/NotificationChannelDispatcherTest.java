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
    @Mock FcmAccessTokenProvider fcmTokens;
    NotificationChannelDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        dispatcher = new NotificationChannelDispatcher(
                notifications, logs, users, new ObjectMapper(), fcmTokens);
        ReflectionTestUtils.setField(dispatcher, "maxAttempts", 3);
    }

    @Test
    void mockProviderRecordsSuccessfulAttempt() {
        mockPersistence();
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
        mockPersistence();
        ReflectionTestUtils.setField(dispatcher, "providerMode", "real");
        ReflectionTestUtils.setField(dispatcher, "emailProvider", "sendgrid");
        ReflectionTestUtils.setField(dispatcher, "sendGridApiKey", "");

        Notification row = dispatcher.dispatch("student-1", "GRADE", "EMAIL",
                "Có điểm", "9.0", "GRADE", "grade-1", "/grades", "ACADEMIC");

        assertEquals("FAILED", row.getStatus());
        assertEquals(3, row.getAttemptCount());
        verify(logs, times(3)).save(any());
    }

    @Test
    void missingSmtpCredentialRetriesAndLogsSmtpProvider() {
        mockPersistence();
        ReflectionTestUtils.setField(dispatcher, "providerMode", "real");
        ReflectionTestUtils.setField(dispatcher, "emailProvider", "smtp");
        ReflectionTestUtils.setField(dispatcher, "smtpHost", "smtp.gmail.com");
        ReflectionTestUtils.setField(dispatcher, "smtpPort", 587);
        ReflectionTestUtils.setField(dispatcher, "smtpUsername", "sender@example.com");
        ReflectionTestUtils.setField(dispatcher, "smtpPassword", "");
        ReflectionTestUtils.setField(dispatcher, "smtpFromEmail", "sender@example.com");

        Notification row = dispatcher.dispatch("student-1", "PASSWORD_RESET", "EMAIL",
                "Đặt lại mật khẩu", "body", "PASSWORD_RESET", "token-1", "/reset", "AUTH");

        assertEquals("FAILED", row.getStatus());
        assertEquals(3, row.getAttemptCount());
        ArgumentCaptor<NotificationDeliveryLog> captor = ArgumentCaptor.forClass(NotificationDeliveryLog.class);
        verify(logs, times(3)).save(captor.capture());
        assertEquals("SMTP", captor.getAllValues().get(0).getProvider());
    }

    @Test
    void providerStatusDoesNotExposeCredentials() {
        ReflectionTestUtils.setField(dispatcher, "providerMode", "real");
        ReflectionTestUtils.setField(dispatcher, "emailProvider", "sendgrid");
        ReflectionTestUtils.setField(dispatcher, "sendGridApiKey", "secret-key");
        ReflectionTestUtils.setField(dispatcher, "sendGridFromEmail", "verified@example.com");
        ReflectionTestUtils.setField(dispatcher, "fcmProjectId", "school-project");
        when(fcmTokens.isConfigured()).thenReturn(true);
        when(fcmTokens.source()).thenReturn("SERVICE_ACCOUNT");

        var status = dispatcher.providerStatus();

        assertEquals("REAL", status.mode());
        assertEquals(true, status.sendGridConfigured());
        assertEquals("verified@example.com", status.sendGridFromEmail());
        assertEquals(true, status.fcmConfigured());
        assertEquals("SERVICE_ACCOUNT", status.fcmCredentialSource());
        assertEquals("school-project", status.fcmProjectId());
    }

    @Test
    void providerStatusReportsSmtpConfiguredWithoutExposingPassword() {
        ReflectionTestUtils.setField(dispatcher, "providerMode", "real");
        ReflectionTestUtils.setField(dispatcher, "emailProvider", "smtp");
        ReflectionTestUtils.setField(dispatcher, "smtpHost", "smtp.gmail.com");
        ReflectionTestUtils.setField(dispatcher, "smtpPort", 587);
        ReflectionTestUtils.setField(dispatcher, "smtpUsername", "sender@example.com");
        ReflectionTestUtils.setField(dispatcher, "smtpPassword", "app-secret");
        ReflectionTestUtils.setField(dispatcher, "smtpFromEmail", "sender@example.com");
        when(fcmTokens.isConfigured()).thenReturn(false);
        when(fcmTokens.source()).thenReturn("NONE");

        var status = dispatcher.providerStatus();

        assertEquals(true, status.sendGridConfigured());
        assertEquals("sender@example.com", status.sendGridFromEmail());
    }

    private void mockPersistence() {
        when(notifications.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(logs.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }
}
