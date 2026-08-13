package com.sse.app.finance;

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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaymentRealtimePublisherTest {
    @Mock InvoiceRepository invoices;
    @Mock PaymentRepository payments;
    @Mock UserService users;
    @Mock RealtimeEventHub realtime;

    @Test
    void paymentChangeInvalidatesStudentParentAndAdmins() {
        Invoice invoice = Invoice.builder()
                .id("invoice-1").studentId("student-1").parentId("parent-1")
                .status("PAID").paidAmount(500_000).build();
        Payment payment = Payment.builder()
                .id("payment-1").invoiceId("invoice-1").status("SUCCESS").build();
        when(invoices.findById("invoice-1")).thenReturn(Optional.of(invoice));
        when(payments.findById("payment-1")).thenReturn(Optional.of(payment));
        UserDto admin = mock(UserDto.class);
        when(admin.id()).thenReturn("admin-1");
        when(users.list("ADMIN", null, null)).thenReturn(List.of(admin));

        new PaymentRealtimePublisher(invoices, payments, users, realtime)
                .publish(new PaymentChangedEvent("invoice-1", "payment-1", "CONFIRMED"));

        verify(realtime).publish(eq("student-1"), eq("PAYMENT_STATUS_UPDATED"), any());
        verify(realtime).publish(eq("parent-1"), eq("PAYMENT_STATUS_UPDATED"), any());
        verify(realtime).publish(eq("admin-1"), eq("PAYMENT_STATUS_UPDATED"), any());
    }
}
