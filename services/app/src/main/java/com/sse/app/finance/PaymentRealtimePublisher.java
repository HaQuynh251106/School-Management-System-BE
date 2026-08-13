package com.sse.app.finance;

import com.sse.app.identity.UserDto;
import com.sse.app.identity.UserService;
import com.sse.app.realtime.RealtimeEventHub;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

@Component
@RequiredArgsConstructor
class PaymentRealtimePublisher {
    private final InvoiceRepository invoices;
    private final PaymentRepository payments;
    private final UserService users;
    private final RealtimeEventHub realtime;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void publish(PaymentChangedEvent event) {
        invoices.findById(event.invoiceId()).ifPresent(invoice -> {
            Payment payment = event.paymentId() == null
                    ? null
                    : payments.findById(event.paymentId()).orElse(null);
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("resource", "PAYMENT");
            payload.put("action", event.action());
            payload.put("entityId", event.paymentId());
            payload.put("invoiceId", invoice.getId());
            payload.put("studentId", invoice.getStudentId());
            payload.put("invoiceStatus", invoice.getStatus());
            payload.put("paidAmount", invoice.getPaidAmount());
            payload.put("paymentStatus", payment == null ? null : payment.getStatus());
            payload.put("occurredAt", Instant.now().toString());

            Set<String> recipients = new LinkedHashSet<>();
            recipients.add(invoice.getStudentId());
            if (invoice.getParentId() != null) {
                recipients.add(invoice.getParentId());
            } else {
                recipients.addAll(users.parentIdsOf(invoice.getStudentId()));
            }
            users.list("ADMIN", null, null).stream()
                    .map(UserDto::id).forEach(recipients::add);
            recipients.forEach(userId ->
                    realtime.publish(userId, "PAYMENT_STATUS_UPDATED", payload));
        });
    }
}
