package com.sse.app.finance;

import com.sse.app.academic.structure.AcademicYear;
import com.sse.app.academic.structure.ClassEnrollment;
import com.sse.app.academic.structure.Cohort;
import com.sse.app.academic.structure.SchoolClass;
import com.sse.app.academic.structure.StructureService;
import com.sse.app.common.ApiException;
import com.sse.app.common.Ids;
import com.sse.app.common.PageResponse;
import com.sse.app.common.Paging;
import com.sse.app.common.SchedulerExecutionRegistry;
import com.sse.app.finance.FinanceDtos.*;
import com.sse.app.identity.UserDto;
import com.sse.app.identity.UserService;
import com.sse.app.notification.NotificationService;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.data.domain.Sort;
import org.springframework.data.domain.Page;
import org.springframework.data.jpa.domain.Specification;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;

/** A7: Tài chính nội bộ — đợt thu, sinh hóa đơn, VietQR và đối soát. */
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
    private final VietQrGateway vietQrGateway;
    private final String paymentMode;
    private final SchedulerExecutionRegistry executions;

    public FinanceService(FeePeriodRepository periods, FeePeriodItemRepository periodItems,
                          InvoiceRepository invoices, InvoiceItemRepository invoiceItems,
                          PaymentRepository payments, PaymentGatewayTransactionRepository gatewayTransactions,
                          StructureService structure,
                          UserService users, NotificationService notifications, VietQrGateway vietQrGateway,
                          @Value("${sse.payments.mode:disabled}") String paymentMode,
                          SchedulerExecutionRegistry executions) {
        this.periods = periods;
        this.periodItems = periodItems;
        this.invoices = invoices;
        this.invoiceItems = invoiceItems;
        this.payments = payments;
        this.gatewayTransactions = gatewayTransactions;
        this.structure = structure;
        this.users = users;
        this.notifications = notifications;
        this.vietQrGateway = vietQrGateway;
        this.paymentMode = paymentMode;
        this.executions = executions;
    }

    // ---------- Đợt thu ----------
    public Map<String, Object> integrationStatus() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("paymentMode", paymentMode);
        result.put("vietQr", vietQrGateway.configurationStatus());
        result.put("notifications", notifications.channelCapabilities());
        result.put("automaticBankConfirmation", false);
        result.put("reconciliationMode", "ACCOUNTANT_MANUAL");
        return result;
    }

    public List<FeePeriod> listPeriods(String academicYearId) {
        String effectiveYearId = academicYearId == null || academicYearId.isBlank()
                ? defaultAcademicYearId() : academicYearId;
        structure.getYear(effectiveYearId);
        return periods.findByAcademicYearIdOrderByCreatedAtDesc(effectiveYearId);
    }

    public FeePeriod createPeriod(CreateFeePeriodRequest r) {
        String code = r.code().trim();
        if (periods.findByCode(code).isPresent()) throw ApiException.conflict("Mã đợt thu đã tồn tại");
        AcademicYear year = requireWritableYear(r.academicYearId());
        return periods.save(FeePeriod.builder()
                .id(r.id() == null || r.id().isBlank() ? Ids.gen("fp") : r.id())
                .code(code).name(r.name()).status("DRAFT")
                .academicYearId(year.getId()).applyToGrades(normalizeGrades(r.applyToGrades()))
                .dueDate(r.dueDate()).createdAt(Instant.now()).build());
    }

    public FeePeriod updatePeriod(String periodId, UpdateFeePeriodRequest r) {
        FeePeriod period = getPeriod(periodId);
        requireDraft(period);
        AcademicYear year = requireWritableYear(r.academicYearId());
        period.setName(r.name().trim());
        period.setAcademicYearId(year.getId());
        period.setApplyToGrades(normalizeGrades(r.applyToGrades()));
        period.setDueDate(r.dueDate());
        return periods.save(period);
    }

    @Transactional
    public void deletePeriod(String periodId) {
        FeePeriod period = getPeriod(periodId);
        requireWritableYear(period.getAcademicYearId());
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
        requireWritableYear(period.getAcademicYearId());
        requireDraft(period);
        return periodItems.save(FeePeriodItem.builder()
                .id(r.id() == null || r.id().isBlank() ? Ids.gen("fpi") : r.id())
                .feePeriodId(periodId).name(r.name()).amount(r.amount()).gradeLevel(r.gradeLevel()).build());
    }

    public void deleteItem(String periodId, String itemId) {
        FeePeriod period = getPeriod(periodId);
        requireWritableYear(period.getAcademicYearId());
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
        requireWritableYear(p.getAcademicYearId());
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

    private AcademicYear requireWritableYear(String academicYearId) {
        if (academicYearId == null || academicYearId.isBlank()) {
            throw ApiException.badRequest("Đợt thu bắt buộc phải thuộc một năm học");
        }
        AcademicYear year = structure.getYear(academicYearId.trim());
        if ("CLOSED".equalsIgnoreCase(year.getStatus())) {
            throw ApiException.conflict("Năm học " + year.getCode() + " đã đóng và chỉ được phép xem lịch sử");
        }
        return year;
    }

    private String normalizeGrades(String value) {
        Set<String> grades = parseGrades(value);
        return grades == null ? null : String.join(",", grades.stream().sorted().toList());
    }

    // ---------- Sinh hóa đơn (flowchart 2.8) ----------
    public InvoiceGenerationPreview previewInvoiceGeneration(String periodId) {
        FeePeriod period = getPeriod(periodId);
        AcademicYear year = structure.getYear(period.getAcademicYearId());
        Set<String> gradeFilter = parseGrades(period.getApplyToGrades());
        List<FeePeriodItem> items = itemsOf(periodId);
        if (items.isEmpty()) throw ApiException.badRequest("Đợt thu chưa có khoản thu");

        List<EligibleStudent> eligible = eligibleStudents(period, items);
        Map<String, List<EligibleStudent>> byClass = eligible.stream()
                .collect(java.util.stream.Collectors.groupingBy(row -> row.schoolClass().getId(),
                        LinkedHashMap::new, java.util.stream.Collectors.toList()));
        List<InvoiceScopeClass> scopes = byClass.values().stream().map(rows -> {
            EligibleStudent first = rows.get(0);
            return new InvoiceScopeClass(first.schoolClass().getId(), first.schoolClass().getCode(),
                    first.schoolClass().getGradeLevel(), rows.size(),
                    (int) rows.stream().filter(EligibleStudent::invoiceExists).count(),
                    (int) rows.stream().filter(row -> row.parentIds().isEmpty()).count(),
                    first.totalAmount());
        }).toList();
        int existing = (int) eligible.stream().filter(EligibleStudent::invoiceExists).count();
        int missingParents = (int) eligible.stream().filter(row -> row.parentIds().isEmpty()).count();
        long expected = eligible.stream().filter(row -> !row.invoiceExists())
                .mapToLong(EligibleStudent::totalAmount).sum();
        List<String> warnings = new ArrayList<>();
        if (missingParents > 0) warnings.add(missingParents
                + " học sinh chưa liên kết phụ huynh; hóa đơn vẫn được tạo nhưng chưa thể gửi thông báo");
        if (existing > 0) warnings.add(existing + " học sinh đã có hóa đơn và sẽ được giữ nguyên");
        if (eligible.isEmpty()) warnings.add("Không có học sinh đang theo học phù hợp với phạm vi đợt thu");
        return new InvoiceGenerationPreview(period.getId(), period.getCode(), period.getName(),
                year.getId(), year.getCode(), gradeFilter == null ? List.of() : gradeFilter.stream().sorted().toList(),
                scopes.size(), eligible.size(), existing, eligible.size() - existing, missingParents,
                expected, scopes, warnings);
    }

    @Transactional
    public List<Invoice> generateInvoices(String periodId) {
        FeePeriod period = getPeriod(periodId);
        requireWritableYear(period.getAcademicYearId());
        if (!"OPEN".equals(period.getStatus())) throw ApiException.badRequest("Đợt thu phải ở trạng thái OPEN");
        List<FeePeriodItem> items = itemsOf(periodId);
        if (items.isEmpty()) throw ApiException.badRequest("Đợt thu chưa có khoản thu");
        List<Invoice> synchronizedInvoices = new ArrayList<>();

        for (EligibleStudent candidate : eligibleStudents(period, items)) {
            UserDto student = candidate.student();
            Optional<Invoice> existing = invoices.findByFeePeriodIdAndStudentId(periodId, student.id());
            if (existing.isPresent()) {
                synchronizedInvoices.add(existing.get());
                continue;
            }
            SchoolClass schoolClass = candidate.schoolClass();
            String parentId = candidate.parentIds().stream().findFirst().orElse(null);
            Invoice invoice = invoices.save(Invoice.builder()
                    .id(Ids.gen("inv")).code("INV-" + period.getCode() + "-" + student.id())
                    .studentId(student.id()).studentName(student.fullName()).parentId(parentId)
                    .classId(schoolClass.getId()).classCode(schoolClass.getCode())
                    .gradeLevel(schoolClass.getGradeLevel()).feePeriodId(periodId)
                    .totalAmount(candidate.totalAmount()).paidAmount(0).status("PENDING")
                    .issuedAt(Instant.now()).dueDate(period.getDueDate()).build());
            for (FeePeriodItem item : candidate.items()) {
                invoiceItems.save(InvoiceItem.builder().id(Ids.gen("ii"))
                        .invoiceId(invoice.getId()).name(item.getName()).amount(item.getAmount()).build());
            }
            synchronizedInvoices.add(invoice);
            if (!candidate.parentIds().isEmpty()) {
                notifications.notifyUsers(candidate.parentIds(), "FEE", "IMPORTANT", "Khoản thu mới",
                        String.format("%s — %s: %,d₫. Hạn thanh toán: %s", student.fullName(),
                                invoice.getCode(), candidate.totalAmount(),
                                invoice.getDueDate() == null ? "theo thông báo của nhà trường" : invoice.getDueDate()),
                        "INVOICE", invoice.getId());
            }
        }
        if (synchronizedInvoices.isEmpty()) {
            throw ApiException.badRequest(
                    "Không có học sinh đang theo học phù hợp trong đúng năm học và khối áp dụng");
        }
        return synchronizedInvoices;
    }

    private List<EligibleStudent> eligibleStudents(FeePeriod period, List<FeePeriodItem> items) {
        String academicYearId = period.getAcademicYearId();
        if (academicYearId == null || academicYearId.isBlank()) {
            throw ApiException.badRequest("Đợt thu chưa có năm học; vui lòng cập nhật trước khi phát hành");
        }
        structure.getYear(academicYearId);
        Set<String> gradeFilter = parseGrades(period.getApplyToGrades());
        List<EligibleStudent> result = new ArrayList<>();
        for (ClassEnrollment enrollment : structure.activeEnrollments(academicYearId)) {
            UserDto student = users.dtoById(enrollment.getStudentId());
            if (!"STUDENT".equals(student.role()) || !"ACTIVE".equals(student.status())
                    || "GRADUATED".equals(student.studentStatus())) continue;
            SchoolClass schoolClass = structure.getClass(enrollment.getClassId());
            if (!academicYearId.equals(schoolClass.getAcademicYearId())) continue;
            if (enrollment.getCohortId() != null && !enrollment.getCohortId().isBlank()) {
                Cohort cohort = structure.getCohort(enrollment.getCohortId());
                if ("COMPLETED".equalsIgnoreCase(cohort.getStatus())) continue;
            }
            String gradeLevel = schoolClass.getGradeLevel();
            if (gradeFilter != null && (gradeLevel == null || !gradeFilter.contains(gradeLevel))) continue;
            List<FeePeriodItem> applicable = items.stream()
                    .filter(item -> item.getGradeLevel() == null || item.getGradeLevel().isBlank()
                            || item.getGradeLevel().equalsIgnoreCase(gradeLevel))
                    .toList();
            if (applicable.isEmpty()) continue;
            result.add(new EligibleStudent(student, schoolClass, applicable,
                    users.parentIdsOf(student.id()).stream().distinct().toList(),
                    invoices.findByFeePeriodIdAndStudentId(period.getId(), student.id()).isPresent(),
                    applicable.stream().mapToLong(FeePeriodItem::getAmount).sum()));
        }
        return result;
    }

    private record EligibleStudent(UserDto student, SchoolClass schoolClass,
                                   List<FeePeriodItem> items, List<String> parentIds,
                                   boolean invoiceExists, long totalAmount) {}

    private Set<String> parseGrades(String csv) {
        if (csv == null || csv.isBlank()) return null;
        Set<String> set = new HashSet<>();
        for (String grade : csv.split(",")) {
            String normalized = grade.trim().toUpperCase(Locale.ROOT);
            if (!Set.of("K10", "K11", "K12").contains(normalized)) {
                throw ApiException.badRequest("Khối áp dụng không hợp lệ: " + grade.trim());
            }
            set.add(normalized);
        }
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
        List<Invoice> result = base.stream()
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
        enrichParentNames(result);
        return result;
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
        PageResponse<Invoice> result = PageResponse.from(invoices.findAll(specification,
                Paging.request(page, size, Sort.by(Sort.Direction.DESC, "issuedAt"))));
        enrichParentNames(result.items());
        return result;
    }

    private void enrichParentNames(List<Invoice> rows) {
        Map<String, String> names = new HashMap<>();
        for (Invoice invoice : rows) {
            String parentId = invoice.getParentId();
            if (parentId == null || parentId.isBlank()) continue;
            invoice.setParentName(names.computeIfAbsent(parentId, users::fullNameOf));
        }
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

    // ---------- Thanh toán VietQR: tạo QR, chờ đối soát rồi Kế toán xác nhận ----------
    @Transactional
    public Map<String, Object> pay(PayRequest r) {
        return pay(r, "127.0.0.1");
    }

    @Transactional
    public Map<String, Object> pay(PayRequest r, String clientIp) {
        if (!"vietqr".equalsIgnoreCase(paymentMode)) {
            throw ApiException.serviceUnavailable(
                    "Thanh toán VietQR chưa được bật. Không có giao dịch nào được tạo.");
        }
        Invoice inv = getInvoice(r.invoiceId());
        long remaining = inv.getTotalAmount() - inv.getPaidAmount();
        if (remaining <= 0) throw ApiException.badRequest("Hóa đơn đã thanh toán đủ");

        String method = r.method() == null ? "VIETQR" : r.method().toUpperCase(Locale.ROOT);
        if (!"VIETQR".equals(method)) {
            throw ApiException.badRequest("Hệ thống chỉ hỗ trợ thanh toán trực tuyến qua VietQR");
        }

        Optional<Payment> pending = payments.findFirstByInvoiceIdAndStatusOrderByCreatedAtDesc(inv.getId(), "PENDING");
        if (pending.isPresent()) {
            PaymentGatewayTransaction transaction = gatewayTransactions.findByPaymentId(pending.get().getId())
                    .orElseThrow(() -> ApiException.notFound("Giao dịch VietQR"));
            if ("VIETQR".equals(transaction.getGateway())) {
                return paymentResponse(inv, pending.get(), transaction);
            }
            pending.get().setStatus("FAILED");
            payments.save(pending.get());
        }

        String txnRef = "VQR" + UUID.randomUUID().toString().replace("-", "")
                .substring(0, 20).toUpperCase(Locale.ROOT);
        Payment payment = payments.save(Payment.builder()
                .id(Ids.gen("pay")).invoiceId(inv.getId()).amount(remaining).method(method)
                .status("PENDING").txnRef(txnRef).createdAt(Instant.now()).build());
        VietQrGateway.VietQrPayment qr = vietQrGateway.create(txnRef, remaining);
        PaymentGatewayTransaction transaction = gatewayTransactions.save(PaymentGatewayTransaction.builder()
                .id(Ids.gen("pgt")).paymentId(payment.getId()).txnRef(txnRef).gateway(method)
                .status("PENDING").requestPayload(qr.qrImageUrl())
                .signatureValid(false).createdAt(Instant.now()).updatedAt(Instant.now()).build());
        return paymentResponse(inv, payment, transaction);
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
        return classSummaries(periodId, null, allowedClassIds, null, null, null);
    }

    public List<FinanceClassSummary> classSummaries(String periodId, Set<String> allowedClassIds,
                                                     String gradeLevel, String filterClassId, String status) {
        return classSummaries(periodId, null, allowedClassIds, gradeLevel, filterClassId, status);
    }

    public List<FinanceClassSummary> classSummaries(String periodId, String academicYearId,
                                                     Set<String> allowedClassIds, String gradeLevel,
                                                     String filterClassId, String status) {
        Set<String> scopedPeriodIds = periodIdsForScope(periodId, academicYearId);
        Map<String, SchoolClass> classMap = structure.listClasses(academicYearId, null).stream()
                .collect(java.util.stream.Collectors.toMap(SchoolClass::getId, item -> item));
        LocalDate today = LocalDate.now();
        return invoices.findAll().stream()
                .filter(invoice -> scopedPeriodIds == null || scopedPeriodIds.contains(invoice.getFeePeriodId()))
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

    @Transactional
    public Map<String, Object> markVietQrSubmitted(String paymentId) {
        Payment payment = getVietQrPayment(paymentId);
        PaymentGatewayTransaction transaction = gatewayTransactions.findByPaymentId(paymentId)
                .orElseThrow(() -> ApiException.notFound("Giao dịch VietQR"));
        if ("SUCCESS".equals(payment.getStatus())) {
            return callbackResult(getInvoice(payment.getInvoiceId()), payment, transaction);
        }
        if (!"PENDING".equals(payment.getStatus())) {
            throw ApiException.badRequest("Giao dịch VietQR không còn chờ thanh toán");
        }
        transaction.setStatus("AWAITING_CONFIRMATION");
        transaction.setUpdatedAt(Instant.now());
        gatewayTransactions.save(transaction);
        return callbackResult(getInvoice(payment.getInvoiceId()), payment, transaction);
    }

    public List<Map<String, Object>> pendingVietQrPayments() {
        return gatewayTransactions
                .findByGatewayAndStatusInOrderByCreatedAtDesc(
                        "VIETQR", List.of("PENDING", "AWAITING_CONFIRMATION"))
                .stream()
                .map(transaction -> {
                    Payment payment = payments.findById(transaction.getPaymentId()).orElse(null);
                    if (payment == null || !"PENDING".equals(payment.getStatus())) return null;
                    Invoice invoice = getInvoice(payment.getInvoiceId());
                    return paymentResponse(invoice, payment, transaction);
                })
                .filter(Objects::nonNull)
                .toList();
    }

    private Set<String> periodIdsForScope(String periodId, String academicYearId) {
        if (periodId != null && !periodId.isBlank()) {
            FeePeriod period = getPeriod(periodId);
            if (academicYearId != null && !academicYearId.isBlank()
                    && !academicYearId.equals(period.getAcademicYearId())) {
                throw ApiException.badRequest("Đợt thu không thuộc năm học đã chọn");
            }
            return Set.of(periodId);
        }
        String effectiveYearId = academicYearId == null || academicYearId.isBlank()
                ? defaultAcademicYearId() : academicYearId;
        structure.getYear(effectiveYearId);
        return periods.findByAcademicYearIdOrderByCreatedAtDesc(effectiveYearId).stream()
                .map(FeePeriod::getId).collect(java.util.stream.Collectors.toSet());
    }

    private String defaultAcademicYearId() {
        return structure.listYears().stream()
                .filter(year -> "ACTIVE".equalsIgnoreCase(year.getStatus()))
                .findFirst()
                .or(() -> structure.listYears().stream()
                        .filter(year -> !"CLOSED".equalsIgnoreCase(year.getStatus())).findFirst())
                .orElseThrow(() -> ApiException.badRequest("Hệ thống chưa có năm học đang vận hành"))
                .getId();
    }

    public List<Map<String, Object>> vietQrReceiptDeliveries() {
        return gatewayTransactions
                .findByGatewayAndStatusInOrderByCreatedAtDesc("VIETQR", List.of("SUCCESS"))
                .stream()
                .map(transaction -> payments.findById(transaction.getPaymentId())
                        .filter(payment -> "SUCCESS".equals(payment.getStatus()))
                        .map(payment -> paymentResponse(getInvoice(payment.getInvoiceId()), payment, transaction))
                        .orElse(null))
                .filter(Objects::nonNull)
                .limit(200)
                .toList();
    }

    public Map<String, Object> vietQrPaymentStatus(String paymentId) {
        Payment payment = getVietQrPayment(paymentId);
        PaymentGatewayTransaction transaction = gatewayTransactions.findByPaymentId(paymentId)
                .orElseThrow(() -> ApiException.notFound("Giao dịch VietQR"));
        return paymentResponse(getInvoice(payment.getInvoiceId()), payment, transaction);
    }

    @Transactional
    public Map<String, Object> confirmVietQrPayment(String paymentId, String bankTransactionRef) {
        Payment payment = getVietQrPayment(paymentId);
        PaymentGatewayTransaction transaction = gatewayTransactions.findByPaymentId(paymentId)
                .orElseThrow(() -> ApiException.notFound("Giao dịch VietQR"));
        Invoice invoice = getInvoice(payment.getInvoiceId());
        if ("SUCCESS".equals(payment.getStatus())) return paymentResponse(invoice, payment, transaction);
        if (!Set.of("PENDING", "AWAITING_CONFIRMATION").contains(transaction.getStatus())) {
            throw ApiException.badRequest("Giao dịch VietQR không ở trạng thái có thể xác nhận");
        }

        payment.setStatus("SUCCESS");
        payment.setPaidAt(Instant.now());
        transaction.setStatus("SUCCESS");
        // Đối soát thủ công không phải là callback có chữ ký từ cổng thanh toán.
        transaction.setSignatureValid(false);
        transaction.setCallbackPayload("ACCOUNTANT_CONFIRMED:"
                + (bankTransactionRef == null || bankTransactionRef.isBlank()
                ? payment.getTxnRef() : bankTransactionRef.trim()));
        transaction.setUpdatedAt(Instant.now());
        invoice.setPaidAmount(Math.min(invoice.getTotalAmount(), invoice.getPaidAmount() + payment.getAmount()));
        invoice.setStatus(invoice.getPaidAmount() >= invoice.getTotalAmount() ? "PAID" : "PARTIAL");

        payments.save(payment);
        gatewayTransactions.save(transaction);
        invoices.save(invoice);
        if (invoice.getParentId() != null) {
            sendVietQrReceipt(invoice, payment, "Thanh toán thành công");
        }
        return paymentResponse(invoice, payment, transaction);
    }

    @Transactional
    public Map<String, Object> rejectVietQrPayment(String paymentId) {
        Payment payment = getVietQrPayment(paymentId);
        PaymentGatewayTransaction transaction = gatewayTransactions.findByPaymentId(paymentId)
                .orElseThrow(() -> ApiException.notFound("Giao dịch VietQR"));
        if ("SUCCESS".equals(payment.getStatus())) {
            throw ApiException.badRequest("Không thể từ chối giao dịch đã được đối soát thành công");
        }
        payment.setStatus("FAILED");
        transaction.setStatus("REJECTED");
        transaction.setUpdatedAt(Instant.now());
        payments.save(payment);
        gatewayTransactions.save(transaction);
        return callbackResult(getInvoice(payment.getInvoiceId()), payment, transaction);
    }

    @Transactional
    public Map<String, Object> resendVietQrReceipt(String paymentId) {
        Payment payment = getVietQrPayment(paymentId);
        if (!"SUCCESS".equals(payment.getStatus())) {
            throw ApiException.badRequest("Chỉ có thể gửi lại biên nhận cho giao dịch đã thanh toán thành công");
        }
        Invoice invoice = getInvoice(payment.getInvoiceId());
        if (invoice.getParentId() == null || invoice.getParentId().isBlank()) {
            throw ApiException.badRequest("Hóa đơn chưa liên kết phụ huynh để gửi email biên nhận");
        }
        Map<String, Object> current = notifications.deliveryStatus("PAYMENT", payment.getId(), "EMAIL");
        String status = String.valueOf(current.getOrDefault("status", "NOT_SENT"));
        if (Set.of("PENDING", "PROCESSING", "RETRYING").contains(status)) {
            throw ApiException.conflict("Email biên nhận đang được hệ thống xử lý, vui lòng chờ");
        }
        if ("DELIVERED".equals(status)) {
            throw ApiException.conflict("Email biên nhận đã được gửi thành công");
        }
        sendVietQrReceipt(invoice, payment, "Gửi lại biên nhận thanh toán");
        PaymentGatewayTransaction transaction = gatewayTransactions.findByPaymentId(paymentId)
                .orElseThrow(() -> ApiException.notFound("Giao dịch VietQR"));
        return paymentResponse(invoice, payment, transaction);
    }

    @Scheduled(fixedDelayString = "${sse.payments.reconciliation-interval-ms:3600000}")
    @Transactional
    public void expirePendingPayments() {
        executions.run("vietqr-expiration", "Dọn giao dịch VietQR quá hạn", this::expirePendingPaymentsInternal);
    }

    private void expirePendingPaymentsInternal() {
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

    public List<Payment> allPayments() {
        return payments.findAll(Sort.by(Sort.Direction.DESC, "createdAt"));
    }

    public PageResponse<PaymentView> paymentPage(String academicYearId, String periodId,
                                                  String status, String query, int page, int size) {
        Set<String> scopedPeriods = periodIdsForScope(periodId, academicYearId);
        String normalizedQuery = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        Map<String, Invoice> invoiceMap = invoices.findAll().stream()
                .filter(invoice -> scopedPeriods == null || scopedPeriods.contains(invoice.getFeePeriodId()))
                .collect(java.util.stream.Collectors.toMap(Invoice::getId, invoice -> invoice));

        Specification<Payment> specification = Specification.where(null);
        if (scopedPeriods != null) {
            Set<String> invoiceIds = invoiceMap.keySet();
            specification = specification.and((root, ignored, builder) -> invoiceIds.isEmpty()
                    ? builder.disjunction() : root.get("invoiceId").in(invoiceIds));
        }
        if (status != null && !status.isBlank() && !"ALL".equalsIgnoreCase(status)) {
            specification = specification.and((root, ignored, builder) ->
                    builder.equal(builder.upper(root.get("status")), status.trim().toUpperCase(Locale.ROOT)));
        }
        if (!normalizedQuery.isBlank()) {
            String pattern = "%" + normalizedQuery + "%";
            Set<String> matchingInvoiceIds = invoiceMap.values().stream()
                    .filter(invoice -> containsIgnoreCase(invoice.getCode(), normalizedQuery)
                            || containsIgnoreCase(invoice.getStudentName(), normalizedQuery)
                            || containsIgnoreCase(invoice.getClassCode(), normalizedQuery))
                    .map(Invoice::getId).collect(java.util.stream.Collectors.toSet());
            specification = specification.and((root, ignored, builder) -> builder.or(
                    builder.like(builder.lower(root.get("txnRef")), pattern),
                    matchingInvoiceIds.isEmpty() ? builder.disjunction()
                            : root.get("invoiceId").in(matchingInvoiceIds)));
        }
        Page<PaymentView> result = payments.findAll(specification,
                        Paging.request(page, size, Sort.by(Sort.Direction.DESC, "createdAt")))
                .map(payment -> paymentView(payment, invoiceMap.get(payment.getInvoiceId())));
        return PageResponse.from(result);
    }

    private PaymentView paymentView(Payment payment, Invoice suppliedInvoice) {
        Invoice invoice = suppliedInvoice == null ? getInvoice(payment.getInvoiceId()) : suppliedInvoice;
        FeePeriod period = getPeriod(invoice.getFeePeriodId());
        return new PaymentView(payment.getId(), invoice.getId(), invoice.getCode(),
                invoice.getStudentId(), invoice.getStudentName(), invoice.getClassId(), invoice.getClassCode(),
                period.getId(), period.getName() == null || period.getName().isBlank() ? period.getCode() : period.getName(),
                period.getAcademicYearId(), payment.getAmount(), payment.getMethod(), payment.getStatus(),
                payment.getTxnRef(), payment.getCreatedAt(), payment.getPaidAt());
    }

    public Payment getPayment(String paymentId) {
        return payments.findById(paymentId).orElseThrow(() -> ApiException.notFound("Thanh toán"));
    }

    private Map<String, Object> paymentResponse(Invoice invoice, Payment payment,
                                                PaymentGatewayTransaction transaction) {
        VietQrGateway.VietQrPayment qr = vietQrGateway.create(payment.getTxnRef(), payment.getAmount());
        Map<String, Object> result = callbackResult(invoice, payment, transaction);
        result.put("gateway", "VIETQR");
        result.put("qrImageUrl", qr.qrImageUrl());
        result.put("bankId", qr.bankId());
        result.put("accountNo", qr.accountNo());
        result.put("accountName", qr.accountName());
        result.put("transferContent", qr.transferContent());
        result.put("expiresAt", transaction.getCreatedAt().plus(30, ChronoUnit.MINUTES));
        result.put("emailDelivery", notifications.deliveryStatus("PAYMENT", payment.getId(), "EMAIL"));
        return result;
    }

    private void sendVietQrReceipt(Invoice invoice, Payment payment, String title) {
        notifications.notifyUserWithTransactionalEmail(invoice.getParentId(), "INVOICE", title,
                String.format("Biên nhận %s%nHọc sinh: %s%nSố tiền: %,d₫%nPhương thức: VietQR%nNội dung chuyển khoản: %s%nTrạng thái: Thành công",
                        invoice.getCode(), invoice.getStudentName(), payment.getAmount(), payment.getTxnRef()),
                "PAYMENT", payment.getId());
    }

    private Payment getVietQrPayment(String paymentId) {
        Payment payment = payments.findById(paymentId)
                .orElseThrow(() -> ApiException.notFound("Thanh toán VietQR"));
        if (!"VIETQR".equals(payment.getMethod())) {
            throw ApiException.badRequest("Giao dịch không thuộc phương thức VietQR");
        }
        return payment;
    }

    private Map<String, Object> callbackResult(Invoice invoice, Payment payment,
                                               PaymentGatewayTransaction transaction) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("payment", payment);
        result.put("invoice", invoice);
        result.put("gatewayStatus", transaction.getStatus());
        return result;
    }

    /** Seed: 1 đợt thu mẫu + sinh hóa đơn cho mọi HS (để demo có dữ liệu ngay). Idempotent. */
    @Transactional
    public void seedDefaultPeriodAndInvoices() {
        if (!periods.findAll().isEmpty()) return;
        AcademicYear activeYear = structure.listYears().stream()
                .filter(year -> "ACTIVE".equalsIgnoreCase(year.getStatus())).findFirst()
                .orElseThrow(() -> ApiException.badRequest("Cần có năm học đang hoạt động trước khi tạo dữ liệu tài chính"));
        FeePeriod p = periods.save(FeePeriod.builder()
                .id("fp-hk1").code("HK1-2025").name("Học phí HK1 2025-2026").status("OPEN")
                .academicYearId(activeYear.getId())
                .dueDate(LocalDate.parse("2026-01-15")).createdAt(Instant.now()).build());
        periodItems.save(FeePeriodItem.builder().id("fpi-1").feePeriodId(p.getId())
                .name("Học phí").amount(1500000).build());
        periodItems.save(FeePeriodItem.builder().id("fpi-2").feePeriodId(p.getId())
                .name("Bảo hiểm y tế").amount(300000).build());
        generateInvoices(p.getId());
    }

    /** A8: tổng hợp doanh thu (Invoice là package-private nên gói gọn trong service). */
    public Map<String, Object> financeOverview(String academicYearId) {
        Set<String> scopedPeriodIds = periodIdsForScope(null, academicYearId);
        String effectiveYearId = academicYearId == null || academicYearId.isBlank()
                ? defaultAcademicYearId() : academicYearId;
        List<FeePeriod> scopedPeriods = periods.findByAcademicYearIdOrderByCreatedAtDesc(effectiveYearId);
        List<Invoice> all = invoices.findAll().stream()
                .filter(invoice -> scopedPeriodIds == null || scopedPeriodIds.contains(invoice.getFeePeriodId()))
                .toList();
        Set<String> scopedInvoiceIds = all.stream().map(Invoice::getId).collect(java.util.stream.Collectors.toSet());
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
                .filter(payment -> scopedPeriodIds == null || scopedInvoiceIds.contains(payment.getInvoiceId()))
                .filter(p -> "SUCCESS".equals(p.getStatus()) && p.getPaidAt() != null
                        && !p.getPaidAt().isBefore(monthStart))
                .mapToLong(Payment::getAmount).sum();

        List<Map<String, Object>> periodSummaries = scopedPeriods.stream()
                .map(period -> {
                    List<Invoice> periodInvoices = invoices.findByFeePeriodId(period.getId());
                    long periodTotal = periodInvoices.stream().mapToLong(Invoice::getTotalAmount).sum();
                    long periodPaid = periodInvoices.stream().mapToLong(Invoice::getPaidAmount).sum();
                    Map<String, Object> summary = new LinkedHashMap<>();
                    summary.put("periodId", period.getId());
                    summary.put("code", period.getCode());
                    summary.put("name", period.getName());
                    summary.put("academicYearId", period.getAcademicYearId());
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
        result.put("academicYearId", academicYearId);
        result.put("periods", periodSummaries);
        return result;
    }

    public Map<String, Object> financeOverview() {
        return financeOverview(null);
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
