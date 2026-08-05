package com.sse.app.finance;

import com.sse.app.academic.structure.StructureService;
import com.sse.app.academic.structure.Semester;
import com.sse.app.academic.structure.AcademicYear;
import com.sse.app.common.ApiException;
import com.sse.app.event.DomainEventPublisher;
import com.sse.app.finance.FinanceDtos.AddFeeItemRequest;
import com.sse.app.finance.FinanceDtos.CreateFeePeriodRequest;
import com.sse.app.finance.FinanceDtos.InvoicePreview;
import com.sse.app.finance.FinanceDtos.BankTransferInstructions;
import com.sse.app.finance.FinanceDtos.UpdateFeePeriodMetadataRequest;
import com.sse.app.identity.UserDto;
import com.sse.app.identity.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class FinanceServiceTest {

    @Mock private FeePeriodRepository periods;
    @Mock private FeePeriodItemRepository periodItems;
    @Mock private FeePeriodTargetRepository periodTargets;
    @Mock private FeePeriodItemTargetRepository itemTargets;
    @Mock private InvoiceRepository invoices;
    @Mock private InvoiceItemRepository invoiceItems;
    @Mock private PaymentRepository payments;
    @Mock private BankTransferService bankTransfers;
    @Mock private StructureService structure;
    @Mock private UserService users;
    @Mock private DomainEventPublisher events;

    private FinanceService finance;

    @BeforeEach
    void setUp() {
        finance = new FinanceService(periods, periodItems, periodTargets, itemTargets, invoices, invoiceItems,
                payments, bankTransfers, structure, users, events);
    }

    @Test
    void createPeriodRejectsDuplicateCodeIgnoringCase() {
        when(periods.existsByCodeIgnoreCase("HK1-2026")).thenReturn(true);

        ApiException error = assertThrows(ApiException.class, () -> finance.createPeriod(
                new CreateFeePeriodRequest(null, "hk1-2026", "Học phí HK1", null, null, null, null, null)));

        assertEquals(HttpStatus.CONFLICT, error.getStatus());
        verify(periods, never()).saveAndFlush(any());
    }

    @Test
    void createPeriodStoresFeeTypeAndValidSemesterScope() {
        when(periods.existsByCodeIgnoreCase("HP-HK1-2026")).thenReturn(false);
        when(structure.listSemesters(null)).thenReturn(List.of(Semester.builder()
                .id("semester-1").academicYearId("year-1").code("HK1").name("Học kỳ 1").build()));
        when(periods.saveAndFlush(any(FeePeriod.class))).thenAnswer(invocation -> invocation.getArgument(0));

        FeePeriod created = finance.createPeriod(new CreateFeePeriodRequest(
                null, "hp-hk1-2026", "Học phí học kỳ 1", "year-1", null,
                LocalDate.of(2026, 12, 31), "ALL", List.of(), "TUITION", "semester-1"));

        assertEquals("TUITION", created.getFeeType());
        assertEquals("semester-1", created.getSemesterId());
        assertEquals("year-1", created.getAcademicYearId());
    }

    @Test
    void updatePeriodMetadataClassifiesLegacyClosedPeriodWithoutChangingLifecycle() {
        FeePeriod legacy = FeePeriod.builder()
                .id("fp-legacy").code("OLD-01").name("Khoản thu cũ").status("CLOSED")
                .feeType("OTHER").build();
        when(periods.findByIdForUpdate("fp-legacy")).thenReturn(Optional.of(legacy));
        when(structure.listSemesters(null)).thenReturn(List.of(Semester.builder()
                .id("semester-2").academicYearId("year-1").code("HK2").name("Học kỳ 2").build()));
        when(periods.save(any(FeePeriod.class))).thenAnswer(invocation -> invocation.getArgument(0));

        FeePeriod updated = finance.updatePeriodMetadata("fp-legacy",
                new UpdateFeePeriodMetadataRequest("TUITION", "year-1", "semester-2"));

        assertEquals("TUITION", updated.getFeeType());
        assertEquals("year-1", updated.getAcademicYearId());
        assertEquals("semester-2", updated.getSemesterId());
        assertEquals("CLOSED", updated.getStatus());
    }

    @Test
    void updatePeriodMetadataRejectsSemesterFromAnotherAcademicYear() {
        FeePeriod period = FeePeriod.builder().id("fp-1").status("PUBLISHED").build();
        when(periods.findByIdForUpdate("fp-1")).thenReturn(Optional.of(period));
        when(structure.listSemesters(null)).thenReturn(List.of(Semester.builder()
                .id("semester-1").academicYearId("year-2").build()));

        ApiException error = assertThrows(ApiException.class, () -> finance.updatePeriodMetadata("fp-1",
                new UpdateFeePeriodMetadataRequest("MEAL", "year-1", "semester-1")));

        assertEquals(HttpStatus.BAD_REQUEST, error.getStatus());
        verify(periods, never()).save(any(FeePeriod.class));
    }

    @Test
    void parentInvoiceListIncludesAcademicYearAndSemesterLabels() {
        Invoice invoice = Invoice.builder()
                .id("invoice-1").feePeriodId("fp-1").parentId("parent-1")
                .studentId("student-1").status("PENDING").build();
        when(invoices.findByParentId("parent-1")).thenReturn(List.of(invoice));
        when(periods.findAll()).thenReturn(List.of(FeePeriod.builder()
                .id("fp-1").code("HP-HK1").name("Học phí học kỳ 1").feeType("TUITION")
                .academicYearId("year-1").semesterId("semester-1").build()));
        when(structure.listSemesters(null)).thenReturn(List.of(Semester.builder()
                .id("semester-1").academicYearId("year-1").name("Học kỳ 1").build()));
        when(structure.listYears()).thenReturn(List.of(AcademicYear.builder()
                .id("year-1").code("2026-2027").name("Năm học 2026-2027").build()));

        Invoice result = finance.listInvoices(null, "parent-1", null).get(0);

        assertEquals("HP-HK1", result.getFeePeriodCode());
        assertEquals("Học phí học kỳ 1", result.getFeePeriodName());
        assertEquals("TUITION", result.getFeeType());
        assertEquals("Năm học 2026-2027", result.getAcademicYearName());
        assertEquals("Học kỳ 1", result.getSemesterName());
    }

    @Test
    void generateInvoicesIsIdempotentForExistingStudent() {
        FeePeriod period = FeePeriod.builder()
                .id("fp-1").code("HK1-2026").name("HK1").status("OPEN").build();
        FeePeriodItem item = FeePeriodItem.builder()
                .id("item-1").feePeriodId("fp-1").name("Học phí").amount(1_000_000).build();
        UserDto student = student("student-1", "class-11a1");
        Invoice existing = Invoice.builder()
                .id("invoice-1").feePeriodId("fp-1").studentId("student-1").build();

        when(periods.findByIdForUpdate("fp-1")).thenReturn(Optional.of(period));
        when(periodItems.findByFeePeriodId("fp-1")).thenReturn(List.of(item));
        when(invoices.findByFeePeriodId("fp-1")).thenReturn(List.of(existing));
        when(users.list("STUDENT", null, null)).thenReturn(List.of(student));

        List<Invoice> created = finance.generateInvoices("fp-1");

        assertTrue(created.isEmpty());
        assertEquals("PUBLISHED", period.getStatus());
        verify(invoices, never()).saveAll(any());
        verify(invoiceItems, never()).saveAll(any());
    }

    @Test
    void generateInvoicesCreatesOnlyMissingStudents() {
        FeePeriod period = FeePeriod.builder()
                .id("fp-1").code("HK1-2026").name("HK1").status("OPEN").build();
        FeePeriodItem item = FeePeriodItem.builder()
                .id("item-1").feePeriodId("fp-1").name("Học phí").amount(1_000_000).build();
        UserDto existingStudent = student("student-1", "class-11a1");
        UserDto missingStudent = student("student-2", "class-11a1");
        Invoice existing = Invoice.builder()
                .id("invoice-1").feePeriodId("fp-1").studentId("student-1").build();

        when(periods.findByIdForUpdate("fp-1")).thenReturn(Optional.of(period));
        when(periodItems.findByFeePeriodId("fp-1")).thenReturn(List.of(item));
        when(invoices.findByFeePeriodId("fp-1")).thenReturn(List.of(existing));
        when(users.list("STUDENT", null, null)).thenReturn(List.of(existingStudent, missingStudent));
        when(structure.gradeLevelOf("class-11a1")).thenReturn("K11");
        when(users.parentIdsOf("student-2")).thenReturn(List.of());

        List<Invoice> created = finance.generateInvoices("fp-1");

        assertEquals(1, created.size());
        assertEquals("student-2", created.get(0).getStudentId());
        verify(invoices, times(1)).saveAll(any());
        verify(invoices, times(1)).flush();
        verify(invoiceItems, times(1)).flush();
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Iterable<InvoiceItem>> snapshots = ArgumentCaptor.forClass(Iterable.class);
        verify(invoiceItems).saveAll(snapshots.capture());
        InvoiceItem snapshot = snapshots.getValue().iterator().next();
        assertEquals("item-1", snapshot.getFeePeriodItemId());
        assertEquals("Học phí", snapshot.getName());
        assertEquals(1_000_000, snapshot.getAmount());
        assertEquals("ALL", snapshot.getSourceTargetType());
        assertEquals("PUBLISHED", period.getStatus());
    }

    @Test
    void publishingInvoiceProvisionsPendingMbPaymentAndQr() {
        FeePeriod period = FeePeriod.builder()
                .id("fp-qr").code("THU-QR").name("Khoan thu QR").status("OPEN").build();
        FeePeriodItem item = FeePeriodItem.builder()
                .id("item-qr").feePeriodId("fp-qr").name("Khoan thu chung").amount(1_000_000).build();
        UserDto student = student("student-qr", "class-11a1");

        when(periods.findByIdForUpdate("fp-qr")).thenReturn(Optional.of(period));
        when(periodItems.findByFeePeriodId("fp-qr")).thenReturn(List.of(item));
        when(invoices.findByFeePeriodId("fp-qr")).thenReturn(List.of());
        when(users.list("STUDENT", null, null)).thenReturn(List.of(student));
        when(structure.gradeLevelOf("class-11a1")).thenReturn("K11");
        when(users.parentIdsOf("student-qr")).thenReturn(List.of("parent-1"));
        when(bankTransfers.enabled()).thenReturn(true);
        when(payments.findByInvoiceId(anyString())).thenReturn(List.of());
        when(bankTransfers.instructions(any(Invoice.class), any(Payment.class)))
                .thenReturn(new BankTransferInstructions("MB", "MB Bank", "0000000000", "SSE SCHOOL",
                        1_000_000, "SSE HS001 NGUYEN VAN AN", "https://qr.test/mb.png",
                        "HS001", "Nguyen Van An", "INV-THU-QR-student-qr"));

        finance.generateInvoices("fp-qr");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Iterable<Payment>> captor = ArgumentCaptor.forClass(Iterable.class);
        verify(payments).saveAll(captor.capture());
        Payment payment = captor.getValue().iterator().next();
        assertEquals("MB_BANK_TRANSFER", payment.getMethod());
        assertEquals("PENDING", payment.getStatus());
        assertEquals(1_000_000, payment.getAmount());
        assertTrue(payment.isAutoProvisioned());
        assertEquals("SSE HS001 NGUYEN VAN AN", payment.getBankTransferContent());
        assertEquals("https://qr.test/mb.png", payment.getBankQrUrl());
    }

    @Test
    void generateInvoicesMarksPastDueInvoiceOverdue() {
        FeePeriod period = FeePeriod.builder()
                .id("fp-1").code("HK1-2026").name("HK1").status("OPEN")
                .dueDate(LocalDate.now().minusDays(1)).build();
        FeePeriodItem item = FeePeriodItem.builder()
                .id("item-1").feePeriodId("fp-1").name("Hoc phi").amount(1_000_000).build();
        UserDto student = student("student-1", "class-11a1");

        when(periods.findByIdForUpdate("fp-1")).thenReturn(Optional.of(period));
        when(periodItems.findByFeePeriodId("fp-1")).thenReturn(List.of(item));
        when(invoices.findByFeePeriodId("fp-1")).thenReturn(List.of());
        when(users.list("STUDENT", null, null)).thenReturn(List.of(student));
        when(users.parentIdsOf("student-1")).thenReturn(List.of());

        List<Invoice> created = finance.generateInvoices("fp-1");

        assertEquals(1, created.size());
        assertEquals("OVERDUE", created.get(0).getStatus());
    }

    @Test
    void previewSupportsClassScopeWithCommonAndPrivateItems() {
        FeePeriod period = FeePeriod.builder()
                .id("fp-1").code("P1").name("P1").status("DRAFT").targetType("CLASS").build();
        FeePeriodItem common = FeePeriodItem.builder()
                .id("item-common").feePeriodId("fp-1").name("Khoản chung").amount(1_000).targetType("ALL").build();
        FeePeriodItem privateItem = FeePeriodItem.builder()
                .id("item-private").feePeriodId("fp-1").name("Khoản riêng").amount(500).targetType("STUDENT").build();
        UserDto student1 = student("student-1", "class-11a1");
        UserDto student2 = student("student-2", "class-11a1");
        UserDto outside = student("student-3", "class-12a1");

        when(periods.findById("fp-1")).thenReturn(Optional.of(period));
        when(periodTargets.findByFeePeriodId("fp-1")).thenReturn(List.of(
                FeePeriodTarget.builder().targetId("class-11a1").build()));
        when(periodItems.findByFeePeriodId("fp-1")).thenReturn(List.of(common, privateItem));
        when(itemTargets.findByFeePeriodItemId(anyString())).thenAnswer(invocation ->
                "item-private".equals(invocation.getArgument(0))
                        ? List.of(FeePeriodItemTarget.builder().targetId("student-1").build())
                        : List.of());
        when(invoices.findByFeePeriodId("fp-1")).thenReturn(List.of());
        when(users.list("STUDENT", null, null)).thenReturn(List.of(student1, student2, outside));

        InvoicePreview preview = finance.previewInvoices("fp-1");

        assertEquals(2, preview.targetedStudentCount());
        assertEquals(2, preview.billableStudentCount());
        assertEquals(2, preview.newInvoiceCount());
        assertEquals(2_500, preview.newTotalAmount());
        assertEquals(1_500, preview.students().stream()
                .filter(row -> "student-1".equals(row.studentId())).findFirst().orElseThrow().totalAmount());
        assertEquals(1_000, preview.students().stream()
                .filter(row -> "student-2".equals(row.studentId())).findFirst().orElseThrow().totalAmount());
    }

    @Test
    void openRejectsDraftWithoutFeeItems() {
        FeePeriod period = FeePeriod.builder()
                .id("fp-1").code("P1").status("DRAFT").targetType("CLASS").build();
        when(periods.findByIdForUpdate("fp-1")).thenReturn(Optional.of(period));
        when(periodItems.findByFeePeriodId("fp-1")).thenReturn(List.of());

        ApiException error = assertThrows(ApiException.class, () -> finance.open("fp-1"));

        assertEquals(HttpStatus.BAD_REQUEST, error.getStatus());
        assertTrue(error.getMessage().contains("Chưa có khoản thu"));
        verify(periods, never()).save(any());
    }

    @Test
    void addPrivateItemRejectsStudentOutsidePeriodScope() {
        FeePeriod period = FeePeriod.builder()
                .id("fp-1").code("P1").status("DRAFT").targetType("CLASS").build();
        UserDto outside = student("student-3", "class-12a1");
        when(periods.findByIdForUpdate("fp-1")).thenReturn(Optional.of(period));
        when(users.dtoById("student-3")).thenReturn(outside);
        when(periodTargets.findByFeePeriodId("fp-1")).thenReturn(List.of(
                FeePeriodTarget.builder().targetId("class-11a1").build()));
        when(structure.gradeLevelOf("class-12a1")).thenReturn("K12");

        ApiException error = assertThrows(ApiException.class, () -> finance.addItem("fp-1",
                new AddFeeItemRequest(null, "Khoản riêng", 500L, null,
                        "STUDENT", List.of("student-3"))));

        assertEquals(HttpStatus.BAD_REQUEST, error.getStatus());
        assertTrue(error.getMessage().contains("không thuộc phạm vi"));
        verify(periodItems, never()).saveAndFlush(any());
    }

    @Test
    void deleteItemIsAllowedOnlyInDraft() {
        FeePeriod period = FeePeriod.builder().id("fp-1").status("DRAFT").build();
        FeePeriodItem item = FeePeriodItem.builder().id("item-1").feePeriodId("fp-1").build();
        when(periods.findByIdForUpdate("fp-1")).thenReturn(Optional.of(period));
        when(periodItems.findById("item-1")).thenReturn(Optional.of(item));

        finance.deleteItem("fp-1", "item-1");

        verify(itemTargets).deleteByFeePeriodItemId("item-1");
        verify(periodItems).delete(item);
    }

    @Test
    void closeOpenPeriodStopsFurtherIssuance() {
        FeePeriod period = FeePeriod.builder()
                .id("fp-1").code("P1").status("PUBLISHED").targetType("ALL").build();
        when(periods.findByIdForUpdate("fp-1")).thenReturn(Optional.of(period));
        when(periods.save(any(FeePeriod.class))).thenAnswer(invocation -> invocation.getArgument(0));

        FeePeriod closed = finance.close("fp-1");

        assertEquals("CLOSED", closed.getStatus());
        assertNotNull(closed.getClosedAt());
        assertThrows(ApiException.class, () -> finance.generateInvoices("fp-1"));
    }

    @Test
    void recallPublishedPeriodDeletesUnpaidInvoicesAndReturnsToDraft() {
        FeePeriod period = FeePeriod.builder()
                .id("fp-1").code("P1").status("PUBLISHED").targetType("ALL").publishedAt(Instant.now()).build();
        Invoice invoice = Invoice.builder()
                .id("invoice-1").code("INV-P1-1").studentId("student-1")
                .feePeriodId("fp-1").paidAmount(0).status("PENDING").build();
        when(periods.findByIdForUpdate("fp-1")).thenReturn(Optional.of(period));
        when(invoices.findByFeePeriodId("fp-1")).thenReturn(List.of(invoice));
        when(payments.findByInvoiceId("invoice-1")).thenReturn(List.of());
        when(periods.save(any(FeePeriod.class))).thenAnswer(invocation -> invocation.getArgument(0));

        FeePeriod recalled = finance.recallToDraft("fp-1");

        assertEquals("DRAFT", recalled.getStatus());
        assertNull(recalled.getPublishedAt());
        verify(invoiceItems).deleteByInvoiceId("invoice-1");
        verify(invoices).deleteAll(List.of(invoice));
        verify(invoices).flush();
        verify(events).publish(eq("finance.invoice.recalled"), eq("student-1"), eq("invoice"),
                eq("invoice-1"), anyMap());
    }

    @Test
    void recallPublishedPeriodRejectsPaymentActivity() {
        FeePeriod period = FeePeriod.builder().id("fp-1").status("PUBLISHED").build();
        Invoice invoice = Invoice.builder().id("invoice-1").feePeriodId("fp-1").paidAmount(0).status("PENDING").build();
        Payment payment = Payment.builder().id("pay-1").invoiceId("invoice-1").status("PENDING").build();
        when(periods.findByIdForUpdate("fp-1")).thenReturn(Optional.of(period));
        when(invoices.findByFeePeriodId("fp-1")).thenReturn(List.of(invoice));
        when(payments.findByInvoiceId("invoice-1")).thenReturn(List.of(payment));

        ApiException error = assertThrows(ApiException.class, () -> finance.recallToDraft("fp-1"));

        assertEquals(HttpStatus.CONFLICT, error.getStatus());
        verify(invoices, never()).deleteAll(any());
    }

    @Test
    void remindOverduePublishesNotificationEvent() {
        Invoice invoice = Invoice.builder()
                .id("invoice-1").code("INV-P1-1").studentId("student-1")
                .totalAmount(500_000).paidAmount(0).status("OVERDUE").build();
        when(invoices.findByIdForUpdate("invoice-1")).thenReturn(Optional.of(invoice));

        finance.remindOverdue("invoice-1");

        verify(events).publish(eq("finance.invoice.reminder"), eq("student-1"), eq("invoice"),
                eq("invoice-1"), anyMap());
    }

    @Test
    void cancelPeriodCancelsUnpaidInvoices() {
        FeePeriod period = FeePeriod.builder()
                .id("fp-1").code("P1").status("OPEN").targetType("ALL").build();
        Invoice invoice = Invoice.builder()
                .id("invoice-1").feePeriodId("fp-1").paidAmount(0).status("OVERDUE").build();
        when(periods.findByIdForUpdate("fp-1")).thenReturn(Optional.of(period));
        when(invoices.findByFeePeriodId("fp-1")).thenReturn(List.of(invoice));
        when(periods.save(any(FeePeriod.class))).thenAnswer(invocation -> invocation.getArgument(0));

        FeePeriod cancelled = finance.cancel("fp-1", "Tạo nhầm đợt thu");

        assertEquals("CANCELLED", cancelled.getStatus());
        assertEquals("Tạo nhầm đợt thu", cancelled.getCancellationReason());
        assertEquals("CANCELLED", invoice.getStatus());
        verify(invoices).saveAll(List.of(invoice));
    }

    @Test
    void cancelPeriodRejectsCollectedInvoices() {
        FeePeriod period = FeePeriod.builder()
                .id("fp-1").code("P1").status("OPEN").targetType("ALL").build();
        Invoice paid = Invoice.builder()
                .id("invoice-1").feePeriodId("fp-1").paidAmount(100).status("PARTIAL").build();
        when(periods.findByIdForUpdate("fp-1")).thenReturn(Optional.of(period));
        when(invoices.findByFeePeriodId("fp-1")).thenReturn(List.of(paid));

        ApiException error = assertThrows(ApiException.class,
                () -> finance.cancel("fp-1", "Không dùng nữa"));

        assertEquals(HttpStatus.CONFLICT, error.getStatus());
        verify(periods, never()).save(any());
    }

    private UserDto student(String id, String classId) {
        return new UserDto(
                id, id, "Student " + id, "STUDENT", "ACTIVE",
                null, null, null, "CODE-" + id, "11A1", classId,
                null, null, List.of()
        );
    }
}
