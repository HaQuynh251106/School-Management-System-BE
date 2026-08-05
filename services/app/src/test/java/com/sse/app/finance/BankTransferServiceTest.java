package com.sse.app.finance;

import com.sse.app.common.ApiException;
import com.sse.app.identity.User;
import com.sse.app.identity.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BankTransferServiceTest {
    @Mock private UserService users;

    private PaymentProperties properties;
    private BankTransferService service;

    @BeforeEach
    void setUp() {
        properties = new PaymentProperties();
        properties.getBankTransfer().setEnabled(true);
        properties.getBankTransfer().setBankId("MB");
        properties.getBankTransfer().setBankName("MB Bank");
        properties.getBankTransfer().setAccountNumber("0123456789");
        properties.getBankTransfer().setAccountName("NGUYEN VAN B");
        properties.getBankTransfer().setTransferPrefix("SSE");
        service = new BankTransferService(properties, users);
    }

    @Test
    void instructionsContainStudentIdentityAmountAndInvoiceWhenItFits() {
        when(users.getById("student-1")).thenReturn(User.builder()
                .id("student-1").studentCode("HS1001").fullName("Nguyễn Văn An").build());
        Invoice invoice = Invoice.builder().id("invoice-1").code("INV-HK1")
                .studentId("student-1").studentName("Nguyễn Văn An").build();
        Payment payment = Payment.builder().id("payment-1").amount(750_000).build();

        var result = service.instructions(invoice, payment);

        assertEquals("SSE HS1001 NGUYEN VAN AN INV HK1", result.transferContent());
        assertTrue(result.qrImageUrl().contains("/MB-0123456789-compact2.png"));
        assertTrue(result.qrImageUrl().contains("amount=750000"));
        assertTrue(result.qrImageUrl().contains("addInfo=SSE%20HS1001%20NGUYEN%20VAN%20AN%20INV%20HK1"));
    }

    @Test
    void invoiceCodeIsOmittedBeforeRequiredStudentIdentity() {
        String result = service.transferContent("SSE", "HS20260001", "NGUYEN THI MAI ANH",
                "INVOICE CODE THAT CANNOT FIT IN BANK CONTENT");

        assertEquals("SSE HS20260001 NGUYEN THI MAI ANH", result);
        assertTrue(result.contains("HS20260001"));
        assertTrue(result.contains("NGUYEN THI MAI ANH"));
    }

    @Test
    void disabledBankTransferCannotExposeIncompleteInstructions() {
        properties.getBankTransfer().setEnabled(false);
        Invoice invoice = Invoice.builder().studentId("student-1").build();

        assertThrows(ApiException.class,
                () -> service.instructions(invoice, Payment.builder().amount(10_000).build()));
    }
}
