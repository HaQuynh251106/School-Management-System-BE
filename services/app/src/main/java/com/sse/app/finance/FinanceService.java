package com.sse.app.finance;

import com.sse.app.academic.structure.SchoolClass;
import com.sse.app.academic.structure.StructureService;
import com.sse.app.common.ApiException;
import com.sse.app.common.Ids;
import com.sse.app.common.PageResponse;
import com.sse.app.common.Paging;
import com.sse.app.finance.FinanceDtos.*;
import com.sse.app.identity.UserDto;
import com.sse.app.identity.UserService;
import com.sse.app.notification.NotificationService;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;

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
    private final MomoGatewayClient momoGateway;
    private final String paymentMode;
    private final String callbackSecret;

    public FinanceService(FeePeriodRepository periods, FeePeriodItemRepository periodItems,
                          InvoiceRepository invoices, InvoiceItemRepository invoiceItems,
                          PaymentRepository payments, PaymentGatewayTransactionRepository gatewayTransactions,
                          StructureService structure,
                          UserService users, NotificationService notifications, MomoGatewayClient momoGateway,
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
        this.momoGateway = momoGateway;
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

    public FeePeriod updatePeriod(String periodId, UpdateFeePeriodRequest r) {
        FeePeriod period = getPeriod(periodId);
        requireDraft(period);
        if (r.academicYearId() != null && !r.academicYearId().isBlank()) {
            structure.getYear(r.academicYearId());
        }
        period.setName(r.name().trim());
        period.setAcademicYearId(blankToNull(r.academicYearId()));
        period.setApplyToGrades(blankToNull(r.applyToGrades()));
        period.setDueDate(r.dueDate());
        return periods.save(period);
    }

    @Transactional
    public void deletePeriod(String periodId) {
        FeePeriod period = getPeriod(periodId);
        requireDraft(period);
        if (invoices.existsByFeePeriodId(periodId)) {
            throw ApiException.conflict("Không thể xóa đợt thu đã phát sinh hóa đơn");
        }
        periodItems.deleteByFeePeriodId(periodId);
        periods.delete(period);
    }

    public List<FeePeriodItem> itemsOf(String periodId) { return periodItems.findByFeePeriodId(periodId); }

    public FeePeriodItem addItem(String periodId, AddFeeItemRequest r) {
        FeePeriod period = getPeriod(periodId);
        requireDraft(period);
        return periodItems.save(FeePeriodItem.builder()
                .id(r.id() == null || r.id().isBlank() ? Ids.gen("fpi") : r.id())
                .feePeriodId(periodId).name(r.name()).amount(r.amount()).gradeLevel(r.gradeLevel()).build());
    }

    public void deleteItem(String periodId, String itemId) {
        FeePeriod period = getPeriod(periodId);
        requireDraft(period);
        FeePeriodItem item = periodItems.findById(itemId)
                .orElseThrow(() -> ApiException.notFound("Khoản thu"));
        if (!periodId.equals(item.getFeePeriodId())) {
            throw ApiException.badRequest("Khoản thu không thuộc đợt thu đã chọn");
        }
        periodItems.delete(item);
    }

    public FeePeriod open(String periodId) {
        FeePeriod p = getPeriod(periodId);
        if ("OPEN".equals(p.getStatus())) return p;
        if (!"DRAFT".equals(p.getStatus())) throw ApiException.conflict("Đợt thu không còn ở trạng thái nháp");
        if (periodItems.findByFeePeriodId(periodId).isEmpty()) {
            throw ApiException.badRequest(
                    "Đợt thu chưa có khoản thu. Hãy thêm ít nhất một khoản với số tiền hợp lệ trước khi mở đợt");
        }
        p.setStatus("OPEN");
        return periods.save(p);
    }

    public FeePeriod close(String periodId) {
        FeePeriod period = getPeriod(periodId);
        if ("CLOSED".equals(period.getStatus())) return period;
        if (!"OPEN".equals(period.getStatus())) {
            throw ApiException.conflict("Chỉ có thể đóng đợt thu đang mở");
        }
        period.setStatus("CLOSED");
        return periods.save(period);
    }

    private FeePeriod getPeriod(String id) {
        return periods.findById(id).orElseThrow(() -> ApiException.notFound("Đợt thu"));
    }

    private void requireDraft(FeePeriod period) {
        if (!"DRAFT".equals(period.getStatus())) {
            throw ApiException.conflict("Chỉ có thể thay đổi đợt thu khi còn ở trạng thái nháp");
        }
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
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
            List<String> parentIds = users.parentIdsOf(s.id()).stream().distinct().toList();
            String parentId = parentIds.stream().findFirst().orElse(null);
            SchoolClass schoolClass = s.classId() == null ? null : structure.getClass(s.classId());

            Invoice inv = invoices.save(Invoice.builder()
                    .id(Ids.gen("inv")).code("INV-" + p.getCode() + "-" + s.id())
                    .studentId(s.id()).studentName(s.fullName()).parentId(parentId)
                    .classId(s.classId())
                    .classCode(schoolClass == null ? s.className() : schoolClass.getCode())
                    .gradeLevel(schoolClass == null ? gl : schoolClass.getGradeLevel())
                    .feePeriodId(periodId).totalAmount(total).paidAmount(0).status("PENDING")
                    .issuedAt(Instant.now()).dueDate(p.getDueDate()).build());

            for (FeePeriodItem it : applicable) {
                invoiceItems.save(InvoiceItem.builder().id(Ids.gen("ii"))
                        .invoiceId(inv.getId()).name(it.getName()).amount(it.getAmount()).build());
            }
            created.add(inv);

            if (!parentIds.isEmpty()) {
                notifications.notifyUsers(parentIds, "FEE", "IMPORTANT", "Khoản thu mới",
                        String.format("%s — %s: %,d₫. Hạn thanh toán: %s", s.fullName(), inv.getCode(), total,
                                inv.getDueDate() == null ? "theo thông báo của nhà trường" : inv.getDueDate()),
                        "INVOICE", inv.getId());
            }
        }
        if (created.isEmpty()) {
            throw ApiException.badRequest(
                    "Không có học sinh phù hợp để phát hành. Hãy kiểm tra khối áp dụng, lớp học và dữ liệu học sinh");
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
    public List<Invoice> listInvoices(String studentId, String parentId, String status,
                                      String periodId, String query, String classId, String gradeLevel) {
        List<Invoice> base;
        if (studentId != null)     base = invoices.findByStudentId(studentId);
        else if (parentId != null) base = invoices.findByParentId(parentId);
        else base = invoices.findAll();
        String normalizedQuery = query == null ? null : query.trim().toLowerCase(Locale.ROOT);
        LocalDate today = LocalDate.now();
        return base.stream()
                .filter(i -> periodId == null || periodId.isBlank() || periodId.equals(i.getFeePeriodId()))
                .filter(i -> classId == null || classId.isBlank() || classId.equals(i.getClassId()))
                .filter(i -> gradeLevel == null || gradeLevel.isBlank() || gradeLevel.equals(i.getGradeLevel()))
                .filter(i -> status == null || status.isBlank() || invoiceMatchesStatus(i, status, today))
                .filter(i -> normalizedQuery == null || normalizedQuery.isBlank()
                        || containsIgnoreCase(i.getCode(), normalizedQuery)
                        || containsIgnoreCase(i.getStudentName(), normalizedQuery)
                        || containsIgnoreCase(i.getClassCode(), normalizedQuery)
                        || containsIgnoreCase(i.getGradeLevel(), normalizedQuery))
                .sorted(Comparator.comparing(Invoice::getIssuedAt,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
    }

    public PageResponse<Invoice> pageInvoices(String studentId, String parentId, String status,
                                              String periodId, String query, String classId,
                                              String gradeLevel, int page, int size) {
        Specification<Invoice> specification = Specification.where(null);
        if (studentId != null && !studentId.isBlank()) {
            specification = specification.and((root, ignored, builder) ->
                    builder.equal(root.get("studentId"), studentId));
        }
        if (parentId != null && !parentId.isBlank()) {
            specification = specification.and((root, ignored, builder) ->
                    builder.equal(root.get("parentId"), parentId));
        }
        if (periodId != null && !periodId.isBlank()) {
            specification = specification.and((root, ignored, builder) ->
                    builder.equal(root.get("feePeriodId"), periodId));
        }
        if (classId != null && !classId.isBlank()) {
            specification = specification.and((root, ignored, builder) ->
                    builder.equal(root.get("classId"), classId));
        }
        if (gradeLevel != null && !gradeLevel.isBlank()) {
            specification = specification.and((root, ignored, builder) ->
                    builder.equal(root.get("gradeLevel"), gradeLevel));
        }
        if (status != null && !status.isBlank()) {
            if ("OVERDUE".equalsIgnoreCase(status)) {
                specification = specification.and((root, ignored, builder) -> builder.and(
                        builder.lessThan(root.get("dueDate"), LocalDate.now()),
                        builder.lt(root.get("paidAmount"), root.get("totalAmount"))
                ));
            } else {
                specification = specification.and((root, ignored, builder) ->
                        builder.equal(builder.upper(root.get("status")), status.trim().toUpperCase(Locale.ROOT)));
            }
        }
        if (query != null && !query.isBlank()) {
            String pattern = "%" + query.trim().toLowerCase(Locale.ROOT) + "%";
            specification = specification.and((root, ignored, builder) -> builder.or(
                    builder.like(builder.lower(root.get("code")), pattern),
                    builder.like(builder.lower(root.get("studentName")), pattern),
                    builder.like(builder.lower(root.get("classCode")), pattern),
                    builder.like(builder.lower(root.get("gradeLevel")), pattern)
            ));
        }
        return PageResponse.from(invoices.findAll(specification,
                Paging.request(page, size, Sort.by(Sort.Direction.DESC, "issuedAt"))));
    }

    private boolean invoiceMatchesStatus(Invoice invoice, String status, LocalDate today) {
        if ("OVERDUE".equalsIgnoreCase(status)) {
            return invoice.getPaidAmount() < invoice.getTotalAmount()
                    && invoice.getDueDate() != null && invoice.getDueDate().isBefore(today);
        }
        return status.equalsIgnoreCase(invoice.getStatus());
    }

    private boolean containsIgnoreCase(String value, String normalizedQuery) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(normalizedQuery);
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
        return pay(r, "127.0.0.1");
    }

    @Transactional
    public Map<String, Object> pay(PayRequest r, String clientIp) {
        boolean simulatedSandbox = "sandbox".equalsIgnoreCase(paymentMode);
        boolean momoSandbox = "momo-sandbox".equalsIgnoreCase(paymentMode);
        if (!simulatedSandbox && !momoSandbox) {
            throw ApiException.serviceUnavailable(
                    "Cổng thanh toán chưa được cấu hình. Không có giao dịch nào được tạo.");
        }
        Invoice inv = getInvoice(r.invoiceId());
        long remaining = inv.getTotalAmount() - inv.getPaidAmount();
        if (remaining <= 0) throw ApiException.badRequest("Hóa đơn đã thanh toán đủ");

        String method = r.method() == null ? "MOMO" : r.method().toUpperCase(Locale.ROOT);
        if (!"MOMO".equals(method)) {
            throw ApiException.badRequest("Hệ thống chỉ hỗ trợ thanh toán trực tuyến qua MoMo");
        }
        if (simulatedSandbox) requireSandboxSecret();

        Optional<Payment> pending = payments.findFirstByInvoiceIdAndStatusOrderByCreatedAtDesc(inv.getId(), "PENDING");
        if (pending.isPresent()) return paymentResponse(inv, pending.get(),
                gatewayTransactions.findByPaymentId(pending.get().getId()).orElseThrow(), clientIp);

        String txnRef = simulatedSandbox
                ? "SANDBOX" + UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase(Locale.ROOT)
                : "MOMO" + System.currentTimeMillis() + UUID.randomUUID().toString().replace("-", "").substring(0, 6).toUpperCase(Locale.ROOT);
        Payment payment = payments.save(Payment.builder()
                .id(Ids.gen("pay")).invoiceId(inv.getId()).amount(remaining).method(method)
                .status("PENDING").txnRef(txnRef).createdAt(Instant.now()).build());
        PaymentGatewayTransaction transaction = gatewayTransactions.save(PaymentGatewayTransaction.builder()
                .id(Ids.gen("pgt")).paymentId(payment.getId()).txnRef(txnRef).gateway(method)
                .status("PENDING").requestPayload(simulatedSandbox ? canonical(txnRef, "SUCCESS", remaining) : "MOMO_SANDBOX")
                .signatureValid(false).createdAt(Instant.now()).updatedAt(Instant.now()).build());
        return paymentResponse(inv, payment, transaction, clientIp);
    }

    @Transactional
    public Map<String, Object> recordCashPayment(String invoiceId, Long requestedAmount) {
        Invoice invoice = getInvoice(invoiceId);
        long remaining = invoice.getTotalAmount() - invoice.getPaidAmount();
        if (remaining <= 0) throw ApiException.badRequest("Hóa đơn đã thanh toán đủ");
        long amount = requestedAmount == null ? remaining : requestedAmount;
        if (amount <= 0 || amount > remaining) {
            throw ApiException.badRequest("Số tiền thu phải lớn hơn 0 và không vượt quá công nợ còn lại");
        }
        Payment payment = payments.save(Payment.builder()
                .id(Ids.gen("pay")).invoiceId(invoice.getId()).amount(amount).method("CASH")
                .status("SUCCESS").txnRef("CASH-" + Ids.gen("tx"))
                .createdAt(Instant.now()).paidAt(Instant.now()).build());
        invoice.setPaidAmount(invoice.getPaidAmount() + amount);
        invoice.setStatus(invoice.getPaidAmount() >= invoice.getTotalAmount() ? "PAID" : "PARTIAL");
        invoices.save(invoice);
        if (invoice.getParentId() != null) {
            notifications.notifyUserWithTransactionalEmail(invoice.getParentId(), "INVOICE", "Biên nhận thanh toán học phí",
                    String.format("Biên nhận %s: %,d₫ (tiền mặt). Còn lại: %,d₫",
                            invoice.getCode(), amount, invoice.getTotalAmount() - invoice.getPaidAmount()),
                    "PAYMENT", payment.getId());
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("payment", payment);
        result.put("invoice", invoice);
        return result;
    }

    public void remindInvoice(String invoiceId) {
        Invoice invoice = getInvoice(invoiceId);
        long outstanding = invoice.getTotalAmount() - invoice.getPaidAmount();
        if (outstanding <= 0) throw ApiException.badRequest("Hóa đơn đã thanh toán đủ");
        List<String> parentIds = invoice.getParentId() == null
                ? users.parentIdsOf(invoice.getStudentId()).stream().distinct().toList()
                : List.of(invoice.getParentId());
        if (parentIds.isEmpty()) {
            throw ApiException.badRequest("Học sinh chưa được liên kết với phụ huynh để gửi nhắc thanh toán");
        }
        notifications.notifyUsers(parentIds, "FINANCE_REMINDER", "IMPORTANT", "Nhắc thanh toán khoản thu",
                String.format("%s còn %,d₫ cần thanh toán cho hóa đơn %s. Hạn thanh toán: %s",
                        invoice.getStudentName(), outstanding, invoice.getCode(),
                        invoice.getDueDate() == null ? "theo thông báo của nhà trường" : invoice.getDueDate()),
                "INVOICE_REMINDER", invoice.getId() + ":" + LocalDate.now());
    }

    /** Tổng hợp theo lớp; allowedClassIds = null nghĩa là Admin được xem toàn trường. */
    public List<FinanceClassSummary> classSummaries(String periodId, Set<String> allowedClassIds) {
        return classSummaries(periodId, allowedClassIds, null, null, null);
    }

    public List<FinanceClassSummary> classSummaries(String periodId, Set<String> allowedClassIds,
                                                     String gradeLevel, String filterClassId, String status) {
        Map<String, SchoolClass> classMap = structure.listClasses(null, null).stream()
                .collect(java.util.stream.Collectors.toMap(SchoolClass::getId, item -> item));
        LocalDate today = LocalDate.now();
        return invoices.findAll().stream()
                .filter(invoice -> periodId == null || periodId.isBlank() || periodId.equals(invoice.getFeePeriodId()))
                .filter(invoice -> invoice.getClassId() != null && !invoice.getClassId().isBlank())
                .filter(invoice -> allowedClassIds == null || allowedClassIds.contains(invoice.getClassId()))
                .collect(java.util.stream.Collectors.groupingBy(Invoice::getClassId))
                .entrySet().stream()
                .map(entry -> {
                    String classId = entry.getKey();
                    List<Invoice> rows = entry.getValue();
                    SchoolClass schoolClass = classMap.get(classId);
                    long total = rows.stream().mapToLong(Invoice::getTotalAmount).sum();
                    long paid = rows.stream().mapToLong(Invoice::getPaidAmount).sum();
                    int paidCount = (int) rows.stream().filter(item -> item.getPaidAmount() >= item.getTotalAmount()).count();
                    int partialCount = (int) rows.stream().filter(item -> item.getPaidAmount() > 0
                            && item.getPaidAmount() < item.getTotalAmount()).count();
                    int overdueCount = (int) rows.stream().filter(item -> item.getPaidAmount() < item.getTotalAmount()
                            && item.getDueDate() != null && item.getDueDate().isBefore(today)).count();
                    String teacherId = schoolClass == null ? null : schoolClass.getHomeroomTeacherId();
                    String completionRef = completionRef(periodId, classId);
                    boolean completed = !rows.isEmpty() && paid >= total;
                    return new FinanceClassSummary(classId,
                            schoolClass == null ? rows.get(0).getClassCode() : schoolClass.getCode(),
                            schoolClass == null ? rows.get(0).getGradeLevel() : schoolClass.getGradeLevel(),
                            teacherId, schoolClass == null ? null : schoolClass.getHomeroomTeacherName(),
                            rows.size(), paidCount, partialCount, overdueCount, total, paid, total - paid,
                            total == 0 ? 0d : paid * 100d / total, completed,
                            completed && notifications.hasNotification(teacherId, "FINANCE_CLASS", completionRef),
                            notifications.hasNotification(teacherId, "FINANCE_CLASS_DEBT",
                                    debtReminderRef(periodId, classId)));
                })
                .filter(summary -> gradeLevel == null || gradeLevel.isBlank()
                        || gradeLevel.equalsIgnoreCase(summary.gradeLevel()))
                .filter(summary -> filterClassId == null || filterClassId.isBlank()
                        || filterClassId.equals(summary.classId()))
                .filter(summary -> classSummaryMatchesStatus(summary, status))
                .sorted(Comparator.comparing(FinanceClassSummary::gradeLevel,
                                Comparator.nullsLast(String::compareTo))
                        .thenComparing(FinanceClassSummary::classCode, Comparator.nullsLast(String::compareTo)))
                .toList();
    }

    private boolean classSummaryMatchesStatus(FinanceClassSummary summary, String status) {
        if (status == null || status.isBlank() || "ALL".equalsIgnoreCase(status)) return true;
        return switch (status.toUpperCase(Locale.ROOT)) {
            case "COMPLETED" -> summary.completed();
            case "INCOMPLETE" -> !summary.completed();
            case "OVERDUE" -> !summary.completed() && summary.overdueCount() > 0;
            case "IN_PROGRESS" -> !summary.completed() && summary.overdueCount() == 0;
            case "NO_HOMEROOM" -> summary.homeroomTeacherId() == null
                    || summary.homeroomTeacherId().isBlank();
            default -> throw ApiException.badRequest("Trạng thái lọc công nợ lớp không hợp lệ");
        };
    }

    public HomeroomDebtReminderResult remindHomeroomTeachers(String periodId, List<String> requestedClassIds) {
        Set<String> classIds = requestedClassIds == null ? Set.of() : requestedClassIds.stream()
                .filter(Objects::nonNull).map(String::trim).filter(value -> !value.isBlank())
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        if (classIds.isEmpty()) {
            throw ApiException.badRequest("Vui lòng chọn ít nhất một lớp cần nhắc giáo viên chủ nhiệm");
        }
        Map<String, FinanceClassSummary> summaries = classSummaries(periodId, classIds).stream()
                .collect(java.util.stream.Collectors.toMap(FinanceClassSummary::classId, item -> item));
        FeePeriod period = periodId == null || periodId.isBlank() ? null : getPeriod(periodId);
        String periodName = period == null ? "các khoản thu hiện tại"
                : period.getName() == null || period.getName().isBlank() ? period.getCode() : period.getName();
        Set<String> recipients = new HashSet<>();
        int classCount = 0;
        int skippedCount = 0;
        for (String requestedClassId : classIds) {
            FinanceClassSummary summary = summaries.get(requestedClassId);
            if (summary == null || summary.completed() || summary.outstanding() <= 0
                    || summary.homeroomTeacherId() == null || summary.homeroomTeacherId().isBlank()) {
                skippedCount++;
                continue;
            }
            String refId = debtReminderRef(periodId, requestedClassId);
            if (notifications.hasNotification(summary.homeroomTeacherId(), "FINANCE_CLASS_DEBT", refId)) {
                skippedCount++;
                continue;
            }
            int unfinishedInvoices = Math.max(0, summary.invoiceCount() - summary.paidCount());
            notifications.notifyUser(summary.homeroomTeacherId(), "FINANCE_TASK_REMINDER", "IMPORTANT",
                    "Lớp " + summary.classCode() + " còn nhiệm vụ tài chính",
                    String.format("Lớp %s còn %d hóa đơn chưa hoàn thành %s, tổng công nợ %,d₫ (đã thu %.1f%%). Vui lòng kiểm tra và nhắc phụ huynh trong lớp.",
                            summary.classCode(), unfinishedInvoices, periodName,
                            summary.outstanding(), summary.collectionRate()),
                    "FINANCE_CLASS_DEBT", refId);
            recipients.add(summary.homeroomTeacherId());
            classCount++;
        }
        if (classCount == 0) {
            throw ApiException.conflict("Các lớp đã hoàn thành, chưa có GVCN hoặc GVCN đã nhận nhắc trong hôm nay");
        }
        return new HomeroomDebtReminderResult(classCount, recipients.size(), skippedCount, Instant.now());
    }

    public void notifyHomeroomCompletion(String classId, String periodId) {
        SchoolClass schoolClass = structure.getClass(classId);
        if (schoolClass.getHomeroomTeacherId() == null || schoolClass.getHomeroomTeacherId().isBlank()) {
            throw ApiException.badRequest("Lớp chưa được phân công giáo viên chủ nhiệm");
        }
        FinanceClassSummary summary = classSummaries(periodId, Set.of(classId)).stream().findFirst()
                .orElseThrow(() -> ApiException.badRequest("Lớp chưa có hóa đơn trong đợt thu đã chọn"));
        if (!summary.completed()) {
            throw ApiException.badRequest("Lớp vẫn còn công nợ, chưa thể xác nhận hoàn thành");
        }
        FeePeriod period = periodId == null || periodId.isBlank() ? null : getPeriod(periodId);
        String periodName = period == null ? "các khoản thu hiện tại" :
                (period.getName() == null || period.getName().isBlank() ? period.getCode() : period.getName());
        String refId = completionRef(periodId, classId);
        if (notifications.hasNotification(schoolClass.getHomeroomTeacherId(), "FINANCE_CLASS", refId)) {
            throw ApiException.conflict("Giáo viên chủ nhiệm đã nhận thông báo hoàn thành của lớp");
        }
        notifications.notifyUser(schoolClass.getHomeroomTeacherId(), "FINANCE_CLASS_COMPLETE", "IMPORTANT",
                "Lớp " + schoolClass.getCode() + " đã hoàn thành khoản thu",
                String.format("Lớp %s đã hoàn thành 100%% yêu cầu tài chính của %s. Tổng đã thu: %,d₫.",
                        schoolClass.getCode(), periodName, summary.paidAmount()),
                "FINANCE_CLASS", refId);
    }

    public ClassReminderResult remindHomeroomClass(String teacherId, String classId, String periodId) {
        SchoolClass schoolClass = structure.getClass(classId);
        if (!teacherId.equals(schoolClass.getHomeroomTeacherId())) {
            throw ApiException.forbidden("Bạn chỉ được quản lý công nợ lớp mình chủ nhiệm");
        }
        List<Invoice> pending = listInvoices(null, null, null, periodId, null, classId, null).stream()
                .filter(invoice -> invoice.getPaidAmount() < invoice.getTotalAmount()).toList();
        if (pending.isEmpty()) throw ApiException.badRequest("Lớp không còn hóa đơn cần nhắc thanh toán");
        Set<String> recipients = new HashSet<>();
        int sentInvoices = 0;
        for (Invoice invoice : pending) {
            List<String> parentIds = invoice.getParentId() == null
                    ? users.parentIdsOf(invoice.getStudentId()).stream().distinct().toList()
                    : List.of(invoice.getParentId());
            if (parentIds.isEmpty()) continue;
            long outstanding = invoice.getTotalAmount() - invoice.getPaidAmount();
            String refId = invoice.getId() + ":" + LocalDate.now();
            List<String> newRecipients = parentIds.stream()
                    .filter(parentId -> !notifications.hasNotification(parentId, "INVOICE_REMINDER", refId)).toList();
            if (!newRecipients.isEmpty()) {
                notifications.notifyUsers(newRecipients, "FINANCE_REMINDER", "IMPORTANT",
                        "GVCN lớp " + schoolClass.getCode() + " nhắc hạn khoản thu",
                        String.format("Kính gửi phụ huynh %s, học sinh còn %,d₫ cần thanh toán cho hóa đơn %s. Hạn: %s.",
                                invoice.getStudentName(), outstanding, invoice.getCode(),
                                invoice.getDueDate() == null ? "theo thông báo nhà trường" : invoice.getDueDate()),
                        "INVOICE_REMINDER", refId);
                recipients.addAll(newRecipients);
                sentInvoices++;
            }
        }
        if (sentInvoices == 0) {
            throw ApiException.conflict("Các phụ huynh còn nợ đã nhận nhắc hạn trong hôm nay");
        }
        return new ClassReminderResult(sentInvoices, recipients.size(), Instant.now());
    }

    public ClassReminderResult remindHomeroomInvoice(String teacherId, String invoiceId) {
        Invoice invoice = getInvoice(invoiceId);
        if (invoice.getClassId() == null || invoice.getClassId().isBlank()) {
            throw ApiException.badRequest("Hóa đơn chưa có thông tin lớp học");
        }
        SchoolClass schoolClass = structure.getClass(invoice.getClassId());
        if (!teacherId.equals(schoolClass.getHomeroomTeacherId())) {
            throw ApiException.forbidden("Bạn chỉ được nhắc phụ huynh của học sinh trong lớp mình chủ nhiệm");
        }
        long outstanding = invoice.getTotalAmount() - invoice.getPaidAmount();
        if (outstanding <= 0) throw ApiException.badRequest("Hóa đơn đã hoàn thành thanh toán");
        List<String> parentIds = invoice.getParentId() == null
                ? users.parentIdsOf(invoice.getStudentId()).stream().distinct().toList()
                : List.of(invoice.getParentId());
        if (parentIds.isEmpty()) {
            throw ApiException.badRequest("Học sinh chưa được liên kết với phụ huynh để gửi nhắc thanh toán");
        }
        String refId = invoice.getId() + ":" + LocalDate.now();
        List<String> newRecipients = parentIds.stream()
                .filter(parentId -> !notifications.hasNotification(parentId, "INVOICE_REMINDER", refId)).toList();
        if (newRecipients.isEmpty()) {
            throw ApiException.conflict("Phụ huynh đã nhận nhắc hạn cho hóa đơn này trong hôm nay");
        }
        notifications.notifyUsers(newRecipients, "FINANCE_REMINDER", "IMPORTANT",
                "GVCN lớp " + schoolClass.getCode() + " nhắc hạn khoản thu",
                String.format("Kính gửi phụ huynh %s, học sinh còn %,d₫ cần thanh toán cho hóa đơn %s. Hạn: %s.",
                        invoice.getStudentName(), outstanding, invoice.getCode(),
                        invoice.getDueDate() == null ? "theo thông báo nhà trường" : invoice.getDueDate()),
                "INVOICE_REMINDER", refId);
        return new ClassReminderResult(1, newRecipients.size(), Instant.now());
    }

    private String completionRef(String periodId, String classId) {
        return (periodId == null || periodId.isBlank() ? "ALL" : periodId) + ":" + classId;
    }

    private String debtReminderRef(String periodId, String classId) {
        return completionRef(periodId, classId) + ":" + LocalDate.now();
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
                notifications.notifyUserWithTransactionalEmail(invoice.getParentId(), "INVOICE", "Thanh toán thành công",
                        String.format("Biên nhận %s%nHọc sinh: %s%nSố tiền: %,d₫%nPhương thức: Mô phỏng MoMo%nMã giao dịch: %s%nTrạng thái: Thành công",
                                invoice.getCode(), invoice.getStudentName(), payment.getAmount(), payment.getTxnRef()),
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
                                                PaymentGatewayTransaction transaction, String clientIp) {
        if ("momo-sandbox".equalsIgnoreCase(paymentMode)) {
            Map<String, Object> result = callbackResult(invoice, payment, transaction);
            MomoGatewayClient.MomoCreateResult created =
                    momoGateway.createPayment(payment.getTxnRef(), payment.getAmount(), invoice.getCode());
            transaction.setRequestPayload("MOMO_SANDBOX_CREATE:" + created.resultCode());
            transaction.setUpdatedAt(Instant.now());
            gatewayTransactions.save(transaction);
            result.put("paymentUrl", created.payUrl());
            result.put("gateway", "MOMO");
            return result;
        }
        PaymentCallbackRequest callback = new PaymentCallbackRequest(transaction.getTxnRef(), "SUCCESS",
                payment.getAmount(), sign(canonical(transaction.getTxnRef(), "SUCCESS", payment.getAmount())));
        Map<String, Object> result = callbackResult(invoice, payment, transaction);
        result.put("callbackUrl", "/payments/callback/" + transaction.getGateway().toLowerCase(Locale.ROOT));
        result.put("sandboxCallback", callback);
        return result;
    }

    @Transactional
    public Map<String, Object> completeMomo(Map<String, Object> payload) {
        String txnRef = Objects.toString(payload.get("orderId"), "");
        if (txnRef.isBlank()) return momoIpnResponse(payload, 1001, "Thiếu mã giao dịch");

        Optional<PaymentGatewayTransaction> found = gatewayTransactions.findByTxnRef(txnRef);
        if (found.isEmpty()) return momoIpnResponse(payload, 1002, "Không tìm thấy giao dịch");
        PaymentGatewayTransaction transaction = found.get();
        Payment payment = payments.findById(transaction.getPaymentId()).orElse(null);
        if (payment == null) return momoIpnResponse(payload, 1002, "Không tìm thấy thanh toán");
        Invoice invoice = getInvoice(payment.getInvoiceId());

        transaction.setCallbackPayload(momoGateway.safeCallbackPayload(payload));
        transaction.setUpdatedAt(Instant.now());
        boolean signatureValid = momoGateway.verifyIpn(payload);
        transaction.setSignatureValid(signatureValid);
        if (!signatureValid) {
            transaction.setStatus("REJECTED");
            gatewayTransactions.save(transaction);
            return momoIpnResponse(payload, 1003, "Chữ ký không hợp lệ");
        }

        long amount;
        int resultCode;
        try {
            amount = Long.parseLong(Objects.toString(payload.get("amount"), "0"));
            resultCode = Integer.parseInt(Objects.toString(payload.get("resultCode"), "-1"));
        } catch (NumberFormatException ignored) {
            transaction.setStatus("REJECTED");
            gatewayTransactions.save(transaction);
            return momoIpnResponse(payload, 1004, "Dữ liệu giao dịch không hợp lệ");
        }
        if (amount != payment.getAmount()) {
            transaction.setStatus("REJECTED");
            gatewayTransactions.save(transaction);
            return momoIpnResponse(payload, 1004, "Số tiền không khớp");
        }
        if ("SUCCESS".equals(payment.getStatus())) {
            return momoIpnResponse(payload, 0, "Giao dịch đã được xác nhận");
        }

        if (resultCode == 0) {
            payment.setStatus("SUCCESS");
            payment.setPaidAt(Instant.now());
            transaction.setStatus("SUCCESS");
            invoice.setPaidAmount(Math.min(invoice.getTotalAmount(), invoice.getPaidAmount() + payment.getAmount()));
            invoice.setStatus(invoice.getPaidAmount() >= invoice.getTotalAmount() ? "PAID" : "PARTIAL");
            invoices.save(invoice);
            if (invoice.getParentId() != null) {
                notifications.notifyUserWithTransactionalEmail(invoice.getParentId(), "INVOICE", "Thanh toán thành công",
                        String.format("Biên nhận %s%nHọc sinh: %s%nSố tiền: %,d₫%nPhương thức: MoMo%nMã giao dịch: %s%nTrạng thái: Thành công",
                                invoice.getCode(), invoice.getStudentName(), payment.getAmount(), payment.getTxnRef()),
                        "PAYMENT", payment.getId());
            }
        } else {
            payment.setStatus("FAILED");
            transaction.setStatus("FAILED");
        }
        payments.save(payment);
        gatewayTransactions.save(transaction);
        return momoIpnResponse(payload, 0, "Đã tiếp nhận kết quả");
    }

    private Map<String, Object> momoIpnResponse(Map<String, Object> payload, int resultCode, String message) {
        return momoGateway.ipnResponse(
                Objects.toString(payload.get("orderId"), ""),
                Objects.toString(payload.get("requestId"), ""),
                resultCode,
                message);
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
    public Map<String, Object> financeOverview() {
        List<Invoice> all = invoices.findAll();
        LocalDate today = LocalDate.now();
        LocalDate dueSoonLimit = today.plusDays(7);
        Instant monthStart = today.withDayOfMonth(1)
                .atStartOfDay(java.time.ZoneId.of("Asia/Ho_Chi_Minh")).toInstant();

        long total = all.stream().mapToLong(Invoice::getTotalAmount).sum();
        long paid = all.stream().mapToLong(Invoice::getPaidAmount).sum();
        long overdueCount = all.stream().filter(i -> i.getPaidAmount() < i.getTotalAmount()
                && i.getDueDate() != null && i.getDueDate().isBefore(today)).count();
        long dueSoonCount = all.stream().filter(i -> i.getPaidAmount() < i.getTotalAmount()
                && i.getDueDate() != null && !i.getDueDate().isBefore(today)
                && !i.getDueDate().isAfter(dueSoonLimit)).count();
        long collectedThisMonth = payments.findAll().stream()
                .filter(p -> "SUCCESS".equals(p.getStatus()) && p.getPaidAt() != null
                        && !p.getPaidAt().isBefore(monthStart))
                .mapToLong(Payment::getAmount).sum();

        List<Map<String, Object>> periodSummaries = periods.findAll().stream()
                .map(period -> {
                    List<Invoice> periodInvoices = invoices.findByFeePeriodId(period.getId());
                    long periodTotal = periodInvoices.stream().mapToLong(Invoice::getTotalAmount).sum();
                    long periodPaid = periodInvoices.stream().mapToLong(Invoice::getPaidAmount).sum();
                    Map<String, Object> summary = new LinkedHashMap<>();
                    summary.put("periodId", period.getId());
                    summary.put("code", period.getCode());
                    summary.put("name", period.getName());
                    summary.put("status", period.getStatus());
                    summary.put("invoiceCount", periodInvoices.size());
                    summary.put("totalAmount", periodTotal);
                    summary.put("paidAmount", periodPaid);
                    summary.put("outstanding", periodTotal - periodPaid);
                    summary.put("collectionRate", periodTotal == 0 ? 0d : periodPaid * 100d / periodTotal);
                    return summary;
                })
                .sorted(Comparator.comparing(summary -> String.valueOf(summary.get("code")), Comparator.reverseOrder()))
                .toList();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("invoiceCount", all.size());
        result.put("paidInvoiceCount", all.stream().filter(i -> "PAID".equals(i.getStatus())).count());
        result.put("partialInvoiceCount", all.stream().filter(i -> "PARTIAL".equals(i.getStatus())).count());
        result.put("overdueInvoiceCount", overdueCount);
        result.put("dueSoonInvoiceCount", dueSoonCount);
        result.put("totalAmount", total);
        result.put("paidAmount", paid);
        result.put("outstanding", total - paid);
        result.put("collectedThisMonth", collectedThisMonth);
        result.put("collectionRate", total == 0 ? 0d : paid * 100d / total);
        result.put("periods", periodSummaries);
        return result;
    }

    public Map<String, Object> revenueReport() {
        return revenueReport(null, null);
    }

    public Map<String, Object> revenueReport(String periodId, String classId) {
        List<Invoice> all = listInvoices(null, null, null, periodId, null, classId, null);
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

    public Map<String, Object> parentFinanceSummary(String parentId) {
        List<Invoice> rows = listInvoices(null, parentId, null, null, null, null, null);
        long total = rows.stream().mapToLong(Invoice::getTotalAmount).sum();
        long paid = rows.stream().mapToLong(Invoice::getPaidAmount).sum();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("invoiceCount", rows.size());
        result.put("paidInvoiceCount", rows.stream().filter(item -> "PAID".equals(item.getStatus())).count());
        result.put("totalAmount", total);
        result.put("paidAmount", paid);
        result.put("outstanding", Math.max(0, total - paid));
        return result;
    }
}
