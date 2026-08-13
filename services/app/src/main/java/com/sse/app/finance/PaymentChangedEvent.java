package com.sse.app.finance;

record PaymentChangedEvent(String invoiceId, String paymentId, String action) {}
