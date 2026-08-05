package com.sse.app.finance;

import com.sse.app.audit.AuditService;
import com.sse.app.finance.FinanceDtos.*;
import com.sse.app.identity.UserService;
import com.sse.app.security.CurrentUser;
import com.sse.app.security.CurrentUserHolder;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

/** A7/D4: fee periods, invoices and payment history. */
@RestController
public class FinanceController {

    private final FinanceService finance;
    private final PaymentService paymentService;
    private final PaymentProofService paymentProofs;
    private final PaymentHistoryService paymentHistory;
    private final PaymentReceiptService paymentReceipts;
    private final PaymentRefundService paymentRefunds;
    private final PaymentReconciliationService reconciliations;
    private final FinanceReminderService reminders;
    private final BankStatementImportService bankStatements;
    private final UserService users;
    private final AuditService audit;

    public FinanceController(FinanceService finance, PaymentService paymentService,
                             PaymentProofService paymentProofs,
                             PaymentHistoryService paymentHistory,
                             PaymentReceiptService paymentReceipts,
                             PaymentRefundService paymentRefunds,
                             PaymentReconciliationService reconciliations,
                             FinanceReminderService reminders,
                             BankStatementImportService bankStatements,
                             UserService users, AuditService audit) {
        this.finance = finance;
        this.paymentService = paymentService;
        this.paymentProofs = paymentProofs;
        this.paymentHistory = paymentHistory;
        this.paymentReceipts = paymentReceipts;
        this.paymentRefunds = paymentRefunds;
        this.reconciliations = reconciliations;
        this.reminders = reminders;
        this.bankStatements = bankStatements;
        this.users = users;
        this.audit = audit;
    }

    @GetMapping("/fee-periods")
    public List<FeePeriod> periods() {
        CurrentUserHolder.requireRole("ADMIN");
        return finance.listPeriods();
    }

    @PostMapping("/fee-periods")
    public FeePeriod createPeriod(@Valid @RequestBody CreateFeePeriodRequest request) {
        CurrentUser actor = CurrentUserHolder.require();
        CurrentUserHolder.requireRole("ADMIN");
        FeePeriod period = finance.createPeriod(request);
        record(actor, "CREATE", "fee_period", period.getId(),
                "Tạo đợt thu " + period.getCode() + " - " + period.getName());
        return period;
    }

    @PutMapping("/fee-periods/{id}/metadata")
    public FeePeriod updatePeriodMetadata(@PathVariable String id,
                                          @Valid @RequestBody UpdateFeePeriodMetadataRequest request) {
        CurrentUser actor = CurrentUserHolder.require();
        CurrentUserHolder.requireRole("ADMIN");
        FeePeriod before = finance.listPeriods().stream()
                .filter(period -> id.equals(period.getId()))
                .findFirst()
                .orElseThrow(() -> com.sse.app.common.ApiException.notFound("Đợt thu"));
        String oldValue = periodMetadata(before);
        FeePeriod updated = finance.updatePeriodMetadata(id, request);
        record(actor, "UPDATE", "fee_period", updated.getId(),
                "Cập nhật phân loại đợt thu " + updated.getCode()
                        + "; trước={" + oldValue + "}; sau={" + periodMetadata(updated) + "}");
        return updated;
    }

    @GetMapping("/fee-periods/{id}/items")
    public List<FeePeriodItem> items(@PathVariable String id) {
        CurrentUserHolder.requireRole("ADMIN");
        return finance.itemsOf(id);
    }

    @PostMapping("/fee-periods/{id}/items")
    public FeePeriodItem addItem(@PathVariable String id, @Valid @RequestBody AddFeeItemRequest request) {
        CurrentUser actor = CurrentUserHolder.require();
        CurrentUserHolder.requireRole("ADMIN");
        FeePeriodItem item = finance.addItem(id, request);
        record(actor, "CREATE", "fee_period_item", item.getId(),
                "Thêm khoản thu " + item.getName() + "; amount=" + item.getAmount() + "; period=" + id);
        return item;
    }

    @DeleteMapping("/fee-periods/{id}/items/{itemId}")
    public void deleteItem(@PathVariable String id, @PathVariable String itemId) {
        CurrentUser actor = CurrentUserHolder.require();
        CurrentUserHolder.requireRole("ADMIN");
        finance.deleteItem(id, itemId);
        record(actor, "DELETE", "fee_period_item", itemId,
                "Xóa khoản thu khỏi đợt " + id);
    }

    @PostMapping("/fee-periods/{id}/open")
    public FeePeriod open(@PathVariable String id) {
        CurrentUser actor = CurrentUserHolder.require();
        CurrentUserHolder.requireRole("ADMIN");
        FeePeriod period = finance.open(id);
        record(actor, "UPDATE", "fee_period", period.getId(),
                "Mở đợt thu " + period.getCode());
        return period;
    }

    @GetMapping("/fee-periods/{id}/preview")
    public InvoicePreview preview(@PathVariable String id) {
        CurrentUserHolder.requireRole("ADMIN");
        return finance.previewInvoices(id);
    }

    @PostMapping("/fee-periods/{id}/close")
    public FeePeriod close(@PathVariable String id) {
        CurrentUser actor = CurrentUserHolder.require();
        CurrentUserHolder.requireRole("ADMIN");
        FeePeriod period = finance.close(id);
        record(actor, "UPDATE", "fee_period", period.getId(),
                "Đóng đợt thu " + period.getCode());
        return period;
    }

    @PostMapping("/fee-periods/{id}/cancel")
    public FeePeriod cancel(@PathVariable String id,
                            @Valid @RequestBody(required = false) CancelFeePeriodRequest request) {
        CurrentUser actor = CurrentUserHolder.require();
        CurrentUserHolder.requireRole("ADMIN");
        String reason = request == null ? null : request.reason();
        FeePeriod period = finance.cancel(id, reason);
        record(actor, "CANCEL", "fee_period", period.getId(),
                "Hủy đợt thu " + period.getCode() + (reason == null || reason.isBlank() ? "" : "; lý do=" + reason.trim()));
        return period;
    }

    @PostMapping("/fee-periods/{id}/recall")
    public FeePeriod recall(@PathVariable String id) {
        CurrentUser actor = CurrentUserHolder.require();
        CurrentUserHolder.requireRole("ADMIN");
        FeePeriod period = finance.recallToDraft(id);
        record(actor, "UPDATE", "fee_period", period.getId(),
                "Thu hồi đợt thu " + period.getCode() + " về nháp; xóa các hóa đơn chưa thanh toán");
        return period;
    }

    @PostMapping("/fee-periods/{id}/generate-invoices")
    public List<Invoice> generate(@PathVariable String id) {
        CurrentUser actor = CurrentUserHolder.require();
        CurrentUserHolder.requireRole("ADMIN");
        List<Invoice> created = finance.generateInvoices(id);
        record(actor, "CREATE", "invoice_batch", id,
                "Sinh invoice idempotent; created=" + created.size());
        return created;
    }

    @GetMapping("/invoices")
    public List<Invoice> invoices(@RequestParam(required = false) String studentId,
                                  @RequestParam(required = false) String parentId,
                                  @RequestParam(required = false) String status) {
        CurrentUser actor = CurrentUserHolder.require();
        CurrentUserHolder.requireRole("ADMIN", "PARENT", "STUDENT");
        if (actor.isParent()) {
            if (studentId != null) {
                users.assertParentOf(actor.id(), studentId);
            } else {
                parentId = actor.id();
            }
        } else if (actor.isStudent()) {
            studentId = actor.id();
            parentId = null;
        }
        return finance.listInvoices(studentId, parentId, status);
    }

    @GetMapping("/students/{studentId}/invoices")
    public List<Invoice> studentInvoices(@PathVariable String studentId,
                                         @RequestParam(required = false) String status) {
        CurrentUser actor = CurrentUserHolder.require();
        CurrentUserHolder.requireRole("ADMIN", "PARENT", "STUDENT");
        if (actor.isParent()) {
            users.assertParentOf(actor.id(), studentId);
        } else if (actor.isStudent() && !actor.id().equals(studentId)) {
            throw com.sse.app.common.ApiException.forbidden("Không phải hóa đơn của bạn");
        }
        return finance.listInvoices(studentId, null, status);
    }

    @GetMapping("/invoices/{id}")
    public Map<String, Object> invoiceDetail(@PathVariable String id) {
        CurrentUser actor = CurrentUserHolder.require();
        CurrentUserHolder.requireRole("ADMIN", "PARENT", "STUDENT");
        Invoice invoice = finance.getInvoice(id);
        assertInvoiceAccess(actor, invoice);
        return finance.invoiceDetail(id);
    }

    @PostMapping("/invoices/{id}/remind")
    public Invoice remind(@PathVariable String id) {
        CurrentUser actor = CurrentUserHolder.require();
        CurrentUserHolder.requireRole("ADMIN");
        Invoice invoice = finance.remindOverdue(id);
        record(actor, "NOTIFY", "invoice", invoice.getId(),
                "Gửi nhắc nợ hóa đơn " + invoice.getCode() + " cho học sinh và phụ huynh");
        return invoice;
    }

    @PostMapping("/finance/reminders/run")
    public FinanceReminderRunResponse runReminders() {
        CurrentUser actor = CurrentUserHolder.require();
        CurrentUserHolder.requireRole("ADMIN");
        FinanceReminderRunResponse response = reminders.run();
        record(actor, "RUN_DEBT_REMINDERS", "invoice_batch",
                response.executedAt().toString(),
                "scanned=" + response.scanned()
                        + "; reminded=" + response.reminded()
                        + "; skipped=" + response.skipped());
        return response;
    }

    @PostMapping("/payments")
    public PaymentInitResponse pay(@Valid @RequestBody PayRequest request, HttpServletRequest httpRequest) {
        CurrentUser actor = CurrentUserHolder.require();
        CurrentUserHolder.requireRole("PARENT", "ADMIN");
        Invoice invoice = finance.getInvoice(request.invoiceId());
        assertInvoiceAccess(actor, invoice);

        PaymentInitResponse result = paymentService.create(request, actor.isAdmin(), clientIp(httpRequest));
        Payment payment = result.payment();
        record(actor, "PAYMENT", "payment", payment.getId(),
                "Khởi tạo payment; invoice=" + invoice.getId()
                        + "; method=" + payment.getMethod()
                        + "; amount=" + payment.getAmount()
                        + "; status=" + payment.getStatus());
        return result;
    }

    @PostMapping("/payments/{id}/cash-confirm")
    public PaymentInitResponse confirmCash(@PathVariable String id) {
        CurrentUser actor = CurrentUserHolder.require();
        CurrentUserHolder.requireRole("ADMIN");
        PaymentInitResponse result = paymentService.confirmCash(id, actor.id());
        record(actor, "PAYMENT", "payment", id,
                "Xác nhận thu tiền mặt; invoice=" + result.invoice().getId()
                        + "; amount=" + result.payment().getAmount()
                        + "; status=" + result.payment().getStatus());
        return result;
    }

    @PostMapping({"/payments/{provider}/ipn", "/payments/{provider}/callback"})
    public GatewayCallbackResponse paymentIpn(@PathVariable String provider,
                                              @RequestBody Map<String, String> payload) {
        return paymentService.processIpn(provider, payload);
    }

    @PostMapping("/payments/momo/ipn")
    @ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
    public void momoIpn(@RequestBody Map<String, String> payload) {
        paymentService.processIpn("MOMO", payload);
    }

    @GetMapping("/payments/vnpay/ipn")
    public VnpayIpnResponse vnpayIpn(@RequestParam Map<String, String> payload) {
        GatewayCallbackResponse result = paymentService.processIpn("VNPAY", payload);
        if (result.accepted()) {
            boolean alreadyConfirmed = !result.processed()
                    && ("SUCCESS".equals(result.paymentStatus()) || "FAILED".equals(result.paymentStatus()));
            return alreadyConfirmed
                    ? new VnpayIpnResponse("02", "Order already confirmed")
                    : new VnpayIpnResponse("00", "Confirm Success");
        }
        String rspCode = switch (result.errorCode() == null ? "" : result.errorCode()) {
            case "SIGNATURE_INVALID" -> "97";
            case "PAYMENT_NOT_FOUND" -> "01";
            case "AMOUNT_MISMATCH" -> "04";
            default -> "99";
        };
        String message = switch (rspCode) {
            case "97" -> "Invalid signature";
            case "01" -> "Order not found";
            case "04" -> "Invalid amount";
            default -> "Unknown error";
        };
        return new VnpayIpnResponse(rspCode, message);
    }

    @GetMapping("/payments/{provider}/return")
    public BrowserReturnResponse paymentReturn(@PathVariable String provider,
                                               @RequestParam Map<String, String> payload) {
        return paymentService.browserReturn(provider, payload);
    }

    @GetMapping("/payments")
    public List<Payment> payments(@RequestParam String invoiceId) {
        CurrentUser actor = CurrentUserHolder.require();
        CurrentUserHolder.requireRole("ADMIN", "PARENT", "STUDENT");
        Invoice invoice = finance.getInvoice(invoiceId);
        assertInvoiceAccess(actor, invoice);
        return paymentService.paymentsOf(invoiceId);
    }

    @GetMapping("/payments/{id}")
    public Payment payment(@PathVariable String id) {
        CurrentUser actor = CurrentUserHolder.require();
        CurrentUserHolder.requireRole("ADMIN", "PARENT", "STUDENT");
        Payment payment = paymentService.get(id);
        assertInvoiceAccess(actor, finance.getInvoice(payment.getInvoiceId()));
        return payment;
    }

    @GetMapping("/payments/{id}/gateway-transactions")
    public List<PaymentGatewayTransaction> gatewayTransactions(@PathVariable String id) {
        CurrentUserHolder.requireRole("ADMIN");
        return paymentService.gatewayTransactionsOf(id);
    }

    @GetMapping("/payment-history")
    public List<PaymentHistoryResponse> paymentHistory(
            @RequestParam(required = false) String studentId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String method) {
        CurrentUser actor = CurrentUserHolder.require();
        CurrentUserHolder.requireRole("ADMIN", "PARENT", "STUDENT");
        String parentId = null;
        if (actor.isParent()) {
            if (studentId != null && !studentId.isBlank()) users.assertParentOf(actor.id(), studentId);
            else parentId = actor.id();
        } else if (actor.isStudent()) {
            studentId = actor.id();
        }
        return paymentHistory.list(studentId, parentId, status, method);
    }

    @GetMapping("/payment-refunds")
    public List<PaymentRefundResponse> paymentRefunds(
            @RequestParam(required = false) String studentId,
            @RequestParam(required = false) String status) {
        CurrentUser actor = CurrentUserHolder.require();
        CurrentUserHolder.requireRole("ADMIN", "PARENT", "STUDENT");
        String parentId = null;
        if (actor.isParent()) {
            if (studentId != null && !studentId.isBlank()) users.assertParentOf(actor.id(), studentId);
            else parentId = actor.id();
        } else if (actor.isStudent()) {
            studentId = actor.id();
        }
        return paymentRefunds.list(studentId, parentId, status);
    }

    @PostMapping("/payments/{id}/refunds")
    public PaymentRefundResponse requestRefund(@PathVariable String id,
                                               @Valid @RequestBody CreateRefundRequest request) {
        CurrentUser actor = CurrentUserHolder.require();
        CurrentUserHolder.requireRole("ADMIN");
        PaymentRefundResponse refund = paymentRefunds.request(id, request.amount(), request.reason(), actor.id());
        record(actor, "REQUEST_REFUND", "payment_refund", refund.id(),
                "Yêu cầu hoàn " + refund.amount() + " VND; payment=" + id + "; lý do=" + refund.reason());
        return refund;
    }

    @PostMapping("/payment-refunds/{id}/approve")
    public PaymentRefundResponse approveRefund(@PathVariable String id,
                                               @Valid @RequestBody ApproveRefundRequest request) {
        CurrentUser actor = CurrentUserHolder.require();
        CurrentUserHolder.requireRole("ADMIN");
        PaymentRefundResponse refund = paymentRefunds.approve(id, request.method(), request.reference(), actor.id());
        record(actor, "APPROVE_REFUND", "payment_refund", refund.id(),
                "Duyệt hoàn " + refund.amount() + " VND; type=" + refund.refundType()
                        + "; method=" + refund.refundMethod()
                        + (refund.refundReference() == null ? "" : "; reference=" + refund.refundReference())
                        + "; invoicePaid=" + refund.invoicePaidAmountBefore()
                        + "->" + refund.invoicePaidAmountAfter());
        return refund;
    }

    @PostMapping("/payment-refunds/{id}/reject")
    public PaymentRefundResponse rejectRefund(@PathVariable String id,
                                              @Valid @RequestBody RejectRefundRequest request) {
        CurrentUser actor = CurrentUserHolder.require();
        CurrentUserHolder.requireRole("ADMIN");
        PaymentRefundResponse refund = paymentRefunds.reject(id, request.reason(), actor.id());
        record(actor, "REJECT_REFUND", "payment_refund", refund.id(),
                "Từ chối hoàn tiền; lý do=" + request.reason().trim());
        return refund;
    }

    @PostMapping("/payment-refunds/{id}/cancel")
    public PaymentRefundResponse cancelRefund(@PathVariable String id,
                                              @Valid @RequestBody CancelRefundRequest request) {
        CurrentUser actor = CurrentUserHolder.require();
        CurrentUserHolder.requireRole("ADMIN");
        PaymentRefundResponse refund = paymentRefunds.cancel(id, request.reason(), actor.id());
        record(actor, "CANCEL_REFUND", "payment_refund", refund.id(),
                "Hủy yêu cầu hoàn tiền; lý do=" + request.reason().trim());
        return refund;
    }

    @PostMapping("/finance/reconciliations")
    public ReconciliationResponse runReconciliation(@Valid @RequestBody ReconciliationRequest request) {
        CurrentUser actor = CurrentUserHolder.require();
        CurrentUserHolder.requireRole("ADMIN");
        ReconciliationResponse result = reconciliations.run(request, actor.id());
        record(actor, "RECONCILE", "payment_reconciliation", result.id(),
                "Đối soát " + result.fromDate() + " đến " + result.toDate()
                        + "; method=" + (result.method() == null ? "ALL" : result.method())
                        + "; gross=" + result.grossAmount()
                        + "; refunds=" + result.refundAmount() + "; net=" + result.netAmount()
                        + "; discrepancies=" + result.discrepancyCount());
        return result;
    }

    @GetMapping("/finance/reconciliations")
    public List<ReconciliationResponse> reconciliations() {
        CurrentUserHolder.requireRole("ADMIN");
        return reconciliations.list();
    }

    @GetMapping("/finance/reconciliations/{id}")
    public ReconciliationResponse reconciliation(@PathVariable String id) {
        CurrentUserHolder.requireRole("ADMIN");
        return reconciliations.get(id);
    }

    @PostMapping("/payments/{id}/receipt/issue")
    public PaymentReceiptResponse issuePaymentReceipt(@PathVariable String id) {
        CurrentUser actor = CurrentUserHolder.require();
        CurrentUserHolder.requireRole("ADMIN");
        PaymentReceipt receipt = paymentReceipts.issueForPayment(id, actor.id());
        record(actor, "ISSUE_RECEIPT", "payment_receipt", receipt.getId(),
                "Phát hành biên nhận " + receipt.getReceiptNumber()
                        + "; payment=" + id + "; status=" + receipt.getStatus());
        return paymentReceipts.toResponse(receipt);
    }

    @PostMapping("/payments/{id}/receipt/void")
    public PaymentReceiptResponse voidPaymentReceipt(
            @PathVariable String id,
            @Valid @RequestBody VoidReceiptRequest request) {
        CurrentUser actor = CurrentUserHolder.require();
        CurrentUserHolder.requireRole("ADMIN");
        PaymentReceipt receipt = paymentReceipts.voidForPayment(
                id, request.reason(), actor.id());
        record(actor, "VOID_RECEIPT", "payment_receipt", receipt.getId(),
                "Thu hồi biên nhận " + receipt.getReceiptNumber()
                        + "; lý do=" + request.reason().trim());
        return paymentReceipts.toResponse(receipt);
    }

    @PostMapping("/payments/{id}/receipt/reissue")
    public PaymentReceiptResponse reissuePaymentReceipt(
            @PathVariable String id) {
        CurrentUser actor = CurrentUserHolder.require();
        CurrentUserHolder.requireRole("ADMIN");
        PaymentReceipt receipt =
                paymentReceipts.reissueForPayment(id, actor.id());
        record(actor, "REISSUE_RECEIPT", "payment_receipt", receipt.getId(),
                "Cấp lại biên nhận " + receipt.getReceiptNumber()
                        + "; trạng thái=" + receipt.getStatus());
        return paymentReceipts.toResponse(receipt);
    }

    @GetMapping("/payments/{id}/receipt")
    public PaymentReceiptDownloadResponse downloadPaymentReceipt(@PathVariable String id) {
        CurrentUser actor = CurrentUserHolder.require();
        CurrentUserHolder.requireRole("ADMIN", "PARENT", "STUDENT");
        Payment payment = paymentService.get(id);
        assertInvoiceAccess(actor, finance.getInvoice(payment.getInvoiceId()));
        PaymentReceiptDownloadResponse result = paymentReceipts.downloadForPayment(id);
        if (actor.isAdmin()) {
            record(actor, "DOWNLOAD_RECEIPT", "payment_receipt", result.receipt().id(),
                    "Tải biên nhận " + result.receipt().receiptNumber() + "; payment=" + id);
        }
        return result;
    }

    @PostMapping("/payments/{id}/proofs")
    public PaymentProofResponse submitPaymentProof(@PathVariable String id,
                                                   @Valid @RequestBody SubmitPaymentProofRequest request) {
        CurrentUser actor = CurrentUserHolder.require();
        CurrentUserHolder.requireRole("PARENT");
        Payment payment = paymentService.get(id);
        assertInvoiceAccess(actor, finance.getInvoice(payment.getInvoiceId()));
        PaymentProofResponse proof = paymentProofs.submit(id, request.fileId(), actor.id(), actor.role());
        record(actor, "SUBMIT", "payment_proof", proof.id(),
                "Gửi biên lai; payment=" + id + "; invoice=" + proof.invoiceCode()
                        + "; file=" + proof.fileName());
        return proof;
    }

    @GetMapping("/payment-proofs")
    public List<PaymentProofResponse> paymentProofs(@RequestParam(required = false) String status) {
        CurrentUser actor = CurrentUserHolder.require();
        CurrentUserHolder.requireRole("ADMIN", "PARENT");
        return actor.isAdmin()
                ? paymentProofs.listForAdmin(status)
                : paymentProofs.listForParent(actor.id());
    }

    @GetMapping("/payments/{id}/proofs")
    public List<PaymentProofResponse> paymentProofsOf(@PathVariable String id) {
        CurrentUser actor = CurrentUserHolder.require();
        CurrentUserHolder.requireRole("ADMIN", "PARENT");
        Payment payment = paymentService.get(id);
        assertInvoiceAccess(actor, finance.getInvoice(payment.getInvoiceId()));
        return paymentProofs.listForPayment(id);
    }

    @PostMapping("/payment-proofs/{id}/approve")
    public PaymentProofDecisionResponse approvePaymentProof(@PathVariable String id) {
        CurrentUser actor = CurrentUserHolder.require();
        CurrentUserHolder.requireRole("ADMIN");
        PaymentProofDecisionResponse result = paymentProofs.approve(id, actor.id());
        record(actor, "APPROVE", "payment_proof", id,
                "Duyệt biên lai; payment=" + result.payment().getId()
                        + "; invoice=" + result.invoice().getCode()
                        + "; amount=" + result.payment().getAmount());
        return result;
    }

    @PostMapping({"/payment-proofs/{id}/request-repayment", "/payment-proofs/{id}/reject"})
    public PaymentProofDecisionResponse requestPaymentProofRepayment(@PathVariable String id,
                                                                     @Valid @RequestBody ReviewPaymentProofRequest request) {
        CurrentUser actor = CurrentUserHolder.require();
        CurrentUserHolder.requireRole("ADMIN");
        PaymentProofDecisionResponse result = paymentProofs.requestRepayment(id, actor.id(), request.reason());
        record(actor, "REQUEST_REPAYMENT", "payment_proof", id,
                "Yêu cầu thanh toán lại; payment=" + result.payment().getId()
                        + "; invoice=" + result.invoice().getCode()
                        + "; lý do=" + request.reason().trim());
        return result;
    }

    @PostMapping(value = "/finance/bank-statements/import",
            consumes = "multipart/form-data")
    public BankStatementImportResponse importBankStatement(
            @RequestPart("file") MultipartFile file) {
        CurrentUser actor = CurrentUserHolder.require();
        CurrentUserHolder.requireRole("ADMIN");
        BankStatementImportResponse response =
                bankStatements.importFile(file, actor.id());
        record(actor, "IMPORT_BANK_STATEMENT", "bank_statement_import",
                response.importBatchId(),
                "total=" + response.total() + "; matched=" + response.matched()
                        + "; mismatched=" + response.mismatched()
                        + "; unmatched=" + response.unmatched()
                        + "; duplicates=" + response.duplicates());
        return response;
    }

    @GetMapping("/finance/bank-statements")
    public List<BankStatementEntryResponse> bankStatements(
            @RequestParam(required = false) String status) {
        CurrentUserHolder.requireRole("ADMIN");
        return bankStatements.list(status);
    }

    private void assertInvoiceAccess(CurrentUser actor, Invoice invoice) {
        if (actor.isParent() && !actor.id().equals(invoice.getParentId())) {
            users.assertParentOf(actor.id(), invoice.getStudentId());
        } else if (actor.isStudent() && !actor.id().equals(invoice.getStudentId())) {
            throw com.sse.app.common.ApiException.forbidden("Không phải hóa đơn của bạn");
        }
    }

    private void record(CurrentUser actor, String action, String entityType, String entityId, String detail) {
        audit.record(actor.id(), users.fullNameOf(actor.id()), actor.role(), action,
                "finance", entityType, entityId, detail);
    }

    private String periodMetadata(FeePeriod period) {
        return "feeType=" + String.valueOf(period.getFeeType())
                + ", academicYearId=" + String.valueOf(period.getAcademicYearId())
                + ", semesterId=" + String.valueOf(period.getSemesterId());
    }

    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",", 2)[0].trim();
        }
        String realIp = request.getHeader("X-Real-IP");
        return realIp == null || realIp.isBlank() ? request.getRemoteAddr() : realIp.trim();
    }
}
