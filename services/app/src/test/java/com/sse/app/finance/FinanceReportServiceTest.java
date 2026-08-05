package com.sse.app.finance;

import com.sse.app.academic.structure.SchoolClass;
import com.sse.app.academic.structure.StructureService;
import com.sse.app.common.ApiException;
import com.sse.app.finance.FinanceReportDtos.FinanceReportFilter;
import com.sse.app.finance.FinanceReportDtos.FinanceReportResponse;
import com.sse.app.identity.User;
import com.sse.app.identity.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FinanceReportServiceTest {
    @Mock private InvoiceRepository invoices;
    @Mock private PaymentRepository payments;
    @Mock private PaymentRefundRepository refunds;
    @Mock private FeePeriodRepository feePeriods;
    @Mock private UserRepository users;
    @Mock private StructureService structure;

    private FinanceReportService service;
    private final LocalDate fromDate = LocalDate.of(2026, 7, 20);
    private final LocalDate toDate = LocalDate.of(2026, 7, 21);

    @BeforeEach
    void setUp() {
        service = new FinanceReportService(invoices, payments, refunds, feePeriods, users, structure);
    }

    @Test
    void reportCalculatesReceivableCashFlowRefundNetAndOverdueDebt() {
        stubLedger();
        FinanceReportResponse report = service.report(filter(null));

        assertEquals(2, report.summary().invoiceCount());
        assertEquals(1_500_000, report.summary().totalReceivable());
        assertEquals(1_300_000, report.summary().currentPaidAmount());
        assertEquals(200_000, report.summary().outstandingAmount());
        assertEquals(1, report.summary().overdueInvoiceCount());
        assertEquals(200_000, report.summary().overdueAmount());
        assertEquals(1_500_000, report.summary().grossCollected());
        assertEquals(200_000, report.summary().refundAmount());
        assertEquals(1_300_000, report.summary().netRevenue());
        assertEquals(2, report.dailyCashFlow().size());
        assertEquals(2, report.byMethod().size());
        assertEquals(1, report.debtByFeePeriod().size());
        assertEquals(1, report.debtByGrade().size());
        assertEquals(1, report.debtByClass().size());
        assertEquals(1, report.debts().size());
        assertTrue(report.debts().get(0).overdue());
        assertEquals("10A1", report.debts().get(0).classCode());
    }

    @Test
    void methodFilterAppliesToGrossAndRefundCashFlowUsingOriginalPaymentMethod() {
        stubLedger();
        FinanceReportResponse report = service.report(filter("MB_BANK_TRANSFER"));

        assertEquals(1_000_000, report.summary().grossCollected());
        assertEquals(200_000, report.summary().refundAmount());
        assertEquals(800_000, report.summary().netRevenue());
        assertEquals(1, report.byMethod().size());
        assertEquals("MB_BANK_TRANSFER", report.byMethod().get(0).method());
        assertEquals(1_500_000, report.summary().totalReceivable());
    }

    @Test
    void filtersByFeeTypeSemesterAndSettlementStatus() {
        stubLedger();

        FinanceReportResponse paid = service.report(new FinanceReportFilter(
                fromDate, toDate, null, null, null, null, null,
                "TUITION", "semester-1", "PAID"));
        FinanceReportResponse unpaid = service.report(new FinanceReportFilter(
                fromDate, toDate, null, null, null, null, null,
                "TUITION", "semester-1", "UNPAID"));
        FinanceReportResponse wrongType = service.report(new FinanceReportFilter(
                fromDate, toDate, null, null, null, null, null,
                "TRANSPORT", null, null));

        assertEquals(1, paid.summary().invoiceCount());
        assertEquals(0, paid.summary().outstandingAmount());
        assertEquals(1, unpaid.summary().invoiceCount());
        assertEquals(200_000, unpaid.summary().outstandingAmount());
        assertEquals(0, wrongType.summary().invoiceCount());
    }

    @Test
    void rejectsInvalidDateRangeAndUnsupportedMethod() {
        assertThrows(ApiException.class, () -> service.report(new FinanceReportFilter(
                toDate, fromDate, null, null, null, null, null)));
        assertThrows(ApiException.class, () -> service.report(new FinanceReportFilter(
                fromDate, toDate, null, null, null, null, "CRYPTO")));
    }

    @Test
    void excelAndPdfExportsHaveValidFileSignatures() {
        stubLedger();
        FinanceReportResponse report = service.report(filter(null));

        byte[] xlsx = new FinanceReportExcelExporter().export(report);
        byte[] pdf = new FinanceReportPdfRenderer().render(report);

        assertTrue(xlsx.length > 2_000);
        assertEquals('P', xlsx[0]);
        assertEquals('K', xlsx[1]);
        assertTrue(pdf.length > 10_000);
        assertEquals("%PDF", new String(pdf, 0, 4, StandardCharsets.US_ASCII));
    }

    @Test
    void supportsLongNumericTokensInPeriodAndClassCodes() {
        User student = User.builder().id("student-long-code").role("STUDENT").status("ACTIVE")
                .studentCode("HS-LONG").fullName("Long Code Student")
                .classId("class-long-code").className("10A20260721151147460").build();
        SchoolClass schoolClass = SchoolClass.builder().id("class-long-code")
                .code("10A20260721151147460").name("Lớp mã dài").gradeLevel("K10")
                .academicYearId("ay-1").studentCount(1).build();
        FeePeriod period = FeePeriod.builder().id("fp-long-code")
                .code("P42-20260721151147460").name("Đợt thu mã dài").status("OPEN").build();
        Invoice invoice = Invoice.builder().id("invoice-long-code").code("INV-LONG")
                .studentId(student.getId()).studentName(student.getFullName()).feePeriodId(period.getId())
                .totalAmount(100_000).paidAmount(0).status("OVERDUE").dueDate(fromDate.minusDays(1)).build();

        when(users.findAll()).thenReturn(List.of(student));
        when(structure.listClasses(null, null)).thenReturn(List.of(schoolClass));
        when(feePeriods.findAll()).thenReturn(List.of(period));
        when(invoices.findAll()).thenReturn(List.of(invoice));
        when(payments.findAll()).thenReturn(List.of());
        when(refunds.findAll()).thenReturn(List.of());

        FinanceReportResponse report = service.report(new FinanceReportFilter(
                fromDate, toDate, null, null, null, null, null));

        assertEquals("P42-20260721151147460", report.debtByFeePeriod().get(0).code());
        assertEquals("10A20260721151147460", report.debtByClass().get(0).code());
        assertEquals("10A20260721151147460", report.debts().get(0).classCode());
    }

    @Test
    void reversedPaymentCountsAsGrossOnlyWhenBackedByCompletedRefund() {
        User student = User.builder().id("student-refunded").role("STUDENT").status("ACTIVE")
                .studentCode("HS-REFUND").fullName("Refunded Student")
                .classId("class-10a1").className("10A1").build();
        SchoolClass schoolClass = SchoolClass.builder().id("class-10a1").code("10A1")
                .name("Lớp 10A1").gradeLevel("K10").academicYearId("ay-1").studentCount(1).build();
        FeePeriod period = FeePeriod.builder().id("fp-refund").code("REFUND-01")
                .name("Đợt hoàn toàn phần").status("OPEN").build();
        Invoice invoice = Invoice.builder().id("invoice-refunded").code("INV-REFUND")
                .studentId(student.getId()).studentName(student.getFullName()).feePeriodId(period.getId())
                .totalAmount(100_000).paidAmount(0).status("PENDING").dueDate(toDate.plusDays(10)).build();
        Payment refundedPayment = Payment.builder().id("payment-refunded").invoiceId(invoice.getId())
                .amount(100_000).method("CASH").status("REVERSED")
                .paidAt(Instant.parse("2026-07-20T03:00:00Z")).build();
        Payment cleanupReversal = Payment.builder().id("payment-cleanup").invoiceId(invoice.getId())
                .amount(70_000).method("CASH").status("REVERSED")
                .paidAt(Instant.parse("2026-07-20T04:00:00Z")).build();
        PaymentRefund completedRefund = PaymentRefund.builder().id("refund-completed")
                .paymentId(refundedPayment.getId()).invoiceId(invoice.getId()).studentId(student.getId())
                .amount(100_000).status("COMPLETED")
                .completedAt(Instant.parse("2026-07-21T03:00:00Z")).build();

        when(users.findAll()).thenReturn(List.of(student));
        when(structure.listClasses(null, null)).thenReturn(List.of(schoolClass));
        when(feePeriods.findAll()).thenReturn(List.of(period));
        when(invoices.findAll()).thenReturn(List.of(invoice));
        when(payments.findAll()).thenReturn(List.of(refundedPayment, cleanupReversal));
        when(refunds.findAll()).thenReturn(List.of(completedRefund));

        FinanceReportResponse report = service.report(new FinanceReportFilter(
                fromDate, toDate, null, null, null, null, null));

        assertEquals(1, report.summary().paymentCount());
        assertEquals(100_000, report.summary().grossCollected());
        assertEquals(100_000, report.summary().refundAmount());
        assertEquals(0, report.summary().netRevenue());
    }

    private FinanceReportFilter filter(String method) {
        return new FinanceReportFilter(fromDate, toDate, "fp-1", "K10", "class-10a1", null, method);
    }

    private void stubLedger() {
        User student1 = User.builder().id("student-1").role("STUDENT").status("ACTIVE")
                .studentCode("HS001").fullName("Nguyen Van A").classId("class-10a1").className("10A1").build();
        User student2 = User.builder().id("student-2").role("STUDENT").status("ACTIVE")
                .studentCode("HS002").fullName("Tran Thi B").classId("class-10a1").className("10A1").build();
        SchoolClass schoolClass = SchoolClass.builder().id("class-10a1").code("10A1").name("Lớp 10A1")
                .gradeLevel("K10").academicYearId("ay-1").studentCount(2).build();
        FeePeriod period = FeePeriod.builder().id("fp-1").code("HP-HK1").name("Học phí học kỳ 1")
                .status("OPEN").feeType("TUITION").semesterId("semester-1").build();
        Invoice first = Invoice.builder().id("invoice-1").code("INV-001").studentId("student-1")
                .studentName("Nguyen Van A").feePeriodId("fp-1").totalAmount(1_000_000).paidAmount(800_000)
                .status("PARTIAL").dueDate(LocalDate.of(2026, 7, 19)).build();
        Invoice second = Invoice.builder().id("invoice-2").code("INV-002").studentId("student-2")
                .studentName("Tran Thi B").feePeriodId("fp-1").totalAmount(500_000).paidAmount(500_000)
                .status("PAID").dueDate(LocalDate.of(2026, 7, 30)).build();
        Payment bankPayment = Payment.builder().id("payment-1").invoiceId("invoice-1").amount(1_000_000)
                .method("MB_BANK_TRANSFER").status("SUCCESS")
                .paidAt(Instant.parse("2026-07-20T03:00:00Z")).build();
        Payment cashPayment = Payment.builder().id("payment-2").invoiceId("invoice-2").amount(500_000)
                .method("CASH").status("SUCCESS")
                .paidAt(Instant.parse("2026-07-21T03:00:00Z")).build();
        PaymentRefund refund = PaymentRefund.builder().id("refund-1").paymentId("payment-1")
                .invoiceId("invoice-1").studentId("student-1").amount(200_000).status("COMPLETED")
                .completedAt(Instant.parse("2026-07-20T04:00:00Z")).build();

        when(users.findAll()).thenReturn(List.of(student1, student2));
        when(structure.listClasses(null, null)).thenReturn(List.of(schoolClass));
        when(feePeriods.findAll()).thenReturn(List.of(period));
        when(invoices.findAll()).thenReturn(List.of(first, second));
        when(payments.findAll()).thenReturn(List.of(bankPayment, cashPayment));
        when(refunds.findAll()).thenReturn(List.of(refund));
    }
}
