package com.sse.app.finance;

import com.sse.app.academic.structure.StructureService;
import com.sse.app.common.ApiException;
import com.sse.app.common.Ids;
import com.sse.app.finance.FinanceDtos.*;
import com.sse.app.identity.UserDto;
import com.sse.app.identity.UserService;
import com.sse.app.notification.NotificationService;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.scheduling.annotation.Scheduled;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;

/** A7: Tài chính nội bộ — đợt thu, sinh hóa đơn (2.8), thanh toán (2.9, sandbox). */
@Service
public class FinanceService {

    private final FeePeriodRepository periods;
    private final FeePeriodItemRepository periodItems;
    private final InvoiceRepository invoices;
    private final InvoiceItemRepository invoiceItems;
    private final PaymentRepository payments;
    private final PaymentGatewayTransactionRepository gatewayTransactions;
    private final StructureService structure;
    private final UserService users;
    private final NotificationService notifications;
    private final String paymentMode;
    private final String callbackSecret;

    public FinanceService(FeePeriodRepository periods, FeePeriodItemRepository periodItems,
                          InvoiceRepository invoices, InvoiceItemRepository invoiceItems,
                          PaymentRepository payments, PaymentGatewayTransactionRepository gatewayTransactions,
                          StructureService structure,
                          UserService users, NotificationService notifications,
                          @Value("${sse.payments.mode:disabled}") String paymentMode,
                          @Value("${sse.payments.callback-secret:}") String callbackSecret) {
        this.periods = periods;
        this.periodItems = periodItems;
        this.invoices = invoices;
        this.invoiceItems = invoiceItems;
        this.payments = payments;
        this.gatewayTransactions = gatewayTransactions;
        this.structure = structure;
        this.users = users;
        this.notifications = notifications;
        this.paymentMode = paymentMode;
        this.callbackSecret = callbackSecret;
    }

    // ---------- Đợt thu ----------
    public List<FeePeriod> listPeriods() { return periods.findAll(); }

    public FeePeriod createPeriod(CreateFeePeriodRequest r) {
        String code = r.code().trim();
        if (periods.findByCode(code).isPresent()) throw ApiException.conflict("Mã đợt thu đã tồn tại");
        if (r.academicYearId() != null && !r.academicYearId().isBlank()) structure.getYear(r.academicYearId());
        return periods.save(FeePeriod.builder()
                .id(r.id() == null || r.id().isBlank() ? Ids.gen("fp") : r.id())
                .code(code).name(r.name()).status("DRAFT")
                .academicYearId(r.academicYearId()).applyToGrades(r.applyToGrades())
                .dueDate(r.dueDate()).createdAt(Instant.now()).build());
    }

    public List<FeePeriodItem> itemsOf(String periodId) { return periodItems.findByFeePeriodId(periodId); }

    public FeePeriodItem addItem(String periodId, AddFeeItemRequest r) {
        FeePeriod period = getPeriod(periodId);
        if (!"DRAFT".equals(period.getStatus())) {
            throw ApiException.conflict("Không thể thay đổi khoản thu sau khi đợt thu đã mở");
        }
        return periodItems.save(FeePeriodItem.builder()
                .id(r.id() == null || r.id().isBlank() ? Ids.gen("fpi") : r.id())
                .feePeriodId(periodId).name(r.name()).amount(r.amount()).gradeLevel(r.gradeLevel()).build());
    }

    public FeePeriod open(String periodId) {
        FeePeriod p = getPeriod(periodId);
        if ("OPEN".equals(p.getStatus())) return p;
        if (!"DRAFT".equals(p.getStatus())) throw ApiException.conflict("Đợt thu không còn ở trạng thái nháp");
        if (periodItems.findByFeePeriodId(periodId).isEmpty()) {
            throw ApiException.badRequest("Cần thêm ít nhất một khoản thu trước khi mở đợt thu");
        }
        p.setStatus("OPEN");
        return periods.save(p);
    }

    private FeePeriod getPeriod(String id) {
        return periods.findById(id).orElseThrow(() -> ApiException.notFound("Đợt thu"));
    }

    // ---------- Sinh hóa đơn (flowchart 2.8) ----------
    @Transactional
    public List<Invoice> generateInvoices(String periodId) {
        FeePeriod p = getPeriod(periodId);
        if (!"OPEN".equals(p.getStatus())) throw ApiException.badRequest("Đợt thu phải ở trạng thái OPEN");

        Set<String> gradeFilter = parseGrades(p.getApplyToGrades());
        List<FeePeriodItem> items = itemsOf(periodId);
        if (items.isEmpty()) throw ApiException.badRequest("Đợt thu chưa có khoản thu");
        List<Invoice> created = new ArrayList<>();

        for (UserDto s : users.list("STUDENT", null, null)) {
            String gl = structure.gradeLevelOf(s.classId());
            if (gradeFilter != null && (gl == null || !gradeFilter.contains(gl))) continue;

            List<FeePeriodItem> applicable = items.stream()
                    .filter(it -> it.getGradeLevel() == null || it.getGradeLevel().equals(gl))
                    .toList();
            if (applicable.isEmpty()) continue;

            Optional<Invoice> existing = invoices.findByFeePeriodIdAndStudentId(periodId, s.id());
            if (existing.isPresent()) {
                created.add(existing.get());
                continue;
            }

            long total = applicable.stream().mapToLong(FeePeriodItem::getAmount).sum();
            String parentId = users.parentIdsOf(s.id()).stream().findFirst().orElse(null);

            Invoice inv = invoices.save(Invoice.builder()
                    .id(Ids.gen("inv")).code("INV-" + p.getCode() + "-" + s.id())
                    .studentId(s.id()).studentName(s.fullName()).parentId(parentId)
                    .feePeriodId(periodId).totalAmount(total).paidAmount(0).status("PENDING")
                    .issuedAt(Instant.now()).dueDate(p.getDueDate()).build());

            for (FeePeriodItem it : applicable) {
                invoiceItems.save(InvoiceItem.builder().id(Ids.gen("ii"))
                        .invoiceId(inv.getId()).name(it.getName()).amount(it.getAmount()).build());
            }
            created.add(inv);

            if (parentId != null) {
                notifications.notifyUser(parentId, "INVOICE", "Hóa đơn học phí mới",
                        String.format("%s — %s: %,d₫", s.fullName(), inv.getCode(), total),
                        "INVOICE", inv.getId());
            }
        }
        return created;
    }

    private Set<String> parseGrades(String csv) {
        if (csv == null || csv.isBlank()) return null;
        Set<String> set = new HashSet<>();
        for (String g : csv.split(",")) set.add(g.trim());
        return set;
    }

    // ---------- Hóa đơn ----------
    public List<Invoice> listInvoices(String studentId, String parentId, String status) {
        List<Invoice> base;
        if (studentId != null)     base = invoices.findByStudentId(studentId);
        else if (parentId != null) base = invoices.findByParentId(parentId);
        else base = invoices.findAll();
        return base.stream().filter(i -> status == null || status.equals(i.getStatus())).toList();
    }

    public Invoice getInvoice(String id) {
        return invoices.findById(id).orElseThrow(() -> ApiException.notFound("Hóa đơn"));
    }

    public Map<String, Object> invoiceDetail(String id) {
        Invoice inv = getInvoice(id);
        Map<String, Object> m = new HashMap<>();
        m.put("invoice", inv);
        m.put("items", invoiceItems.findByInvoiceId(id));
        m.put("payments", payments.findByInvoiceId(id));
        return m;
    }

    // ---------- Thanh toán: tạo PENDING, chỉ callback có chữ ký mới ghi nhận thành công ----------
    @Transactional
    public Map<String, Object> pay(PayRequest r) {
        if (!"sandbox".equalsIgnoreCase(paymentMode)) {
            throw ApiException.serviceUnavailable(
                    "Cổng thanh toán chưa được cấu hình. Không có giao dịch nào được tạo.");
        }
        Invoice inv = getInvoice(r.invoiceId());
        long remaining = inv.getTotalAmount() - inv.getPaidAmount();
        if (remaining <= 0) throw ApiException.badRequest("Hóa đơn đã thanh toán đủ");

        String method = r.method() == null ? "VNPAY" : r.method().toUpperCase();
        if (!Set.of("VNPAY", "MOMO").contains(method)) {
            throw ApiException.badRequest("Phương thức thanh toán chỉ hỗ trợ VNPAY hoặc MOMO");
        }
        requireSandboxSecret();

        Optional<Payment> pending = payments.findFirstByInvoiceIdAndStatusOrderByCreatedAtDesc(inv.getId(), "PENDING");
        if (pending.isPresent()) return paymentResponse(inv, pending.get(),
                gatewayTransactions.findByPaymentId(pending.get().getId()).orElseThrow());

        String txnRef = "SANDBOX-" + Ids.gen("tx");
        Payment payment = payments.save(Payment.builder()
                .id(Ids.gen("pay")).invoiceId(inv.getId()).amount(remaining).method(method)
                .status("PENDING").txnRef(txnRef).createdAt(Instant.now()).build());
        PaymentGatewayTransaction transaction = gatewayTransactions.save(PaymentGatewayTransaction.builder()
                .id(Ids.gen("pgt")).paymentId(payment.getId()).txnRef(txnRef).gateway(method)
                .status("PENDING").requestPayload(canonical(txnRef, "SUCCESS", remaining))
                .signatureValid(false).createdAt(Instant.now()).updatedAt(Instant.now()).build());
        return paymentResponse(inv, payment, transaction);
    }

    @Transactional
    public Map<String, Object> recordCashPayment(String invoiceId) {
        Invoice invoice = getInvoice(invoiceId);
        long remaining = invoice.getTotalAmount() - invoice.getPaidAmount();
        if (remaining <= 0) throw ApiException.badRequest("Hóa đơn đã thanh toán đủ");
        Payment payment = payments.save(Payment.builder()
                .id(Ids.gen("pay")).invoiceId(invoice.getId()).amount(remaining).method("CASH")
                .status("SUCCESS").txnRef("CASH-" + Ids.gen("tx"))
                .createdAt(Instant.now()).paidAt(Instant.now()).build());
        invoice.setPaidAmount(invoice.getTotalAmount());
        invoice.setStatus("PAID");
        invoices.save(invoice);
        if (invoice.getParentId() != null) {
            notifications.notifyUser(invoice.getParentId(), "INVOICE", "Nhà trường đã xác nhận học phí",
                    String.format("Biên nhận %s: %,d₫ (tiền mặt)", invoice.getCode(), remaining),
                    "PAYMENT", payment.getId());
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("payment", payment);
        result.put("invoice", invoice);
        return result;
    }

    @Transactional(noRollbackFor = ApiException.class)
    public Map<String, Object> completeGatewayPayment(String gateway, PaymentCallbackRequest request) {
        if (!"sandbox".equalsIgnoreCase(paymentMode)) {
            throw ApiException.serviceUnavailable("Callback sandbox đang bị tắt");
        }
        requireSandboxSecret();
        String normalizedGateway = gateway.toUpperCase(Locale.ROOT);
        PaymentGatewayTransaction transaction = gatewayTransactions.findByTxnRef(request.txnRef())
                .orElseThrow(() -> ApiException.notFound("Giao dịch cổng thanh toán"));
        if (!normalizedGateway.equals(transaction.getGateway())) {
            throw ApiException.badRequest("Cổng thanh toán không khớp với giao dịch");
        }
        Payment payment = payments.findById(transaction.getPaymentId())
                .orElseThrow(() -> ApiException.notFound("Thanh toán"));
        Invoice invoice = getInvoice(payment.getInvoiceId());

        String status = request.status().toUpperCase(Locale.ROOT);
        if (!Set.of("SUCCESS", "FAILED").contains(status)) {
            throw ApiException.badRequest("Trạng thái callback không hợp lệ");
        }
        String payload = canonical(request.txnRef(), status, request.amount());
        boolean signatureValid = verify(payload, request.signature());
        transaction.setCallbackPayload(payload);
        transaction.setSignatureValid(signatureValid);
        transaction.setUpdatedAt(Instant.now());
        if (!signatureValid) {
            transaction.setStatus("REJECTED");
            gatewayTransactions.save(transaction);
            throw ApiException.forbidden("Chữ ký callback thanh toán không hợp lệ");
        }
        if (request.amount() != payment.getAmount()) {
            transaction.setStatus("REJECTED");
            gatewayTransactions.save(transaction);
            throw ApiException.badRequest("Số tiền callback không khớp");
        }
        if ("SUCCESS".equals(payment.getStatus())) return callbackResult(invoice, payment, transaction);

        if ("FAILED".equals(status)) {
            payment.setStatus("FAILED");
            transaction.setStatus("FAILED");
        } else {
            payment.setStatus("SUCCESS");
            payment.setPaidAt(Instant.now());
            transaction.setStatus("SUCCESS");
            invoice.setPaidAmount(Math.min(invoice.getTotalAmount(), invoice.getPaidAmount() + payment.getAmount()));
            invoice.setStatus(invoice.getPaidAmount() >= invoice.getTotalAmount() ? "PAID" : "PARTIAL");
            invoices.save(invoice);
            if (invoice.getParentId() != null) {
                notifications.notifyUser(invoice.getParentId(), "INVOICE", "Thanh toán thành công",
                        String.format("Biên nhận %s: %,d₫ (%s)", invoice.getCode(), payment.getAmount(), payment.getMethod()),
                        "PAYMENT", payment.getId());
            }
        }
        payments.save(payment);
        gatewayTransactions.save(transaction);
        return callbackResult(invoice, payment, transaction);
    }

    @Scheduled(fixedDelayString = "${sse.payments.reconciliation-interval-ms:3600000}")
    @Transactional
    public void expirePendingPayments() {
        Instant cutoff = Instant.now().minus(30, ChronoUnit.MINUTES);
        for (PaymentGatewayTransaction transaction
                : gatewayTransactions.findByStatusAndCreatedAtBefore("PENDING", cutoff)) {
            payments.findById(transaction.getPaymentId()).ifPresent(payment -> {
                if ("PENDING".equals(payment.getStatus())) {
                    payment.setStatus("FAILED");
                    payments.save(payment);
                }
            });
            transaction.setStatus("EXPIRED");
            transaction.setUpdatedAt(Instant.now());
            gatewayTransactions.save(transaction);
        }
    }

    public List<Payment> paymentsOf(String invoiceId) { return payments.findByInvoiceId(invoiceId); }

    private Map<String, Object> paymentResponse(Invoice invoice, Payment payment,
                                                PaymentGatewayTransaction transaction) {
        PaymentCallbackRequest callback = new PaymentCallbackRequest(transaction.getTxnRef(), "SUCCESS",
                payment.getAmount(), sign(canonical(transaction.getTxnRef(), "SUCCESS", payment.getAmount())));
        Map<String, Object> result = callbackResult(invoice, payment, transaction);
        result.put("callbackUrl", "/payments/callback/" + transaction.getGateway().toLowerCase(Locale.ROOT));
        result.put("sandboxCallback", callback);
        return result;
    }

    private Map<String, Object> callbackResult(Invoice invoice, Payment payment,
                                               PaymentGatewayTransaction transaction) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("payment", payment);
        result.put("invoice", invoice);
        result.put("gatewayStatus", transaction.getStatus());
        return result;
    }

    private String canonical(String txnRef, String status, long amount) {
        return txnRef + "|" + status + "|" + amount;
    }

    private String sign(String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(callbackSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("Không thể ký callback thanh toán", ex);
        }
    }

    private boolean verify(String value, String signature) {
        byte[] expected = sign(value).getBytes(StandardCharsets.US_ASCII);
        byte[] actual = signature.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.US_ASCII);
        return MessageDigest.isEqual(expected, actual);
    }

    private void requireSandboxSecret() {
        if (callbackSecret == null || callbackSecret.length() < 32) {
            throw ApiException.serviceUnavailable("Khóa ký callback thanh toán chưa được cấu hình an toàn");
        }
    }

    /** Seed: 1 đợt thu mẫu + sinh hóa đơn cho mọi HS (để demo có dữ liệu ngay). Idempotent. */
    @Transactional
    public void seedDefaultPeriodAndInvoices() {
        if (!periods.findAll().isEmpty()) return;
        FeePeriod p = periods.save(FeePeriod.builder()
                .id("fp-hk1").code("HK1-2025").name("Học phí HK1 2025-2026").status("OPEN")
                .dueDate(LocalDate.parse("2026-01-15")).createdAt(Instant.now()).build());
        periodItems.save(FeePeriodItem.builder().id("fpi-1").feePeriodId(p.getId())
                .name("Học phí").amount(1500000).build());
        periodItems.save(FeePeriodItem.builder().id("fpi-2").feePeriodId(p.getId())
                .name("Bảo hiểm y tế").amount(300000).build());
        generateInvoices(p.getId());
    }

    /** A8: tổng hợp doanh thu (Invoice là package-private nên gói gọn trong service). */
    public Map<String, Object> revenueReport() {
        List<Invoice> all = invoices.findAll();
        long total = all.stream().mapToLong(Invoice::getTotalAmount).sum();
        long paid = all.stream().mapToLong(Invoice::getPaidAmount).sum();
        long paidCount = all.stream().filter(i -> "PAID".equals(i.getStatus())).count();
        Map<String, Object> m = new HashMap<>();
        m.put("invoiceCount", all.size());
        m.put("paidCount", paidCount);
        m.put("totalAmount", total);
        m.put("paidAmount", paid);
        m.put("outstanding", total - paid);
        return m;
    }
}
