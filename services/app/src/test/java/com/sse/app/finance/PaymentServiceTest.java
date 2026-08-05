package com.sse.app.finance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sse.app.audit.AuditService;
import com.sse.app.common.ApiException;
import com.sse.app.event.DomainEventPublisher;
import com.sse.app.finance.FinanceDtos.GatewayCallbackResponse;
import com.sse.app.finance.FinanceDtos.PayRequest;
import com.sse.app.finance.FinanceDtos.PaymentInitResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PaymentServiceTest {

    @Mock private PaymentRepository payments;
    @Mock private PaymentGatewayTransactionRepository gatewayTransactions;
    @Mock private InvoiceRepository invoices;
    @Mock private PaymentGateway gateway;
    @Mock private BankTransferService bankTransfers;
    @Mock private DomainEventPublisher events;
    @Mock private PaymentReceiptService receiptService;
    @Mock private AuditService audit;

    private PaymentService service;

    @BeforeEach
    void setUp() {
        service = new PaymentService(payments, gatewayTransactions, invoices,
                List.of(gateway), bankTransfers, new ObjectMapper(), events, receiptService, audit);
        lenient().when(receiptService.issueAfterSettlement(any(), any(), anyString()))
                .thenAnswer(invocation -> PaymentReceipt.builder()
                        .id("receipt-1")
                        .receiptNumber("SSE-REC-TEST")
                        .status("ISSUED")
                        .build());
    }

    @Test
    void externalPaymentStartsPendingAndStoresGatewayRequest() {
        Invoice invoice = invoice();
        when(invoices.findByIdForUpdate("invoice-1")).thenReturn(Optional.of(invoice));
        when(payments.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(gateway.supports("VNPAY")).thenReturn(true);
        when(gateway.initiate(any())).thenReturn(new PaymentGateway.GatewayInitiation(
                "http://gateway/return", "http://gateway/ipn",
                Map.of("txnRef", "request"), Map.of("gatewayStatus", "PENDING")));

        PaymentInitResponse result = service.create(new PayRequest("invoice-1", "VNPAY"), false);

        assertEquals("PENDING", result.payment().getStatus());
        assertEquals(500_000, result.payment().getAmount());
        assertEquals(0, invoice.getPaidAmount());
        assertEquals("PENDING", invoice.getStatus());
        assertEquals("http://gateway/return", result.paymentUrl());
        verify(invoices, never()).save(any());

        ArgumentCaptor<PaymentGatewayTransaction> transaction = ArgumentCaptor.forClass(PaymentGatewayTransaction.class);
        verify(gatewayTransactions).save(transaction.capture());
        assertEquals(result.payment().getId(), transaction.getValue().getPaymentId());
        assertEquals(0, transaction.getValue().getCallbackCount());
        assertFalse(transaction.getValue().isProcessed());
        assertTrue(transaction.getValue().getRequestPayload().contains("txnRef"));
        assertTrue(transaction.getValue().getResponsePayload().contains("PENDING"));
    }

    @Test
    void repeatedInitiationReusesMatchingPendingPayment() {
        Invoice invoice = invoice();
        Payment pending = pendingPayment();
        when(invoices.findByIdForUpdate("invoice-1")).thenReturn(Optional.of(invoice));
        when(payments.findByInvoiceId("invoice-1")).thenReturn(List.of(pending));
        when(gateway.supports("VNPAY")).thenReturn(true);
        when(gateway.initiate(any())).thenReturn(new PaymentGateway.GatewayInitiation(
                "http://gateway/return", "http://gateway/ipn", Map.of(), Map.of()));

        PaymentInitResponse result = service.create(new PayRequest("invoice-1", "VNPAY"), false);

        assertSame(pending, result.payment());
        assertEquals("PENDING", result.gatewayStatus());
        verify(payments, never()).saveAndFlush(any());
        verifyNoInteractions(gatewayTransactions);
    }

    @Test
    void repeatedMomoInitiationReusesStoredPayUrlWithoutCallingCreateApiAgain() {
        Invoice invoice = invoice();
        Payment pending = pendingPayment();
        pending.setMethod("MOMO");
        pending.setTxnRef("MOMO-tx-1");
        PaymentGatewayTransaction transaction = PaymentGatewayTransaction.builder()
                .id("gateway-momo-1")
                .paymentId(pending.getId())
                .provider("MOMO")
                .merchantTxnRef(pending.getTxnRef())
                .requestPayload("{\"ipnUrl\":\"http://gateway/momo/ipn\"}")
                .responsePayload("{\"payUrl\":\"https://test-payment.momo.vn/pay/ready\"}")
                .build();
        when(invoices.findByIdForUpdate("invoice-1")).thenReturn(Optional.of(invoice));
        when(payments.findByInvoiceId("invoice-1")).thenReturn(List.of(pending));
        when(gatewayTransactions.findByProviderAndMerchantTxnRefForUpdate("MOMO", pending.getTxnRef()))
                .thenReturn(Optional.of(transaction));

        PaymentInitResponse result = service.create(new PayRequest("invoice-1", "MOMO"), false);

        assertSame(pending, result.payment());
        assertEquals("https://test-payment.momo.vn/pay/ready", result.paymentUrl());
        assertEquals("http://gateway/momo/ipn", result.callbackUrl());
        verify(gateway, never()).initiate(any());
        verify(payments, never()).saveAndFlush(any());
    }

    @Test
    void parentCannotCreateCashPayment() {
        when(invoices.findByIdForUpdate("invoice-1")).thenReturn(Optional.of(invoice()));

        ApiException error = assertThrows(ApiException.class,
                () -> service.create(new PayRequest("invoice-1", "CASH"), false));

        assertEquals(HttpStatus.FORBIDDEN, error.getStatus());
        verifyNoInteractions(payments);
    }

    @Test
    void cashPaymentRemainsPendingUntilAdminConfirmsIt() {
        Invoice invoice = invoice();
        when(invoices.findByIdForUpdate("invoice-1")).thenReturn(Optional.of(invoice));
        when(payments.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

        PaymentInitResponse initiated = service.create(new PayRequest("invoice-1", "CASH"), true);
        assertEquals("PENDING", initiated.payment().getStatus());
        assertEquals(0, invoice.getPaidAmount());

        when(payments.findByIdForUpdate(initiated.payment().getId())).thenReturn(Optional.of(initiated.payment()));
        PaymentInitResponse confirmed = service.confirmCash(initiated.payment().getId());

        assertEquals("SUCCESS", confirmed.payment().getStatus());
        assertEquals(500_000, invoice.getPaidAmount());
        assertEquals("PAID", invoice.getStatus());
        verify(events).publish(eq("finance.invoice.paid"), eq("parent-1"), eq("invoice"),
                eq("invoice-1"), anyMap());
    }

    @Test
    void settlingInvoiceExpiresOtherPendingPaymentsAndAuditsThem() {
        Invoice invoice = invoice();
        Payment settled = pendingPayment();
        settled.setMethod("CASH");
        Payment staleQr = Payment.builder()
                .id("payment-stale").invoiceId("invoice-1").amount(500_000)
                .method("MB_BANK_TRANSFER").status("PENDING").txnRef("MB-stale").build();
        when(payments.findByIdForUpdate("payment-1")).thenReturn(Optional.of(settled));
        when(invoices.findByIdForUpdate("invoice-1")).thenReturn(Optional.of(invoice));
        when(payments.findByInvoiceId("invoice-1")).thenReturn(List.of(settled, staleQr));

        PaymentInitResponse result = service.confirmCash("payment-1", "admin-1");

        assertEquals("PAID", result.invoice().getStatus());
        assertEquals("EXPIRED", staleQr.getStatus());
        assertTrue(staleQr.getNote().contains("thanh toán bằng giao dịch khác"));
        verify(payments).saveAll(List.of(staleQr));
        verify(audit).record(eq("SYSTEM"), eq("Hệ thống"), eq("SYSTEM"), eq("EXPIRE"),
                eq("finance"), eq("payment"), eq("payment-stale"), contains("settledBy=payment-1"));
    }

    @Test
    void mbTransferStartsPendingWithInstructionsAndNoGatewayLedger() {
        Invoice invoice = invoice();
        when(invoices.findByIdForUpdate("invoice-1")).thenReturn(Optional.of(invoice));
        when(payments.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(bankTransfers.instructions(eq(invoice), any())).thenReturn(
                new FinanceDtos.BankTransferInstructions("MB", "MB Bank", "0123456789", "NGUYEN VAN B",
                        500_000, "SSE HS1001 NGUYEN VAN AN", "https://img.vietqr.io/test.png",
                        "HS1001", "Nguyen Van An", "INV-1"));

        PaymentInitResponse result = service.create(new PayRequest("invoice-1", "MB_BANK_TRANSFER"), false);

        assertEquals("PENDING", result.payment().getStatus());
        assertEquals("MB_BANK_TRANSFER", result.payment().getMethod());
        assertNotNull(result.bankTransfer());
        assertEquals("SSE HS1001 NGUYEN VAN AN", result.bankTransfer().transferContent());
        assertEquals(0, invoice.getPaidAmount());
        verifyNoInteractions(gatewayTransactions);
        verifyNoInteractions(gateway);
    }

    @Test
    void adminApprovalOfMbTransferSettlesInvoiceExactlyOnce() {
        Invoice invoice = invoice();
        Payment payment = pendingPayment();
        payment.setMethod("MB_BANK_TRANSFER");
        when(payments.findByIdForUpdate("payment-1")).thenReturn(Optional.of(payment));
        when(invoices.findByIdForUpdate("invoice-1")).thenReturn(Optional.of(invoice));
        when(bankTransfers.instructions(invoice, payment)).thenReturn(
                new FinanceDtos.BankTransferInstructions("MB", "MB Bank", "0123456789", "NGUYEN VAN B",
                        500_000, "SSE HS1001 NGUYEN VAN AN", "https://img.vietqr.io/test.png",
                        "HS1001", "Nguyen Van An", "INV-1"));

        PaymentInitResponse result = service.confirmBankTransfer("payment-1");

        assertEquals("SUCCESS", result.payment().getStatus());
        assertEquals(500_000, result.invoice().getPaidAmount());
        assertEquals("PAID", result.invoice().getStatus());
        verify(events).publish(eq("finance.invoice.paid"), eq("parent-1"), eq("invoice"),
                eq("invoice-1"), anyMap());
    }

    @Test
    void invalidSignatureIsLoggedWithoutUpdatingPaymentOrInvoice() {
        Payment payment = pendingPayment();
        PaymentGatewayTransaction transaction = gatewayTransaction();
        stubCallback(payment, transaction, new PaymentGateway.GatewayVerification(
                false, false, false, payment.getTxnRef(), payment.getAmount(),
                "provider-1", "00", "SIGNATURE_INVALID", "bad signature"));

        GatewayCallbackResponse result = service.processIpn("vnpay", Map.of("txnRef", payment.getTxnRef()));

        assertFalse(result.accepted());
        assertFalse(result.processed());
        assertEquals("PENDING", payment.getStatus());
        assertEquals(1, transaction.getCallbackCount());
        assertEquals("SIGNATURE_INVALID", transaction.getErrorCode());
        assertFalse(transaction.getSignatureValid());
        assertNull(transaction.getProviderTransactionId());
        verify(invoices, never()).findByIdForUpdate(anyString());
        verify(invoices, never()).save(any());
    }

    @Test
    void validSuccessfulIpnSettlesInvoiceExactlyOnce() {
        Payment payment = pendingPayment();
        Invoice invoice = invoice();
        PaymentGatewayTransaction transaction = gatewayTransaction();
        PaymentGateway.GatewayVerification verified = successfulVerification(payment);
        stubCallback(payment, transaction, verified);
        when(gatewayTransactions.findByProviderAndProviderTransactionId("VNPAY", "provider-1"))
                .thenReturn(Optional.empty());
        when(invoices.findById("invoice-1")).thenReturn(Optional.of(invoice));
        when(invoices.findByIdForUpdate("invoice-1")).thenReturn(Optional.of(invoice));

        GatewayCallbackResponse result = service.processIpn("VNPAY", callbackPayload(payment));

        assertTrue(result.accepted());
        assertTrue(result.processed());
        assertEquals("SUCCESS", payment.getStatus());
        assertNotNull(payment.getPaidAt());
        assertEquals(500_000, invoice.getPaidAmount());
        assertEquals("PAID", invoice.getStatus());
        assertTrue(transaction.isProcessed());
        assertNotNull(transaction.getProcessedAt());
        verify(invoices, times(1)).save(invoice);
    }

    @Test
    void replayedSuccessfulIpnOnlyIncrementsCallbackCount() {
        Payment payment = pendingPayment();
        payment.setStatus("SUCCESS");
        Invoice invoice = invoice();
        invoice.setPaidAmount(invoice.getTotalAmount());
        invoice.setStatus("PAID");
        PaymentGatewayTransaction transaction = gatewayTransaction();
        transaction.setProcessed(true);
        transaction.setCallbackCount(1);
        transaction.setProviderTransactionId("provider-1");
        stubCallback(payment, transaction, successfulVerification(payment));
        when(gatewayTransactions.findByProviderAndProviderTransactionId("VNPAY", "provider-1"))
                .thenReturn(Optional.of(transaction));
        when(invoices.findById("invoice-1")).thenReturn(Optional.of(invoice));

        GatewayCallbackResponse result = service.processIpn("VNPAY", callbackPayload(payment));

        assertTrue(result.accepted());
        assertFalse(result.processed());
        assertEquals(2, result.callbackCount());
        assertEquals(500_000, invoice.getPaidAmount());
        verify(invoices, never()).findByIdForUpdate(anyString());
        verify(invoices, never()).save(any());
    }

    @Test
    void successfulCallbackForExpiredPaymentCannotChangePaidAmount() {
        Payment payment = pendingPayment();
        payment.setStatus("EXPIRED");
        PaymentGatewayTransaction transaction = gatewayTransaction();
        stubCallback(payment, transaction, successfulVerification(payment));
        when(gatewayTransactions.findByProviderAndProviderTransactionId("VNPAY", "provider-1"))
                .thenReturn(Optional.empty());

        GatewayCallbackResponse result = service.processIpn("VNPAY", callbackPayload(payment));

        assertFalse(result.accepted());
        assertFalse(result.processed());
        assertEquals("PAYMENT_NOT_PENDING", result.errorCode());
        assertEquals("EXPIRED", payment.getStatus());
        verify(invoices, never()).findByIdForUpdate(anyString());
        verify(invoices, never()).save(any());
    }

    @Test
    void signedAmountMismatchCannotUpdateInvoice() {
        Payment payment = pendingPayment();
        PaymentGatewayTransaction transaction = gatewayTransaction();
        stubCallback(payment, transaction, new PaymentGateway.GatewayVerification(
                true, true, true, payment.getTxnRef(), 400_000L,
                "provider-1", "00", null, null));

        GatewayCallbackResponse result = service.processIpn("VNPAY", callbackPayload(payment));

        assertFalse(result.accepted());
        assertEquals("PENDING", payment.getStatus());
        assertEquals("AMOUNT_MISMATCH", transaction.getErrorCode());
        verify(invoices, never()).findByIdForUpdate(anyString());
        verify(invoices, never()).save(any());
    }

    @Test
    void providerTransactionIdCannotBeUsedForAnotherPayment() {
        Payment payment = pendingPayment();
        PaymentGatewayTransaction transaction = gatewayTransaction();
        PaymentGatewayTransaction existing = gatewayTransaction();
        existing.setId("gateway-other");
        existing.setPaymentId("payment-other");
        existing.setProviderTransactionId("provider-1");
        stubCallback(payment, transaction, successfulVerification(payment));
        when(gatewayTransactions.findByProviderAndProviderTransactionId("VNPAY", "provider-1"))
                .thenReturn(Optional.of(existing));

        GatewayCallbackResponse result = service.processIpn("VNPAY", callbackPayload(payment));

        assertFalse(result.accepted());
        assertFalse(result.processed());
        assertEquals("PROVIDER_TRANSACTION_DUPLICATE", result.errorCode());
        assertEquals("PENDING", payment.getStatus());
        verify(invoices, never()).findByIdForUpdate(anyString());
        verify(invoices, never()).save(any());
    }

    @Test
    void signedFailureMarksPaymentFailedWithoutChangingInvoice() {
        Payment payment = pendingPayment();
        Invoice invoice = invoice();
        PaymentGatewayTransaction transaction = gatewayTransaction();
        stubCallback(payment, transaction, new PaymentGateway.GatewayVerification(
                true, false, true, payment.getTxnRef(), payment.getAmount(),
                "provider-1", "24", "24", "Gateway rejected"));
        when(gatewayTransactions.findByProviderAndProviderTransactionId("VNPAY", "provider-1"))
                .thenReturn(Optional.empty());
        when(invoices.findById("invoice-1")).thenReturn(Optional.of(invoice));

        GatewayCallbackResponse result = service.processIpn("VNPAY", callbackPayload(payment));

        assertTrue(result.accepted());
        assertTrue(result.processed());
        assertEquals("FAILED", payment.getStatus());
        assertEquals(0, invoice.getPaidAmount());
        assertEquals("PENDING", invoice.getStatus());
        verify(invoices, never()).save(any());
        verify(events).publish(eq("finance.payment.failed"), eq("parent-1"), eq("invoice"),
                eq("invoice-1"), anyMap());
    }

    @Test
    void browserReturnIsReadOnly() {
        Payment payment = pendingPayment();
        when(payments.findById("payment-1")).thenReturn(Optional.of(payment));

        FinanceDtos.BrowserReturnResponse result = service.browserReturn("VNPAY", "payment-1");

        assertEquals("PENDING", result.status());
        assertFalse(result.finalStatus());
        verify(payments, never()).save(any());
        verifyNoInteractions(invoices, gatewayTransactions, events);
    }

    @Test
    void momoBrowserReturnRecognizesOfficialUnprefixedFieldsAndStaysReadOnly() {
        Payment payment = pendingPayment();
        payment.setMethod("MOMO");
        payment.setTxnRef("MOMO-tx-1");
        when(gateway.supports("MOMO")).thenReturn(true);
        when(gateway.verifyCallback(eq("MOMO"), anyMap())).thenReturn(
                new PaymentGateway.GatewayVerification(true, true, true,
                        payment.getTxnRef(), payment.getAmount(), "4088878653", "0", null, null));
        when(payments.findByTxnRef(payment.getTxnRef())).thenReturn(Optional.of(payment));

        FinanceDtos.BrowserReturnResponse result = service.browserReturn("MOMO", Map.of(
                "signature", "signed", "orderId", payment.getTxnRef()));

        assertEquals("PENDING", result.status());
        assertEquals(true, result.signatureValid());
        assertEquals(true, result.gatewaySuccessful());
        verify(payments, never()).save(any());
        verifyNoInteractions(invoices, gatewayTransactions, events);
    }

    @Test
    void inactiveInvoiceCannotCreatePayment() {
        Invoice invoice = invoice();
        invoice.setStatus("CANCELLED");
        when(invoices.findByIdForUpdate("invoice-1")).thenReturn(Optional.of(invoice));

        ApiException error = assertThrows(ApiException.class,
                () -> service.create(new PayRequest("invoice-1", "VNPAY"), false));

        assertEquals(HttpStatus.BAD_REQUEST, error.getStatus());
        verifyNoInteractions(payments);
    }

    private void stubCallback(Payment payment, PaymentGatewayTransaction transaction,
                              PaymentGateway.GatewayVerification verification) {
        when(gateway.supports("VNPAY")).thenReturn(true);
        when(gateway.verifyCallback(eq("VNPAY"), anyMap())).thenReturn(verification);
        when(payments.findByTxnRefForUpdate(payment.getTxnRef())).thenReturn(Optional.of(payment));
        when(gatewayTransactions.findByProviderAndMerchantTxnRefForUpdate("VNPAY", payment.getTxnRef()))
                .thenReturn(Optional.of(transaction));
    }

    private PaymentGateway.GatewayVerification successfulVerification(Payment payment) {
        return new PaymentGateway.GatewayVerification(
                true, true, true, payment.getTxnRef(), payment.getAmount(),
                "provider-1", "00", null, null);
    }

    private Map<String, String> callbackPayload(Payment payment) {
        Map<String, String> payload = new LinkedHashMap<>();
        payload.put("provider", "VNPAY");
        payload.put("txnRef", payment.getTxnRef());
        payload.put("amount", Long.toString(payment.getAmount()));
        payload.put("status", "SUCCESS");
        payload.put("providerTransactionId", "provider-1");
        payload.put("responseCode", "00");
        payload.put("signature", "signed");
        return payload;
    }

    private Invoice invoice() {
        return Invoice.builder()
                .id("invoice-1")
                .code("INV-1")
                .studentId("student-1")
                .parentId("parent-1")
                .totalAmount(500_000)
                .paidAmount(0)
                .status("PENDING")
                .build();
    }

    private Payment pendingPayment() {
        return Payment.builder()
                .id("payment-1")
                .invoiceId("invoice-1")
                .amount(500_000)
                .method("VNPAY")
                .status("PENDING")
                .txnRef("VNPAY-tx-1")
                .build();
    }

    private PaymentGatewayTransaction gatewayTransaction() {
        return PaymentGatewayTransaction.builder()
                .id("gateway-1")
                .paymentId("payment-1")
                .provider("VNPAY")
                .merchantTxnRef("VNPAY-tx-1")
                .callbackCount(0)
                .processed(false)
                .build();
    }
}
