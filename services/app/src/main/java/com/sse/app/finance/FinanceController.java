package com.sse.app.finance;

import com.sse.app.common.ApiException;
import com.sse.app.finance.FinanceDtos.*;
import com.sse.app.academic.structure.StructureService;
import com.sse.app.identity.UserService;
import com.sse.app.security.CurrentUser;
import com.sse.app.security.CurrentUserHolder;
import com.sse.app.common.PageResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import jakarta.servlet.http.HttpServletRequest;

import java.util.List;
import java.util.Map;

/** A7/D4: Đợt thu, hóa đơn, thanh toán. */
@RestController
public class FinanceController {

    private final FinanceService finance;
    private final UserService users;
    private final StructureService structure;

    public FinanceController(FinanceService finance, UserService users, StructureService structure) {
        this.finance = finance;
        this.users = users;
        this.structure = structure;
    }

    // ----- Đợt thu (Kế toán; ADMIN giữ quyền giám sát) -----
    @GetMapping("/finance/integrations")
    public Map<String, Object> integrationStatus() {
        CurrentUserHolder.requireRole("ADMIN", "ACCOUNTANT");
        return finance.integrationStatus();
    }

    @GetMapping("/fee-periods")
    public List<FeePeriod> periods() {
        CurrentUserHolder.requireRole("ADMIN", "ACCOUNTANT", "TEACHER");
        return finance.listPeriods();
    }

    @PostMapping("/fee-periods")
    public FeePeriod createPeriod(@Valid @RequestBody CreateFeePeriodRequest r) {
        CurrentUserHolder.requireRole("ACCOUNTANT");
        return finance.createPeriod(r);
    }

    @PutMapping("/fee-periods/{id}")
    public FeePeriod updatePeriod(@PathVariable String id,
                                  @Valid @RequestBody UpdateFeePeriodRequest request) {
        CurrentUserHolder.requireRole("ACCOUNTANT");
        return finance.updatePeriod(id, request);
    }

    @DeleteMapping("/fee-periods/{id}")
    public void deletePeriod(@PathVariable String id) {
        CurrentUserHolder.requireRole("ACCOUNTANT");
        finance.deletePeriod(id);
    }

    @GetMapping("/fee-periods/{id}/items")
    public List<FeePeriodItem> items(@PathVariable String id) {
        CurrentUserHolder.requireRole("ADMIN", "ACCOUNTANT");
        return finance.itemsOf(id);
    }

    @PostMapping("/fee-periods/{id}/items")
    public FeePeriodItem addItem(@PathVariable String id, @Valid @RequestBody AddFeeItemRequest r) {
        CurrentUserHolder.requireRole("ACCOUNTANT");
        return finance.addItem(id, r);
    }

    @DeleteMapping("/fee-periods/{periodId}/items/{itemId}")
    public void deleteItem(@PathVariable String periodId, @PathVariable String itemId) {
        CurrentUserHolder.requireRole("ACCOUNTANT");
        finance.deleteItem(periodId, itemId);
    }

    @PostMapping("/fee-periods/{id}/open")
    public FeePeriod open(@PathVariable String id) {
        CurrentUserHolder.requireRole("ACCOUNTANT");
        return finance.open(id);
    }

    @PostMapping("/fee-periods/{id}/close")
    public FeePeriod close(@PathVariable String id) {
        CurrentUserHolder.requireRole("ACCOUNTANT");
        return finance.close(id);
    }

    @PostMapping("/fee-periods/{id}/generate-invoices")
    public List<Invoice> generate(@PathVariable String id) {
        CurrentUserHolder.requireRole("ACCOUNTANT");
        return finance.generateInvoices(id);
    }

    // ----- Hóa đơn -----
    @GetMapping("/invoices")
    public List<Invoice> invoices(@RequestParam(required = false) String studentId,
                                  @RequestParam(required = false) String parentId,
                                  @RequestParam(required = false) String status,
                                  @RequestParam(required = false) String periodId,
                                  @RequestParam(required = false, name = "q") String query,
                                  @RequestParam(required = false) String classId,
                                  @RequestParam(required = false) String gradeLevel) {
        CurrentUser me = CurrentUserHolder.require();
        CurrentUserHolder.requireRole("ADMIN", "ACCOUNTANT", "TEACHER", "PARENT", "STUDENT");
        if (me.isParent()) {
            if (studentId != null) { users.assertParentOf(me.id(), studentId); }
            else parentId = me.id();
        } else if (me.isStudent()) {
            studentId = me.id();
        } else if (me.isTeacher()) {
            assertHomeroomClass(me.id(), classId);
        }
        return finance.listInvoices(studentId, parentId, status, periodId, query, classId, gradeLevel);
    }

    @GetMapping("/invoices/page")
    public PageResponse<Invoice> invoicePage(@RequestParam(required = false) String studentId,
                                              @RequestParam(required = false) String parentId,
                                              @RequestParam(required = false) String status,
                                              @RequestParam(required = false) String periodId,
                                              @RequestParam(required = false, name = "q") String query,
                                              @RequestParam(required = false) String classId,
                                              @RequestParam(required = false) String gradeLevel,
                                              @RequestParam(defaultValue = "0") int page,
                                              @RequestParam(defaultValue = "20") int size) {
        CurrentUser me = CurrentUserHolder.require();
        CurrentUserHolder.requireRole("ADMIN", "ACCOUNTANT", "TEACHER", "PARENT", "STUDENT");
        if (me.isParent()) {
            if (studentId != null) users.assertParentOf(me.id(), studentId);
            else parentId = me.id();
        } else if (me.isStudent()) {
            studentId = me.id();
        } else if (me.isTeacher()) {
            assertHomeroomClass(me.id(), classId);
        }
        return finance.pageInvoices(studentId, parentId, status, periodId, query, classId, gradeLevel, page, size);
    }

    @GetMapping("/finance/classes")
    public List<FinanceClassSummary> classSummaries(@RequestParam(required = false) String periodId,
                                                     @RequestParam(required = false) String gradeLevel,
                                                     @RequestParam(required = false) String classId,
                                                     @RequestParam(required = false) String status) {
        CurrentUser me = CurrentUserHolder.require();
        CurrentUserHolder.requireRole("ADMIN", "ACCOUNTANT", "TEACHER");
        if (me.canManageFinance()) return finance.classSummaries(periodId, null, gradeLevel, classId, status);
        var classIds = structure.classesOfHomeroom(me.id()).stream()
                .map(com.sse.app.academic.structure.SchoolClass::getId)
                .collect(java.util.stream.Collectors.toSet());
        return finance.classSummaries(periodId, classIds, gradeLevel, classId, status);
    }

    @PostMapping("/finance/classes/{classId}/remind-homeroom")
    public HomeroomDebtReminderResult remindHomeroomTeacher(@PathVariable String classId,
                                                             @RequestParam(required = false) String periodId) {
        CurrentUserHolder.requireRole("ACCOUNTANT");
        return finance.remindHomeroomTeachers(periodId, List.of(classId));
    }

    @PostMapping("/finance/classes/remind-homerooms")
    public HomeroomDebtReminderResult remindHomeroomTeachers(
            @RequestBody HomeroomDebtReminderRequest request) {
        CurrentUserHolder.requireRole("ACCOUNTANT");
        return finance.remindHomeroomTeachers(request.periodId(), request.classIds());
    }

    @PostMapping("/finance/classes/{classId}/notify-completion")
    public void notifyCompletion(@PathVariable String classId,
                                 @RequestParam(required = false) String periodId) {
        CurrentUserHolder.requireRole("ACCOUNTANT");
        finance.notifyHomeroomCompletion(classId, periodId);
    }

    @PostMapping("/finance/homeroom/classes/{classId}/remind")
    public ClassReminderResult remindHomeroomClass(@PathVariable String classId,
                                                    @RequestParam(required = false) String periodId) {
        CurrentUser me = CurrentUserHolder.require();
        CurrentUserHolder.requireRole("TEACHER");
        return finance.remindHomeroomClass(me.id(), classId, periodId);
    }

    @PostMapping("/finance/homeroom/invoices/{invoiceId}/remind")
    public ClassReminderResult remindHomeroomInvoice(@PathVariable String invoiceId) {
        CurrentUser me = CurrentUserHolder.require();
        CurrentUserHolder.requireRole("TEACHER");
        return finance.remindHomeroomInvoice(me.id(), invoiceId);
    }

    @GetMapping("/finance/overview")
    public Map<String, Object> overview() {
        CurrentUserHolder.requireRole("ADMIN", "ACCOUNTANT");
        return finance.financeOverview();
    }

    @GetMapping("/invoices/{id}")
    public Map<String, Object> invoiceDetail(@PathVariable String id) {
        CurrentUser me = CurrentUserHolder.require();
        CurrentUserHolder.requireRole("ADMIN", "ACCOUNTANT", "PARENT", "STUDENT");
        Invoice inv = finance.getInvoice(id);
        if (me.isParent() && !me.id().equals(inv.getParentId())) {
            users.assertParentOf(me.id(), inv.getStudentId());
        } else if (me.isStudent() && !me.id().equals(inv.getStudentId())) {
            throw ApiException.forbidden("Không phải hóa đơn của bạn");
        }
        return finance.invoiceDetail(id);
    }

    // ----- Thanh toán -----
    @PostMapping("/payments")
    public Map<String, Object> pay(@Valid @RequestBody PayRequest r, HttpServletRequest request) {
        CurrentUser me = CurrentUserHolder.require();
        CurrentUserHolder.requireRole("PARENT", "ACCOUNTANT");
        Invoice inv = finance.getInvoice(r.invoiceId());
        if (me.isParent() && !me.id().equals(inv.getParentId())) {
            users.assertParentOf(me.id(), inv.getStudentId());
        }
        String forwarded = request.getHeader("X-Forwarded-For");
        String clientIp = forwarded == null || forwarded.isBlank() ? request.getRemoteAddr() : forwarded.split(",")[0].trim();
        return finance.pay(r, clientIp);
    }

    @PostMapping("/payments/cash")
    public Map<String, Object> recordCash(@Valid @RequestBody CashPaymentRequest request) {
        CurrentUserHolder.requireRole("ACCOUNTANT");
        return finance.recordCashPayment(request.invoiceId(), request.amount());
    }

    @PostMapping("/invoices/{id}/remind")
    public void remindInvoice(@PathVariable String id) {
        CurrentUserHolder.requireRole("ACCOUNTANT");
        finance.remindInvoice(id);
    }

    @PostMapping("/payments/{paymentId}/submitted")
    public Map<String, Object> markVietQrSubmitted(@PathVariable String paymentId) {
        CurrentUser me = CurrentUserHolder.require();
        CurrentUserHolder.requireRole("PARENT", "ACCOUNTANT");
        Payment payment = finance.getPayment(paymentId);
        Invoice invoice = finance.getInvoice(payment.getInvoiceId());
        if (me.isParent() && !me.id().equals(invoice.getParentId())) {
            users.assertParentOf(me.id(), invoice.getStudentId());
        }
        return finance.markVietQrSubmitted(paymentId);
    }

    @GetMapping("/payments/vietqr/pending")
    public List<Map<String, Object>> pendingVietQrPayments() {
        CurrentUserHolder.requireRole("ADMIN", "ACCOUNTANT");
        return finance.pendingVietQrPayments();
    }

    @GetMapping("/payments/vietqr/receipts")
    public List<Map<String, Object>> vietQrReceiptDeliveries() {
        CurrentUserHolder.requireRole("ADMIN", "ACCOUNTANT");
        return finance.vietQrReceiptDeliveries();
    }

    @GetMapping("/payments/{paymentId}/status")
    public Map<String, Object> vietQrPaymentStatus(@PathVariable String paymentId) {
        CurrentUser me = CurrentUserHolder.require();
        CurrentUserHolder.requireRole("ADMIN", "ACCOUNTANT", "PARENT");
        Payment payment = finance.getPayment(paymentId);
        Invoice invoice = finance.getInvoice(payment.getInvoiceId());
        if (me.isParent() && !me.id().equals(invoice.getParentId())) {
            users.assertParentOf(me.id(), invoice.getStudentId());
        }
        return finance.vietQrPaymentStatus(paymentId);
    }

    @PostMapping("/payments/{paymentId}/confirm-vietqr")
    public Map<String, Object> confirmVietQr(@PathVariable String paymentId,
                                             @RequestBody(required = false) VietQrConfirmationRequest request) {
        CurrentUserHolder.requireRole("ACCOUNTANT");
        return finance.confirmVietQrPayment(paymentId, request == null ? null : request.bankTransactionRef());
    }

    @PostMapping("/payments/{paymentId}/reject-vietqr")
    public Map<String, Object> rejectVietQr(@PathVariable String paymentId) {
        CurrentUserHolder.requireRole("ACCOUNTANT");
        return finance.rejectVietQrPayment(paymentId);
    }

    @PostMapping("/payments/{paymentId}/resend-receipt")
    public Map<String, Object> resendVietQrReceipt(@PathVariable String paymentId) {
        CurrentUserHolder.requireRole("ACCOUNTANT");
        return finance.resendVietQrReceipt(paymentId);
    }

    @GetMapping("/payments")
    public List<Payment> payments(@RequestParam(required = false) String invoiceId) {
        CurrentUser me = CurrentUserHolder.require();
        CurrentUserHolder.requireRole("ADMIN", "ACCOUNTANT", "PARENT", "STUDENT");
        if (invoiceId == null || invoiceId.isBlank()) {
            if (!me.isAdmin() && !me.isAccountant()) {
                throw ApiException.badRequest("Cần chọn hóa đơn để xem lịch sử thanh toán");
            }
            return finance.allPayments();
        }
        Invoice inv = finance.getInvoice(invoiceId);
        if (me.isParent() && !me.id().equals(inv.getParentId())) {
            users.assertParentOf(me.id(), inv.getStudentId());
        } else if (me.isStudent() && !me.id().equals(inv.getStudentId())) {
            throw ApiException.forbidden("Không phải hóa đơn của bạn");
        }
        return finance.paymentsOf(invoiceId);
    }

    private void assertHomeroomClass(String teacherId, String classId) {
        if (classId == null || classId.isBlank()) {
            throw ApiException.badRequest("Giáo viên cần chọn lớp chủ nhiệm để xem công nợ");
        }
        if (!teacherId.equals(structure.getClass(classId).getHomeroomTeacherId())) {
            throw ApiException.forbidden("Bạn chỉ được xem công nợ lớp mình chủ nhiệm");
        }
    }
}
