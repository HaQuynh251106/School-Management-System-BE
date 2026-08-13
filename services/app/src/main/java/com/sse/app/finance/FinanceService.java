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
import org.springframework.context.ApplicationEventPublisher;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;

/** A7: Tài chính nội bộ — đợt thu, sinh hóa đơn, VietQR và đối soát. */
@Service
public class FinanceService {

    private final FeePeriodRepository periods;
    private final FeePeriodItemRepository periodItems;
    private final FeePeriodRecipientRepository periodRecipients;
    private final FeePeriodAdjustmentRepository periodAdjustments;
    private final InvoiceRepository invoices;
    private final InvoiceItemRepository invoiceItems;
    private final PaymentRepository payments;
    private final InvoiceRefundRepository refunds;
    private final PaymentGatewayTransactionRepository gatewayTransactions;
    private final StructureService structure;
    private final UserService users;
    private final NotificationService notifications;
    private final VietQrGateway vietQrGateway;
    private final SandboxPaymentGateway sandboxGateway;
    private final ApplicationEventPublisher events;
    private final String paymentMode;

    public FinanceService(FeePeriodRepository periods, FeePeriodItemRepository periodItems,
                          FeePeriodRecipientRepository periodRecipients,
                          FeePeriodAdjustmentRepository periodAdjustments,
                          InvoiceRepository invoices, InvoiceItemRepository invoiceItems,
                          PaymentRepository payments, InvoiceRefundRepository refunds,
                          PaymentGatewayTransactionRepository gatewayTransactions,
                          StructureService structure,
                          UserService users, NotificationService notifications, VietQrGateway vietQrGateway,
                          SandboxPaymentGateway sandboxGateway,
                          ApplicationEventPublisher events,
                          @Value("${sse.payments.mode:disabled}") String paymentMode) {
        this.periods = periods;
        this.periodItems = periodItems;
        this.periodRecipients = periodRecipients;
        this.periodAdjustments = periodAdjustments;
        this.invoices = invoices;
        this.invoiceItems = invoiceItems;
        this.payments = payments;
        this.refunds = refunds;
        this.gatewayTransactions = gatewayTransactions;
        this.structure = structure;
        this.users = users;
        this.notifications = notifications;
        this.vietQrGateway = vietQrGateway;
        this.sandboxGateway = sandboxGateway;
        this.events = events;
        this.paymentMode = paymentMode;
    }

    // ---------- Đợt thu ----------
    public List<FeePeriod> listPeriods() { return periods.findAll(); }

    public FeePeriod period(String id) { return getPeriod(id); }

    @Transactional
    public FeePeriod createPeriod(CreateFeePeriodRequest r) {
        String code = r.code().trim();
        if (periods.findByCode(code).isPresent()) throw ApiException.conflict("Mã đợt thu đã tồn tại");
        if (r.academicYearId() != null && !r.academicYearId().isBlank()) structure.getYear(r.academicYearId());
        FeePeriod period = periods.save(FeePeriod.builder()
                .id(r.id() == null || r.id().isBlank() ? Ids.gen("fp") : r.id())
                .code(code).name(r.name()).status("DRAFT")
                .academicYearId(r.academicYearId()).applyToGrades(r.applyToGrades())
                .dueDate(r.dueDate()).createdAt(Instant.now()).build());
        applyScope(period, r.scopeType(), r.scopeGradeLevel(), r.scopeClassId(), r.studentIds());
        return periods.save(period);
    }

    @Transactional
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
        applyScope(period, r.scopeType(), r.scopeGradeLevel(), r.scopeClassId(), r.studentIds());
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
        periodRecipients.deleteByFeePeriodId(periodId);
        periodAdjustments.deleteByFeePeriodId(periodId);
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

    public List<FeePeriodAdjustment> adjustmentsOf(String periodId) {
        getPeriod(periodId);
        return periodAdjustments.findByFeePeriodId(periodId);
    }

    public List<String> recipientIdsOf(String periodId) {
        getPeriod(periodId);
        return periodRecipients.findByFeePeriodId(periodId).stream()
                .map(FeePeriodRecipient::getStudentId).toList();
    }

    public FeePeriodAdjustment saveAdjustment(String periodId, FeePeriodAdjustmentRequest request) {
        FeePeriod period = getPeriod(periodId);
        requireDraft(period);
        UserDto student = users.dtoById(request.studentId());
        if (!"STUDENT".equals(student.role())) {
            throw ApiException.badRequest("Đối tượng miễn giảm phải là học sinh");
        }
        String type = request.type().trim().toUpperCase(Locale.ROOT);
        if (!Set.of("EXCLUDE", "DISCOUNT").contains(type)) {
            throw ApiException.badRequest("Loại ngoại lệ phải là EXCLUDE hoặc DISCOUNT");
        }
        long amount = request.amount() == null ? 0 : request.amount();
        if ("DISCOUNT".equals(type) && amount <= 0) {
            throw ApiException.badRequest("Số tiền miễn giảm phải lớn hơn 0");
        }
        FeePeriodAdjustment adjustment = periodAdjustments
                .findByFeePeriodIdAndStudentId(periodId, request.studentId())
                .orElseGet(() -> FeePeriodAdjustment.builder().id(Ids.gen("fpa"))
                        .feePeriodId(periodId).studentId(request.studentId()).build());
        adjustment.setType(type);
        adjustment.setAmount("EXCLUDE".equals(type) ? 0 : amount);
        adjustment.setReason(blankToNull(request.reason()));
        return periodAdjustments.save(adjustment);
    }

    public void deleteAdjustment(String periodId, String adjustmentId) {
        FeePeriod period = getPeriod(periodId);
        requireDraft(period);
        FeePeriodAdjustment adjustment = periodAdjustments.findById(adjustmentId)
                .orElseThrow(() -> ApiException.notFound("Miễn giảm"));
        if (!periodId.equals(adjustment.getFeePeriodId())) {
            throw ApiException.badRequest("Miễn giảm không thuộc đợt thu đã chọn");
        }
        periodAdjustments.delete(adjustment);
    }

    public FeePeriodPreview preview(String periodId) {
        FeePeriod period = getPeriod(periodId);
        List<FeePeriodItem> items = itemsOf(periodId);
        Map<String, FeePeriodAdjustment> adjustments = periodAdjustments.findByFeePeriodId(periodId).stream()
                .collect(java.util.stream.Collectors.toMap(FeePeriodAdjustment::getStudentId, item -> item));
        List<UserDto> students = scopedStudents(period);
        Set<String> errors = new LinkedHashSet<>();
        if (items.isEmpty()) errors.add("Đợt thu chưa có khoản thu");
        if (students.isEmpty()) errors.add("Phạm vi áp dụng không có học sinh");
        List<FeePeriodRecipientPreview> rows = new ArrayList<>();
        long grandTotal = 0;
        int invoiceCount = 0;
        for (UserDto student : students) {
            String gradeLevel = structure.gradeLevelOf(student.classId());
            List<FeePeriodItem> applicable = items.stream()
                    .filter(item -> item.getGradeLevel() == null || item.getGradeLevel().equals(gradeLevel))
                    .toList();
            long baseAmount = applicable.stream().mapToLong(FeePeriodItem::getAmount).sum();
            FeePeriodAdjustment adjustment = adjustments.get(student.id());
            boolean excluded = adjustment != null && "EXCLUDE".equals(adjustment.getType());
            long discount = adjustment != null && "DISCOUNT".equals(adjustment.getType())
                    ? adjustment.getAmount() : 0;
            long total = excluded ? 0 : baseAmount - discount;
            boolean invoiceExists = invoices.findByFeePeriodIdAndStudentId(periodId, student.id()).isPresent();
            List<String> parentIds = users.parentIdsOf(student.id()).stream().distinct().toList();
            if (!excluded && applicable.isEmpty()) {
                errors.add("Học sinh " + student.fullName() + " không có khoản thu phù hợp");
            }
            if (!excluded && total <= 0) {
                errors.add("Số tiền của học sinh " + student.fullName() + " không hợp lệ");
            }
            if (period.getScopeType() != null && !excluded && !invoiceExists && parentIds.isEmpty()) {
                errors.add("Học sinh " + student.fullName() + " chưa liên kết phụ huynh");
            }
            SchoolClass schoolClass = student.classId() == null ? null : structure.getClass(student.classId());
            if (!excluded && total > 0) grandTotal += total;
            if (invoiceExists) invoiceCount++;
            rows.add(new FeePeriodRecipientPreview(student.id(), student.fullName(), student.classId(),
                    schoolClass == null ? student.className() : schoolClass.getCode(), gradeLevel,
                    !parentIds.isEmpty(), excluded, discount, total, invoiceExists));
        }
        int recipientCount = (int) rows.stream()
                .filter(row -> !row.excluded() && row.totalAmount() > 0).count();
        return new FeePeriodPreview(periodId, period.getStatus(), recipientCount, invoiceCount,
                grandTotal, errors.isEmpty(), List.copyOf(errors), rows);
    }

    public FeePeriod open(String periodId) {
        FeePeriod p = getPeriod(periodId);
        if ("OPEN".equals(p.getStatus())) return p;
        FeePeriodPreview periodPreview = preview(periodId);
        if (!periodPreview.valid()) {
            throw ApiException.badRequest(String.join("; ", periodPreview.errors()));
        }
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

    private void applyScope(FeePeriod period, String requestedType, String gradeLevel,
                            String classId, List<String> studentIds) {
        periodRecipients.deleteByFeePeriodId(period.getId());
        if (requestedType == null || requestedType.isBlank()) {
            period.setScopeType(null);
            period.setScopeGradeLevel(null);
            period.setScopeClassId(null);
            return;
        }
        String type = requestedType.trim().toUpperCase(Locale.ROOT);
        if (!Set.of("SCHOOL", "GRADE", "CLASS", "STUDENTS").contains(type)) {
            throw ApiException.badRequest("Phạm vi phải là SCHOOL, GRADE, CLASS hoặc STUDENTS");
        }
        String normalizedGrade = blankToNull(gradeLevel);
        String normalizedClass = blankToNull(classId);
        if ("GRADE".equals(type) && normalizedGrade == null) {
            normalizedGrade = blankToNull(period.getApplyToGrades());
        }
        if ("GRADE".equals(type) && normalizedGrade == null) {
            throw ApiException.badRequest("Vui lòng chọn khối áp dụng");
        }
        if ("CLASS".equals(type) && normalizedClass == null) {
            throw ApiException.badRequest("Vui lòng chọn lớp áp dụng");
        }
        if (normalizedClass != null) structure.getClass(normalizedClass);
        List<String> ids = studentIds == null ? List.of() : studentIds.stream()
                .filter(Objects::nonNull).map(String::trim)
                .filter(value -> !value.isBlank()).distinct().toList();
        if ("STUDENTS".equals(type) && ids.isEmpty()) {
            throw ApiException.badRequest("Vui lòng chọn ít nhất một học sinh");
        }
        period.setScopeType(type);
        period.setScopeGradeLevel("GRADE".equals(type) ? normalizedGrade : null);
        period.setScopeClassId("CLASS".equals(type) ? normalizedClass : null);
        if ("STUDENTS".equals(type)) {
            for (String studentId : ids) {
                UserDto student = users.dtoById(studentId);
                if (!"STUDENT".equals(student.role())) {
                    throw ApiException.badRequest("Phạm vi chứa tài khoản không phải học sinh");
                }
                periodRecipients.save(FeePeriodRecipient.builder().id(Ids.gen("fpr"))
                        .feePeriodId(period.getId()).studentId(studentId).build());
            }
        }
    }

    private List<UserDto> scopedStudents(FeePeriod period) {
        List<UserDto> all = users.list("STUDENT", null, null);
        String type = period.getScopeType();
        if (type == null || type.isBlank()) {
            Set<String> grades = parseGrades(period.getApplyToGrades());
            return grades == null ? all : all.stream()
                    .filter(student -> grades.contains(structure.gradeLevelOf(student.classId()))).toList();
        }
        return switch (type) {
            case "GRADE" -> {
                Set<String> grades = parseGrades(period.getScopeGradeLevel());
                yield all.stream().filter(student -> grades != null
                        && grades.contains(structure.gradeLevelOf(student.classId()))).toList();
            }
            case "CLASS" -> all.stream().filter(student ->
                    Objects.equals(period.getScopeClassId(), student.classId())).toList();
            case "STUDENTS" -> {
                Set<String> ids = periodRecipients.findByFeePeriodId(period.getId()).stream()
                        .map(FeePeriodRecipient::getStudentId)
                        .collect(java.util.stream.Collectors.toSet());
                yield all.stream().filter(student -> ids.contains(student.id())).toList();
            }
            default -> all;
        };
    }

    // ---------- Sinh hóa đơn (flowchart 2.8) ----------
    @Transactional
    public List<Invoice> generateInvoices(String periodId) {
        FeePeriod p = getPeriod(periodId);
        FeePeriodPreview generationPreview = preview(periodId);
        if (!generationPreview.valid()) {
            throw ApiException.badRequest(String.join("; ", generationPreview.errors()));
        }
        if (!"OPEN".equals(p.getStatus())) throw ApiException.badRequest("Đợt thu phải ở trạng thái OPEN");

        List<FeePeriodItem> items = itemsOf(periodId);
        if (items.isEmpty()) throw ApiException.badRequest("Đợt thu chưa có khoản thu");
        List<Invoice> created = new ArrayList<>();

        Map<String, FeePeriodAdjustment> adjustments = periodAdjustments.findByFeePeriodId(periodId).stream()
                .collect(java.util.stream.Collectors.toMap(FeePeriodAdjustment::getStudentId, item -> item));
        for (UserDto s : scopedStudents(p)) {
            String gl = structure.gradeLevelOf(s.classId());
            FeePeriodAdjustment adjustment = adjustments.get(s.id());
            if (adjustment != null && "EXCLUDE".equals(adjustment.getType())) continue;

            List<FeePeriodItem> applicable = items.stream()
                    .filter(it -> it.getGradeLevel() == null || it.getGradeLevel().equals(gl))
                    .toList();
            if (applicable.isEmpty()) continue;

            Optional<Invoice> existing = invoices.findByFeePeriodIdAndStudentId(periodId, s.id());
            if (existing.isPresent()) {
                created.add(existing.get());
                continue;
            }

            long discount = adjustment != null && "DISCOUNT".equals(adjustment.getType())
                    ? adjustment.getAmount() : 0;
            long total = applicable.stream().mapToLong(FeePeriodItem::getAmount).sum() - discount;
            if (total <= 0) {
                throw ApiException.badRequest("Số tiền hóa đơn sau miễn giảm phải lớn hơn 0");
            }
            List<String> parentIds = users.parentIdsOf(s.id()).stream().distinct().toList();
            String parentId = parentIds.stream().findFirst().orElse(null);
            SchoolClass schoolClass = s.classId() == null ? null : structure.getClass(s.classId());

            Invoice inv = invoices.save(Invoice.builder()
                    .id(Ids.gen("inv")).code("INV-" + p.getCode() + "-" + s.id())
                    .studentId(s.id()).studentName(s.fullName()).parentId(parentId)
                    .classId(s.classId())
                    .classCode(schoolClass == null ? s.className() : schoolClass.getCode())
                    .gradeLevel(schoolClass == null ? gl : schoolClass.getGradeLevel())
                    .feePeriodId(periodId).totalAmount(total).paidAmount(0).refundedAmount(0).status("UNPAID")
                    .issuedAt(Instant.now()).dueDate(p.getDueDate()).build());

            for (FeePeriodItem it : applicable) {
                invoiceItems.save(InvoiceItem.builder().id(Ids.gen("ii"))
                        .invoiceId(inv.getId()).name(it.getName()).amount(it.getAmount()).build());
            }
            if (discount > 0) {
                invoiceItems.save(InvoiceItem.builder().id(Ids.gen("ii"))
                        .invoiceId(inv.getId()).name("Miễn giảm").amount(-discount).build());
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
        refreshOverdueStatuses();
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
        refreshOverdueStatuses();
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
        Invoice invoice = invoices.findById(id).orElseThrow(() -> ApiException.notFound("Hóa đơn"));
        String expected = collectionStatus(invoice, LocalDate.now());
        if (!expected.equals(invoice.getStatus())) {
            invoice.setStatus(expected);
            return invoices.save(invoice);
        }
        return invoice;
    }

    public Map<String, Object> invoiceDetail(String id) {
        Invoice inv = getInvoice(id);
        Map<String, Object> m = new HashMap<>();
        m.put("invoice", inv);
        m.put("items", invoiceItems.findByInvoiceId(id));
        m.put("payments", payments.findByInvoiceIdOrderByCreatedAtAsc(id));
        m.put("refunds", refunds.findByInvoiceIdOrderByCreatedAtAsc(id));
        return m;
    }

    // ---------- Thanh toán VietQR: tạo QR, chờ đối soát rồi Admin xác nhận ----------
    @Transactional
    public Map<String, Object> pay(PayRequest r) {
        return pay(r, "127.0.0.1");
    }

    @Transactional
    public Map<String, Object> pay(PayRequest r, String clientIp) {
        String method = r.method() == null ? "VIETQR" : r.method().toUpperCase(Locale.ROOT);
        if ("SANDBOX".equals(method)) {
            return createSandboxPayment(r, clientIp);
        }
        if (!"vietqr".equalsIgnoreCase(paymentMode)) {
            throw ApiException.serviceUnavailable(
                    "Thanh toán VietQR chưa được bật. Không có giao dịch nào được tạo.");
        }
        Invoice inv = getInvoice(r.invoiceId());
        assertCollectable(inv);
        long remaining = inv.getTotalAmount() - inv.getPaidAmount();
        if (remaining <= 0) throw ApiException.badRequest("Hóa đơn đã thanh toán đủ");

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

    private Map<String, Object> createSandboxPayment(PayRequest request, String clientIp) {
        sandboxGateway.requireEnabled();
        Invoice invoice = getInvoice(request.invoiceId());
        assertCollectable(invoice);
        long remaining = invoice.getTotalAmount() - invoice.getPaidAmount();
        if (remaining <= 0) throw ApiException.badRequest("Hóa đơn đã thanh toán đủ");

        String suppliedKey = blankToNull(request.idempotencyKey());
        if (suppliedKey == null || suppliedKey.length() > 80) {
            throw ApiException.badRequest("idempotencyKey phải có từ 1 đến 80 ký tự");
        }
        String idempotencyKey = invoice.getId() + ":" + suppliedKey;
        Optional<PaymentGatewayTransaction> existing = gatewayTransactions.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            Payment payment = payments.findById(existing.get().getPaymentId())
                    .orElseThrow(() -> ApiException.notFound("Thanh toán sandbox"));
            if (!payment.getInvoiceId().equals(invoice.getId())) {
                throw ApiException.conflict("Khóa chống trùng đã được dùng cho hóa đơn khác");
            }
            return sandboxPaymentResponse(invoice, payment, existing.get());
        }

        String txnRef = "SBX" + UUID.randomUUID().toString().replace("-", "")
                .substring(0, 20).toUpperCase(Locale.ROOT);
        Payment payment = payments.save(Payment.builder()
                .id(Ids.gen("pay")).invoiceId(invoice.getId()).amount(remaining).method("SANDBOX")
                .status("PENDING").txnRef(txnRef).note("Client IP: " + clientIp)
                .createdAt(Instant.now()).build());
        PaymentGatewayTransaction transaction = gatewayTransactions.save(PaymentGatewayTransaction.builder()
                .id(Ids.gen("pgt")).paymentId(payment.getId()).txnRef(txnRef)
                .gateway(SandboxPaymentGateway.GATEWAY).status("PENDING")
                .idempotencyKey(idempotencyKey).requestPayload("currency=VND&amount=" + remaining)
                .signatureValid(false).createdAt(Instant.now()).updatedAt(Instant.now()).build());
        return sandboxPaymentResponse(invoice, payment, transaction);
    }

    public Map<String, Object> sandboxPaymentStatus(String paymentId) {
        Payment payment = payments.findById(paymentId)
                .orElseThrow(() -> ApiException.notFound("Thanh toán sandbox"));
        PaymentGatewayTransaction transaction = gatewayTransactions.findByPaymentId(paymentId)
                .orElseThrow(() -> ApiException.notFound("Giao dịch sandbox"));
        if (!SandboxPaymentGateway.GATEWAY.equals(transaction.getGateway())) {
            throw ApiException.badRequest("Giao dịch không thuộc cổng sandbox");
        }
        return sandboxPaymentResponse(getInvoice(payment.getInvoiceId()), payment, transaction);
    }

    public Map<String, Object> sandboxCheckout(String txnRef) {
        sandboxGateway.requireEnabled();
        PaymentGatewayTransaction transaction = gatewayTransactions.findByTxnRef(txnRef)
                .orElseThrow(() -> ApiException.notFound("Giao dịch sandbox"));
        Payment payment = payments.findById(transaction.getPaymentId())
                .orElseThrow(() -> ApiException.notFound("Thanh toán sandbox"));
        Invoice invoice = getInvoice(payment.getInvoiceId());
        Map<String, Object> result = sandboxPaymentResponse(invoice, payment, transaction);
        result.put("merchantCode", sandboxGateway.merchantCode());
        return result;
    }

    @Transactional
    public Map<String, Object> processGatewayCallback(GatewayCallbackRequest request) {
        sandboxGateway.requireEnabled();
        sandboxGateway.verify(request);
        if (!SandboxPaymentGateway.CURRENCY.equalsIgnoreCase(request.currency())) {
            throw ApiException.badRequest("Loại tiền callback không hợp lệ");
        }
        PaymentGatewayTransaction transaction = gatewayTransactions.findByTxnRefForUpdate(request.txnRef())
                .orElseThrow(() -> ApiException.notFound("Giao dịch sandbox"));
        if (!SandboxPaymentGateway.GATEWAY.equals(transaction.getGateway())) {
            throw ApiException.badRequest("Gateway callback không khớp giao dịch");
        }
        Payment payment = payments.findById(transaction.getPaymentId())
                .orElseThrow(() -> ApiException.notFound("Thanh toán sandbox"));
        Invoice invoice = getInvoice(payment.getInvoiceId());
        if (payment.getAmount() != request.amount()) {
            throw ApiException.badRequest("Số tiền callback không khớp giao dịch");
        }

        Optional<PaymentGatewayTransaction> sameGatewayTxn =
                gatewayTransactions.findByGatewayTransactionId(request.gatewayTransactionId());
        if (sameGatewayTxn.isPresent() && !sameGatewayTxn.get().getId().equals(transaction.getId())) {
            throw ApiException.conflict("Mã giao dịch gateway đã được xử lý");
        }
        Optional<PaymentGatewayTransaction> sameEvent =
                gatewayTransactions.findByCallbackEventId(request.callbackEventId());
        if (sameEvent.isPresent()) {
            if (!sameEvent.get().getId().equals(transaction.getId())) {
                throw ApiException.conflict("Sự kiện callback đã được xử lý");
            }
            return callbackResult(invoice, payment, transaction);
        }

        String callbackStatus = request.status().toUpperCase(Locale.ROOT);
        if (!Set.of("SUCCESS", "FAILED").contains(callbackStatus)) {
            throw ApiException.badRequest("Trạng thái callback không hợp lệ");
        }
        transaction.setGatewayTransactionId(request.gatewayTransactionId());
        transaction.setCallbackEventId(request.callbackEventId());
        transaction.setSignatureValid(true);
        transaction.setCallbackPayload(String.format(
                "merchant=%s;event=%s;gatewayTransaction=%s;amount=%d;currency=%s;status=%s",
                request.merchantCode(), request.callbackEventId(), request.gatewayTransactionId(),
                request.amount(), request.currency().toUpperCase(Locale.ROOT), callbackStatus));
        transaction.setUpdatedAt(Instant.now());

        if ("FAILED".equals(callbackStatus)) {
            if (!"SUCCESS".equals(payment.getStatus())) payment.setStatus("FAILED");
            transaction.setStatus("FAILED");
        } else if (!"SUCCESS".equals(payment.getStatus())) {
            payment.setStatus("SUCCESS");
            payment.setPaidAt(Instant.now());
            payment.setReceiptCode("REC-" + UUID.randomUUID().toString().replace("-", "")
                    .substring(0, 12).toUpperCase(Locale.ROOT));
            payment.setPayerName(invoice.getParentId() == null ? null : users.fullNameOf(invoice.getParentId()));
            payment.setRecordedBy("PAYMENT_GATEWAY");
            transaction.setStatus("SUCCESS");
            invoice.setPaidAmount(Math.min(invoice.getTotalAmount(), invoice.getPaidAmount() + payment.getAmount()));
            invoice.setStatus(collectionStatus(invoice, LocalDate.now()));
            invoices.save(invoice);
            if (invoice.getParentId() != null) {
                notifications.notifyUserWithTransactionalEmail(invoice.getParentId(), "INVOICE",
                        "Thanh toán trực tuyến thành công",
                        String.format("Biên nhận %s%nHọc sinh: %s%nSố tiền: %,d₫%nMã giao dịch: %s",
                                invoice.getCode(), invoice.getStudentName(), payment.getAmount(),
                                request.gatewayTransactionId()), "PAYMENT", payment.getId());
            }
        }
        payments.save(payment);
        gatewayTransactions.save(transaction);
        return callbackResult(invoice, payment, transaction);
    }

    private Map<String, Object> sandboxPaymentResponse(Invoice invoice, Payment payment,
                                                       PaymentGatewayTransaction transaction) {
        Map<String, Object> result = callbackResult(invoice, payment, transaction);
        result.put("gateway", SandboxPaymentGateway.GATEWAY);
        result.put("paymentUrl", sandboxGateway.paymentUrl(transaction.getTxnRef()));
        result.put("currency", SandboxPaymentGateway.CURRENCY);
        result.put("expiresAt", transaction.getCreatedAt().plus(30, ChronoUnit.MINUTES));
        return result;
    }

    @Transactional
    public Map<String, Object> recordCashPayment(String invoiceId, Long requestedAmount,
                                                 String payerName, String note, String actorId) {
        Invoice invoice = getInvoice(invoiceId);
        assertCollectable(invoice);
        long remaining = invoice.getTotalAmount() - invoice.getPaidAmount();
        if (remaining <= 0) throw ApiException.badRequest("Hóa đơn đã thanh toán đủ");
        long amount = requestedAmount == null ? remaining : requestedAmount;
        if (amount <= 0 || amount > remaining) {
            throw ApiException.badRequest("Số tiền thu phải lớn hơn 0 và không vượt quá công nợ còn lại");
        }
        String resolvedPayer = blankToNull(payerName);
        if (resolvedPayer == null && invoice.getParentId() != null) {
            resolvedPayer = users.fullNameOf(invoice.getParentId());
        }
        Payment payment = payments.save(Payment.builder()
                .id(Ids.gen("pay")).invoiceId(invoice.getId()).amount(amount).method("CASH")
                .status("SUCCESS").txnRef("CASH-" + Ids.gen("tx"))
                .receiptCode("REC-" + UUID.randomUUID().toString().replace("-", "")
                        .substring(0, 12).toUpperCase(Locale.ROOT))
                .payerName(resolvedPayer).note(blankToNull(note)).recordedBy(actorId)
                .createdAt(Instant.now()).paidAt(Instant.now()).build());
        invoice.setPaidAmount(invoice.getPaidAmount() + amount);
        invoice.setStatus(collectionStatus(invoice, LocalDate.now()));
        invoices.save(invoice);
        if (invoice.getParentId() != null) {
            notifications.notifyUserWithTransactionalEmail(invoice.getParentId(), "INVOICE", "Biên nhận thanh toán học phí",
                    String.format("Biên nhận %s: %,d₫ (tiền mặt). Còn lại: %,d₫",
                            invoice.getCode(), amount, invoice.getTotalAmount() - invoice.getPaidAmount()),
                    "PAYMENT", payment.getId());
        }
        events.publishEvent(new PaymentChangedEvent(invoice.getId(), payment.getId(), "CASH_RECORDED"));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("payment", payment);
        result.put("invoice", invoice);
        return result;
    }

    /** Sinh đúng một hóa đơn sau khi đăng ký CLB được duyệt. */
    @Transactional
    public String createClubInvoice(String clubId, String registrationId, String clubName,
                                    String studentId, long amount) {
        String sourceId = "CLUB:" + clubId + ":" + registrationId;
        Optional<Invoice> existing = invoices.findByFeePeriodIdAndStudentId(sourceId, studentId);
        if (existing.isPresent()) return existing.get().getId();
        UserDto student = users.dtoById(studentId);
        List<String> parentIds = users.parentIdsOf(studentId).stream().distinct().toList();
        String parentId = parentIds.stream().findFirst().orElse(null);
        SchoolClass schoolClass = student.classId() == null ? null : structure.getClass(student.classId());
        Invoice invoice = invoices.save(Invoice.builder()
                .id(Ids.gen("inv"))
                .code("INV-CLUB-" + registrationId)
                .studentId(studentId).studentName(student.fullName()).parentId(parentId)
                .classId(student.classId())
                .classCode(schoolClass == null ? student.className() : schoolClass.getCode())
                .gradeLevel(schoolClass == null ? structure.gradeLevelOf(student.classId()) : schoolClass.getGradeLevel())
                .feePeriodId(sourceId).totalAmount(amount).paidAmount(0).refundedAmount(0)
                .status("UNPAID").issuedAt(Instant.now()).dueDate(LocalDate.now().plusDays(7)).build());
        invoiceItems.save(InvoiceItem.builder().id(Ids.gen("ii")).invoiceId(invoice.getId())
                .name("Phí câu lạc bộ " + clubName).amount(amount).build());
        if (!parentIds.isEmpty()) {
            notifications.notifyUsers(parentIds, "FEE", "IMPORTANT", "Phí câu lạc bộ",
                    String.format("%s - %s: %,d₫", student.fullName(), clubName, amount),
                    "INVOICE", invoice.getId());
        }
        return invoice.getId();
    }

    /** Đóng hóa đơn chưa thu hoặc hoàn toàn bộ số tiền đã thu khi hủy CLB. */
    @Transactional
    public void cancelOrRefundClubInvoice(String invoiceId, String reason, String actorId) {
        Invoice invoice = getInvoice(invoiceId);
        if ("CANCELLED".equals(invoice.getStatus()) || "REFUNDED".equals(invoice.getStatus())) return;
        if (invoice.getPaidAmount() > invoice.getRefundedAmount()) {
            long amount = invoice.getPaidAmount() - invoice.getRefundedAmount();
            InvoiceRefund refund = refunds.save(InvoiceRefund.builder()
                    .id(Ids.gen("rf")).invoiceId(invoiceId).amount(amount).method("MANUAL")
                    .reason(reason).status("SUCCESS").createdBy(actorId).createdAt(Instant.now()).build());
            invoice.setRefundedAmount(invoice.getPaidAmount());
            invoice.setStatus("REFUNDED");
            if (invoice.getParentId() != null) {
                notifications.notifyUserWithTransactionalEmail(invoice.getParentId(), "INVOICE",
                        "Hoàn phí câu lạc bộ", String.format("Hóa đơn %s đã hoàn %,d₫. Lý do: %s",
                                invoice.getCode(), amount, reason), "INVOICE_REFUND", refund.getId());
            }
        } else {
            invoice.setStatus("CANCELLED");
        }
        invoices.save(invoice);
    }

    @Transactional
    public Map<String, Object> refundInvoice(String invoiceId, Long requestedAmount,
                                             String reason, String actorId) {
        Invoice invoice = getInvoice(invoiceId);
        if (!Set.of("PAID", "PARTIALLY_REFUNDED").contains(invoice.getStatus())) {
            throw ApiException.conflict("Chỉ có thể hoàn tiền hóa đơn đã thanh toán đủ");
        }
        long refundable = invoice.getPaidAmount() - invoice.getRefundedAmount();
        long amount = requestedAmount == null ? refundable : requestedAmount;
        if (amount <= 0 || amount > refundable) {
            throw ApiException.badRequest("Số tiền hoàn phải lớn hơn 0 và không vượt quá số tiền còn có thể hoàn");
        }
        InvoiceRefund refund = refunds.save(InvoiceRefund.builder()
                .id(Ids.gen("rf")).invoiceId(invoice.getId()).amount(amount)
                .method("MANUAL").reason(reason.trim()).status("SUCCESS")
                .createdBy(actorId).createdAt(Instant.now()).build());
        invoice.setRefundedAmount(invoice.getRefundedAmount() + amount);
        invoice.setStatus(invoice.getRefundedAmount() >= invoice.getPaidAmount()
                ? "REFUNDED" : "PARTIALLY_REFUNDED");
        invoices.save(invoice);
        if (invoice.getParentId() != null) {
            notifications.notifyUserWithTransactionalEmail(invoice.getParentId(), "INVOICE", "Hoàn tiền học phí",
                    String.format("Hóa đơn %s đã hoàn %,d₫. Lý do: %s",
                            invoice.getCode(), amount, reason.trim()),
                    "INVOICE_REFUND", refund.getId());
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("refund", refund);
        result.put("invoice", invoice);
        return result;
    }

    private void assertCollectable(Invoice invoice) {
        if (!InvoiceStateMachine.isCollectable(invoice.getStatus())) {
            throw ApiException.conflict("Hóa đơn không còn ở trạng thái có thể thu tiền");
        }
    }

    private String collectionStatus(Invoice invoice, LocalDate today) {
        return InvoiceStateMachine.resolve(invoice.getTotalAmount(), invoice.getPaidAmount(),
                invoice.getRefundedAmount(), invoice.getDueDate(), invoice.getStatus(), today);
    }

    @Scheduled(cron = "0 5 0 * * *")
    @Transactional
    public void refreshOverdueStatuses() {
        LocalDate today = LocalDate.now();
        List<Invoice> changed = new ArrayList<>();
        for (Invoice invoice : invoices.findAll()) {
            String expected = collectionStatus(invoice, today);
            if (!expected.equals(invoice.getStatus())) {
                invoice.setStatus(expected);
                changed.add(invoice);
            }
        }
        if (!changed.isEmpty()) invoices.saveAll(changed);
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
        events.publishEvent(new PaymentChangedEvent(payment.getInvoiceId(), payment.getId(), "SUBMITTED"));
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

    @Transactional
    public Map<String, Object> confirmVietQrPayment(String paymentId, String bankTransactionRef,
                                                    String actorId) {
        Payment payment = getVietQrPayment(paymentId);
        PaymentGatewayTransaction transaction = gatewayTransactions.findByPaymentId(paymentId)
                .orElseThrow(() -> ApiException.notFound("Giao dịch VietQR"));
        Invoice invoice = getInvoice(payment.getInvoiceId());
        if ("SUCCESS".equals(payment.getStatus())) return callbackResult(invoice, payment, transaction);
        if (!Set.of("PENDING", "AWAITING_CONFIRMATION").contains(transaction.getStatus())) {
            throw ApiException.badRequest("Giao dịch VietQR không ở trạng thái có thể xác nhận");
        }

        payment.setStatus("SUCCESS");
        payment.setPaidAt(Instant.now());
        payment.setReceiptCode("REC-" + UUID.randomUUID().toString().replace("-", "")
                .substring(0, 12).toUpperCase(Locale.ROOT));
        payment.setPayerName(invoice.getParentId() == null ? null : users.fullNameOf(invoice.getParentId()));
        payment.setRecordedBy(actorId);
        transaction.setStatus("SUCCESS");
        transaction.setSignatureValid(true);
        transaction.setCallbackPayload("ADMIN_CONFIRMED:"
                + (bankTransactionRef == null || bankTransactionRef.isBlank()
                ? payment.getTxnRef() : bankTransactionRef.trim()));
        transaction.setUpdatedAt(Instant.now());
        invoice.setPaidAmount(Math.min(invoice.getTotalAmount(), invoice.getPaidAmount() + payment.getAmount()));
        invoice.setStatus(collectionStatus(invoice, LocalDate.now()));

        payments.save(payment);
        gatewayTransactions.save(transaction);
        invoices.save(invoice);
        if (invoice.getParentId() != null) {
            notifications.notifyUserWithTransactionalEmail(invoice.getParentId(), "INVOICE", "Thanh toán thành công",
                    String.format("Biên nhận %s%nHọc sinh: %s%nSố tiền: %,d₫%nPhương thức: VietQR%nNội dung chuyển khoản: %s%nTrạng thái: Thành công",
                            invoice.getCode(), invoice.getStudentName(), payment.getAmount(), payment.getTxnRef()),
                    "PAYMENT", payment.getId());
        }
        events.publishEvent(new PaymentChangedEvent(invoice.getId(), payment.getId(), "CONFIRMED"));
        return callbackResult(invoice, payment, transaction);
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
        events.publishEvent(new PaymentChangedEvent(payment.getInvoiceId(), payment.getId(), "REJECTED"));
        return callbackResult(getInvoice(payment.getInvoiceId()), payment, transaction);
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
                    events.publishEvent(new PaymentChangedEvent(
                            payment.getInvoiceId(), payment.getId(), "EXPIRED"));
                }
            });
            transaction.setStatus("EXPIRED");
            transaction.setUpdatedAt(Instant.now());
            gatewayTransactions.save(transaction);
        }
    }

    public List<Payment> paymentsOf(String invoiceId) {
        return payments.findByInvoiceIdOrderByCreatedAtAsc(invoiceId);
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
        return result;
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
