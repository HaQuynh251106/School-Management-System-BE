package com.sse.app.notification;

import com.sse.app.event.DomainEvent;
import com.sse.app.identity.UserService;
import com.sse.app.identity.UserDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.never;

@ExtendWith(MockitoExtension.class)
class NotificationServiceTest {
    @Mock private NotificationRepository notifications;
    @Mock private NotificationTemplateRepository templates;
    @Mock private NotificationDeliveryLogRepository deliveryLogs;
    @Mock private AnnouncementRepository announcements;
    @Mock private UserNotificationPreferenceRepository preferences;
    @Mock private UserService users;
    @Mock private NotificationChannelDispatcher channelDispatcher;

    private NotificationService service;

    @BeforeEach
    void setUp() {
        service = new NotificationService(
                notifications, templates, deliveryLogs,
                announcements, preferences, users, channelDispatcher);
    }

    private void stubDeliverySaves() {
        when(notifications.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(deliveryLogs.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void submittedPaymentProofNotifiesTargetAdmin() {
        stubDeliverySaves();
        service.handleDomainEvent(DomainEvent.of(
                "finance.payment.proof_submitted", "admin-1", "payment_proof", "proof-1",
                Map.of("studentName", "Nguyen Van An", "invoiceCode", "INV-001",
                        "message", "Co bien lai moi")));

        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(notifications).save(captor.capture());
        Notification notification = captor.getValue();
        assertEquals("admin-1", notification.getRecipientId());
        assertEquals("PAYMENT_PROOF", notification.getRefType());
        assertTrue(notification.getBody().contains("INV-001"));
    }

    @Test
    void repaymentRequestNotifiesTargetParent() {
        stubDeliverySaves();
        service.handleDomainEvent(DomainEvent.of(
                "finance.payment.proof_reviewed", "parent-1", "payment_proof", "proof-2",
                Map.of("status", "RETRY_REQUIRED", "invoiceCode", "INV-002",
                        "message", "Yeu cau thanh toan lai: Sai so tien")));

        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(notifications).save(captor.capture());
        Notification notification = captor.getValue();
        assertEquals("parent-1", notification.getRecipientId());
        assertEquals("Yêu cầu thanh toán lại", notification.getTitle());
        assertTrue(notification.getBody().contains("Sai so tien"));
    }

    @Test
    void parentWithTwoChildrenReceivesBothNewFeeNotifications() {
        stubDeliverySaves();
        when(users.parentIdsOf("student-1")).thenReturn(List.of("parent-1"));
        when(users.parentIdsOf("student-2")).thenReturn(List.of("parent-1"));

        service.handleDomainEvent(DomainEvent.of("finance.invoice.issued", "student-1", "invoice", "invoice-1",
                Map.of("studentId", "student-1")));
        service.handleDomainEvent(DomainEvent.of("finance.invoice.issued", "student-2", "invoice", "invoice-2",
                Map.of("studentId", "student-2")));

        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(notifications, org.mockito.Mockito.times(4)).save(captor.capture());
        List<Notification> parentNotifications = captor.getAllValues().stream()
                .filter(notification -> "parent-1".equals(notification.getRecipientId()))
                .toList();
        assertEquals(2, parentNotifications.size());
        assertTrue(parentNotifications.stream().allMatch(notification -> "Có khoản thu mới".equals(notification.getTitle())));
        assertTrue(parentNotifications.stream().allMatch(notification -> "INVOICE".equals(notification.getType())));
    }

    @Test
    void financeUnreadCountAndReadAllUseOnlyFinanceTypes() {
        Notification invoice = Notification.builder().id("notification-1").recipientId("parent-1")
                .type("INVOICE").read(false).build();
        when(notifications.countByRecipientIdAndReadIsFalseAndTypeIn("parent-1", List.of("INVOICE", "PAYMENT")))
                .thenReturn(3L);
        when(notifications.findByRecipientIdAndReadIsFalseAndTypeInOrderByCreatedAtDesc(
                "parent-1", List.of("INVOICE", "PAYMENT"))).thenReturn(List.of(invoice));

        assertEquals(3L, service.financeUnreadCount("parent-1"));
        service.markAllFinanceRead("parent-1");

        assertTrue(invoice.isRead());
        verify(notifications).saveAll(List.of(invoice));
    }

    @Test
    void disabledPreferenceSkipsInAppNotification() {
        when(preferences.findByUserIdAndNotificationTypeAndChannel(
                "student-1", "GRADE", "IN_APP"))
                .thenReturn(Optional.of(UserNotificationPreference.builder()
                        .enabled(false).build()));

        service.notifyUser("student-1", "GRADE", "Có điểm", "9.0",
                "GRADE", "grade-1");

        verify(notifications, never()).save(any());
    }

    @Test
    void allPreferenceEnablesExternalChannel() {
        stubDeliverySaves();
        when(preferences.findByUserIdAndNotificationTypeAndChannel(
                anyString(), anyString(), anyString())).thenReturn(Optional.empty());
        doReturn(Optional.of(UserNotificationPreference.builder().enabled(true).build()))
                .when(preferences).findByUserIdAndNotificationTypeAndChannel(
                        "student-1", "ALL", "EMAIL");

        service.notifyUser("student-1", "GRADE", "Có điểm", "9.0",
                "GRADE", "grade-1");

        verify(channelDispatcher).dispatch(
                org.mockito.ArgumentMatchers.eq("student-1"),
                org.mockito.ArgumentMatchers.eq("GRADE"),
                org.mockito.ArgumentMatchers.eq("EMAIL"),
                org.mockito.ArgumentMatchers.eq("Có điểm"),
                org.mockito.ArgumentMatchers.eq("9.0"),
                org.mockito.ArgumentMatchers.eq("GRADE"),
                org.mockito.ArgumentMatchers.eq("grade-1"),
                any(), any());
    }

    @Test
    void publishedTimetableNotifiesEveryAssignedTeacher() {
        stubDeliverySaves();
        when(users.list("STUDENT", null, "class-10a1")).thenReturn(List.of());

        service.handleDomainEvent(DomainEvent.of(
                "academic.timetable.published", "admin-1",
                "timetable_schedule", "schedule-1",
                Map.of("classId", "class-10a1",
                        "teacherIds", List.of("teacher-1", "teacher-2"),
                        "message", "Lịch học mới đã được phát hành")));

        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(notifications, org.mockito.Mockito.times(2)).save(captor.capture());
        assertEquals(List.of("teacher-1", "teacher-2"), captor.getAllValues()
                .stream().map(Notification::getRecipientId).toList());
        assertTrue(captor.getAllValues().stream()
                .allMatch(item -> "TIMETABLE".equals(item.getType())));
    }

    @Test
    void approvedMakeupNotifiesTeacherStudentAndParent() {
        stubDeliverySaves();
        UserDto student = new UserDto(
                "student-1", "hs001", "Nguyen Van An", "STUDENT", "ACTIVE",
                null, null, null, "HS001", "10A1", "class-10a1",
                null, null, List.of());
        when(users.list("STUDENT", null, "class-10a1")).thenReturn(List.of(student));
        when(users.parentIdsOf("student-1")).thenReturn(List.of("parent-1"));

        service.handleDomainEvent(DomainEvent.of(
                "academic.timetable.makeup_approved", "admin-1",
                "timetable_makeup", "makeup-1",
                Map.of("classId", "class-10a1",
                        "teacherIds", List.of("teacher-1"),
                        "message", "10A1 hoc bu Toan vao ngay 12/08, tiet 7")));

        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(notifications, org.mockito.Mockito.times(3)).save(captor.capture());
        assertEquals(List.of("student-1", "parent-1", "teacher-1"), captor.getAllValues()
                .stream().map(Notification::getRecipientId).toList());
        assertTrue(captor.getAllValues().stream()
                .allMatch(item -> "TIMETABLE".equals(item.getType())));
    }

    @Test
    void notificationCarriesDeepLinkAndGroup() {
        stubDeliverySaves();

        Notification row = service.notifyUser(
                "parent-1", "INVOICE", "Khoản thu", "Nội dung",
                "INVOICE", "invoice-1");

        assertEquals("/finance?ref=invoice-1", row.getDeepLink());
        assertEquals("FINANCE", row.getGroupKey());
    }

    @Test
    void operationsSummaryReportsStatusesChannelsAndFailureRate() {
        when(notifications.count()).thenReturn(12L);
        when(notifications.countByStatus("QUEUED")).thenReturn(1L);
        when(notifications.countByStatus("SENT")).thenReturn(8L);
        when(notifications.countByStatus("FAILED")).thenReturn(2L);
        when(notifications.countByStatus("RETRYING")).thenReturn(1L);
        when(notifications.countByChannel("IN_APP")).thenReturn(10L);
        when(notifications.countByChannel("EMAIL")).thenReturn(1L);
        when(notifications.countByChannel("PUSH")).thenReturn(1L);
        when(deliveryLogs.count()).thenReturn(10L);
        when(deliveryLogs.countByStatus("SENT")).thenReturn(8L);
        when(deliveryLogs.countByStatus("FAILED")).thenReturn(2L);

        var summary = service.operationsSummary();

        assertEquals(12L, summary.totalNotifications());
        assertEquals(2L, summary.failed());
        assertEquals(20.0, summary.failureRatePercent());
        assertEquals(10L, summary.notificationsByChannel().get("IN_APP"));
    }
}
