package com.sse.app.finance;

import com.sse.app.academic.structure.StructureService;
import com.sse.app.common.ApiException;
import com.sse.app.common.Ids;
import com.sse.app.event.DomainEventPublisher;
import com.sse.app.finance.FinanceDtos.*;
import com.sse.app.identity.UserDto;
import com.sse.app.identity.UserService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

/** A7: fee periods and invoices. Payment state transitions live in {@link PaymentService}. */
@Service
public class FinanceService {

    private static final Set<String> TARGET_TYPES = Set.of("ALL", "GRADE", "CLASS", "STUDENT");
    private static final Set<String> FEE_TYPES = Set.of("TUITION", "MEAL", "TRANSPORT", "ACTIVITY", "OTHER");
    private static final int INVOICE_BATCH_SIZE = 100;

    private final FeePeriodRepository periods;
    private final FeePeriodItemRepository periodItems;
    private final FeePeriodTargetRepository periodTargets;
    private final FeePeriodItemTargetRepository itemTargets;
    private final InvoiceRepository invoices;
    private final InvoiceItemRepository invoiceItems;
    private final PaymentRepository payments;
    private final BankTransferService bankTransfers;
    private final StructureService structure;
    private final UserService users;
    private final DomainEventPublisher events;

    public FinanceService(FeePeriodRepository periods, FeePeriodItemRepository periodItems,
                          FeePeriodTargetRepository periodTargets, FeePeriodItemTargetRepository itemTargets,
                          InvoiceRepository invoices, InvoiceItemRepository invoiceItems,
                          PaymentRepository payments, BankTransferService bankTransfers, StructureService structure,
                          UserService users, DomainEventPublisher events) {
        this.periods = periods;
        this.periodItems = periodItems;
        this.periodTargets = periodTargets;
        this.itemTargets = itemTargets;
        this.invoices = invoices;
        this.invoiceItems = invoiceItems;
        this.payments = payments;
        this.bankTransfers = bankTransfers;
        this.structure = structure;
        this.users = users;
        this.events = events;
    }

    public List<FeePeriod> listPeriods() {
        return periods.findAll().stream()
                .sorted(Comparator.comparing(FeePeriod::getCreatedAt,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .map(this::hydratePeriodTargets)
                .toList();
    }

    @Transactional
    public FeePeriod updatePeriodMetadata(String periodId, UpdateFeePeriodMetadataRequest request) {
        FeePeriod period = getPeriodForUpdate(periodId);
        String academicYearId = blankToNull(request.academicYearId());
        String semesterId = blankToNull(request.semesterId());
        if (academicYearId == null || semesterId == null) {
            throw ApiException.badRequest("Năm học và học kỳ là bắt buộc");
        }
        var semester = structure.listSemesters(null).stream()
                .filter(item -> semesterId.equals(item.getId()))
                .findFirst()
                .orElseThrow(() -> ApiException.badRequest("Học kỳ không tồn tại"));
        if (!academicYearId.equals(semester.getAcademicYearId())) {
            throw ApiException.badRequest("Học kỳ không thuộc năm học đã chọn");
        }

        period.setFeeType(normalizeFeeType(request.feeType()));
        period.setAcademicYearId(academicYearId);
        period.setSemesterId(semesterId);
        return hydratePeriodTargets(periods.save(period));
    }

    @Transactional
    public FeePeriod createPeriod(CreateFeePeriodRequest request) {
        String code = request.code().trim().toUpperCase(Locale.ROOT);
        if (periods.existsByCodeIgnoreCase(code)) {
            throw ApiException.conflict("Mã đợt thu đã tồn tại");
        }

        String semesterId = blankToNull(request.semesterId());
        String academicYearId = blankToNull(request.academicYearId());
        if (semesterId != null) {
            var semester = structure.listSemesters(null).stream()
                    .filter(item -> semesterId.equals(item.getId()))
                    .findFirst()
                    .orElseThrow(() -> ApiException.badRequest("Học kỳ không tồn tại"));
            if (academicYearId != null && !academicYearId.equals(semester.getAcademicYearId())) {
                throw ApiException.badRequest("Học kỳ không thuộc năm học đã chọn");
            }
            academicYearId = semester.getAcademicYearId();
        }
        String feeType = normalizeFeeType(request.feeType());
        TargetSelection target = resolveTarget(
                request.targetType(), request.targetIds(), request.applyToGrades());
        validateTargets(target, academicYearId);

        FeePeriod period = FeePeriod.builder()
                .id(request.id() == null || request.id().isBlank() ? Ids.gen("fp") : request.id())
                .code(code)
                .name(request.name().trim())
                .status("DRAFT")
                .academicYearId(academicYearId)
                .feeType(feeType)
                .semesterId(semesterId)
                .applyToGrades("GRADE".equals(target.type()) ? String.join(",", target.ids()) : null)
                .targetType(target.type())
                .dueDate(request.dueDate())
                .createdAt(Instant.now())
                .build();
        try {
            period = periods.saveAndFlush(period);
        } catch (DataIntegrityViolationException ex) {
            throw ApiException.conflict("Mã đợt thu đã tồn tại");
        }
        savePeriodTargets(period.getId(), target);
        return hydratePeriodTargets(period);
    }

    public List<FeePeriodItem> itemsOf(String periodId) {
        getPeriod(periodId);
        return periodItems.findByFeePeriodId(periodId).stream()
                .map(this::hydrateItemTargets)
                .toList();
    }

    @Transactional
    public FeePeriodItem addItem(String periodId, AddFeeItemRequest request) {
        FeePeriod period = getPeriodForUpdate(periodId);
        if (!"DRAFT".equals(period.getStatus())) {
            throw ApiException.badRequest("Chỉ được thêm khoản thu khi đợt thu còn ở trạng thái DRAFT");
        }
        if (request.amount() == null || request.amount() <= 0) {
            throw ApiException.badRequest("Số tiền khoản thu phải lớn hơn 0");
        }

        TargetSelection target = resolveTarget(
                request.targetType(), request.targetIds(), request.gradeLevel());
        if (!Set.of("ALL", "STUDENT").contains(target.type())) {
            throw ApiException.badRequest("Khoản thu chỉ áp dụng cho toàn bộ phạm vi đợt thu hoặc một học sinh");
        }
        if ("STUDENT".equals(target.type()) && target.ids().size() != 1) {
            throw ApiException.badRequest("Khoản thu cá nhân phải chọn đúng một học sinh");
        }
        validateTargets(target, period.getAcademicYearId());
        validateItemTargetWithinPeriod(period, target);

        FeePeriodItem item = periodItems.saveAndFlush(FeePeriodItem.builder()
                .id(request.id() == null || request.id().isBlank() ? Ids.gen("fpi") : request.id())
                .feePeriodId(periodId)
                .name(request.name().trim())
                .amount(request.amount())
                .gradeLevel("GRADE".equals(target.type()) && target.ids().size() == 1 ? target.ids().get(0) : null)
                .targetType(target.type())
                .build());
        saveItemTargets(item.getId(), target);
        return hydrateItemTargets(item);
    }

    @Transactional
    public void deleteItem(String periodId, String itemId) {
        FeePeriod period = getPeriodForUpdate(periodId);
        if (!"DRAFT".equals(period.getStatus())) {
            throw ApiException.badRequest("Chỉ được xóa khoản thu khi đợt thu còn ở trạng thái DRAFT");
        }
        FeePeriodItem item = periodItems.findById(itemId)
                .orElseThrow(() -> ApiException.notFound("Khoản thu"));
        if (!periodId.equals(item.getFeePeriodId())) {
            throw ApiException.notFound("Khoản thu");
        }
        itemTargets.deleteByFeePeriodItemId(itemId);
        periodItems.delete(item);
    }

    @Transactional
    public FeePeriod open(String periodId) {
        FeePeriod period = getPeriodForUpdate(periodId);
        if ("OPEN".equals(period.getStatus())) {
            return hydratePeriodTargets(period);
        }
        if (!"DRAFT".equals(period.getStatus())) {
            throw ApiException.badRequest("Chỉ đợt thu DRAFT mới có thể mở");
        }
        if (periodItems.findByFeePeriodId(periodId).isEmpty()) {
            throw ApiException.badRequest("Chưa có khoản thu nào. Hãy thêm ít nhất một khoản thu trước khi mở đợt");
        }
        period.setStatus("OPEN");
        return hydratePeriodTargets(periods.save(period));
    }

    @Transactional
    public FeePeriod close(String periodId) {
        FeePeriod period = getPeriodForUpdate(periodId);
        if ("CLOSED".equals(period.getStatus())) {
            return hydratePeriodTargets(period);
        }
        if (!"PUBLISHED".equals(period.getStatus())) {
            throw ApiException.badRequest("Chỉ đợt thu đã phát hành mới có thể đóng");
        }
        period.setStatus("CLOSED");
        period.setClosedAt(Instant.now());
        return hydratePeriodTargets(periods.save(period));
    }

    @Transactional
    public FeePeriod cancel(String periodId, String reason) {
        FeePeriod period = getPeriodForUpdate(periodId);
        if ("CANCELLED".equals(period.getStatus())) {
            return hydratePeriodTargets(period);
        }
        if (!Set.of("DRAFT", "OPEN", "PUBLISHED").contains(period.getStatus())) {
            throw ApiException.badRequest("Đợt thu ở trạng thái hiện tại không thể hủy");
        }

        List<Invoice> periodInvoices = invoices.findByFeePeriodId(periodId);
        boolean hasCollectedMoney = periodInvoices.stream().anyMatch(invoice -> invoice.getPaidAmount() > 0
                || "PARTIAL".equals(invoice.getStatus()) || "PAID".equals(invoice.getStatus()));
        if (hasCollectedMoney) {
            throw ApiException.conflict("Đợt thu đã phát sinh thanh toán; cần hoàn tiền trước khi hủy");
        }

        periodInvoices.forEach(invoice -> invoice.setStatus("CANCELLED"));
        invoices.saveAll(periodInvoices);
        List<Payment> autoPayments = periodInvoices.stream()
                .flatMap(invoice -> payments.findByInvoiceId(invoice.getId()).stream())
                .filter(Payment::isAutoProvisioned)
                .filter(payment -> "PENDING".equals(payment.getStatus()))
                .toList();
        autoPayments.forEach(payment -> {
            payment.setStatus("FAILED");
            payment.setNote("Đợt thu đã bị hủy");
            payment.setUpdatedAt(Instant.now());
        });
        payments.saveAll(autoPayments);
        period.setStatus("CANCELLED");
        period.setCancelledAt(Instant.now());
        period.setCancellationReason(blankToNull(reason));
        return hydratePeriodTargets(periods.save(period));
    }

    @Transactional
    public FeePeriod recallToDraft(String periodId) {
        FeePeriod period = getPeriodForUpdate(periodId);
        if (!"PUBLISHED".equals(period.getStatus())) {
            throw ApiException.badRequest("Chỉ đợt thu đã phát hành mới có thể thu hồi về nháp");
        }

        List<Invoice> periodInvoices = invoices.findByFeePeriodId(periodId);
        List<Payment> autoPayments = periodInvoices.stream()
                .flatMap(invoice -> payments.findByInvoiceId(invoice.getId()).stream())
                .filter(Payment::isAutoProvisioned)
                .filter(payment -> "PENDING".equals(payment.getStatus()))
                .toList();
        boolean hasPaymentActivity = periodInvoices.stream().anyMatch(invoice ->
                invoice.getPaidAmount() > 0
                        || payments.findByInvoiceId(invoice.getId()).stream()
                        .anyMatch(payment -> "SUCCESS".equals(payment.getStatus())
                                || ("PENDING".equals(payment.getStatus()) && !payment.isAutoProvisioned())));
        if (hasPaymentActivity) {
            throw ApiException.conflict("Đợt thu đã có giao dịch thanh toán nên không thể thu hồi về nháp");
        }

        payments.deleteAll(autoPayments);
        payments.flush();

        for (Invoice invoice : periodInvoices) {
            invoiceItems.deleteByInvoiceId(invoice.getId());
            events.publish("finance.invoice.recalled", invoice.getStudentId(), "invoice", invoice.getId(),
                    Map.of(
                            "studentId", invoice.getStudentId(),
                            "message", "Hóa đơn " + invoice.getCode() + " đã được nhà trường thu hồi để điều chỉnh."
                    ));
        }
        invoices.deleteAll(periodInvoices);
        invoices.flush();

        period.setStatus("DRAFT");
        period.setPublishedAt(null);
        period.setClosedAt(null);
        return hydratePeriodTargets(periods.save(period));
    }

    private FeePeriod getPeriod(String id) {
        return periods.findById(id).orElseThrow(() -> ApiException.notFound("Đợt thu"));
    }

    private FeePeriod getPeriodForUpdate(String id) {
        return periods.findByIdForUpdate(id).orElseThrow(() -> ApiException.notFound("Đợt thu"));
    }

    public InvoicePreview previewInvoices(String periodId) {
        FeePeriod period = getPeriod(periodId);
        BillingComputation computation = computeBilling(period);
        long existingTotal = computation.existingInvoices().stream().mapToLong(Invoice::getTotalAmount).sum();
        List<BillingStudent> newPlans = computation.students().stream()
                .filter(plan -> plan.existingInvoice() == null)
                .toList();
        long newTotal = newPlans.stream().mapToLong(BillingStudent::totalAmount).sum();

        List<InvoicePreviewStudent> studentRows = computation.students().stream()
                .sorted(Comparator.comparing((BillingStudent plan) -> blankToDefault(plan.student().className(), ""))
                        .thenComparing(plan -> blankToDefault(plan.student().fullName(), "")))
                .map(plan -> new InvoicePreviewStudent(
                        plan.student().id(), plan.student().fullName(), plan.student().classId(), plan.student().className(),
                        plan.items().size(), plan.totalAmount(), plan.existingInvoice() != null))
                .toList();

        return new InvoicePreview(
                period.getId(), period.getStatus(), computation.targetedStudentCount(), computation.students().size(),
                computation.existingInvoices().size(), newPlans.size(), existingTotal, newTotal,
                existingTotal + newTotal, studentRows);
    }

    /**
     * Idempotent invoice generation. The fee-period row is locked so concurrent
     * requests for the same period are serialized across application instances.
     */
    @Transactional
    public List<Invoice> generateInvoices(String periodId) {
        FeePeriod period = getPeriodForUpdate(periodId);
        if ("PUBLISHED".equals(period.getStatus())) {
            return List.of();
        }
        if (!"OPEN".equals(period.getStatus())) {
            throw ApiException.badRequest("Đợt thu phải ở trạng thái OPEN");
        }

        BillingComputation computation = computeBilling(period);
        if (computation.items().isEmpty()) {
            throw ApiException.badRequest("Đợt thu chưa có khoản thu");
        }
        if (computation.students().isEmpty()) {
            throw ApiException.badRequest("Không có học sinh nào đủ điều kiện để phát hành hóa đơn");
        }

        List<BillingStudent> pendingPlans = computation.students().stream()
                .filter(plan -> plan.existingInvoice() == null)
                .toList();
        List<Invoice> created = new ArrayList<>();

        for (int offset = 0; offset < pendingPlans.size(); offset += INVOICE_BATCH_SIZE) {
            List<BillingStudent> batchPlans = pendingPlans.subList(
                    offset, Math.min(offset + INVOICE_BATCH_SIZE, pendingPlans.size()));
            List<Invoice> batchInvoices = new ArrayList<>();
            Map<String, BillingStudent> plansByInvoiceId = new LinkedHashMap<>();

            for (BillingStudent plan : batchPlans) {
                String parentId = users.parentIdsOf(plan.student().id()).stream().findFirst().orElse(null);
                Invoice invoice = Invoice.builder()
                        .id(Ids.gen("inv"))
                        .code("INV-" + period.getCode() + "-" + plan.student().id())
                        .studentId(plan.student().id())
                        .studentName(plan.student().fullName())
                        .parentId(parentId)
                        .feePeriodId(periodId)
                        .totalAmount(plan.totalAmount())
                        .paidAmount(0)
                        .status(initialInvoiceStatus(period.getDueDate()))
                        .issuedAt(Instant.now())
                        .dueDate(period.getDueDate())
                        .build();
                batchInvoices.add(invoice);
                plansByInvoiceId.put(invoice.getId(), plan);
            }

            try {
                invoices.saveAll(batchInvoices);
                invoices.flush();
            } catch (DataIntegrityViolationException ex) {
                throw ApiException.conflict("Hóa đơn của học sinh trong đợt thu này đã tồn tại");
            }

            List<InvoiceItem> snapshots = new ArrayList<>();
            for (Invoice invoice : batchInvoices) {
                BillingStudent plan = plansByInvoiceId.get(invoice.getId());
                for (FeePeriodItem item : plan.items()) {
                    snapshots.add(InvoiceItem.builder()
                            .id(Ids.gen("ii"))
                            .invoiceId(invoice.getId())
                            .feePeriodItemId(item.getId())
                            .name(item.getName())
                            .amount(item.getAmount())
                            .sourceTargetType(effectiveTargetType(item.getTargetType()))
                            .build());
                }
            }
            invoiceItems.saveAll(snapshots);
            invoiceItems.flush();
            provisionBankTransfers(batchInvoices);
            created.addAll(batchInvoices);

            for (Invoice invoice : batchInvoices) {
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("studentId", invoice.getStudentId());
                payload.put("parentId", invoice.getParentId());
                payload.put("message", String.format("%s - %s: %,d VND",
                        invoice.getStudentName(), invoice.getCode(), invoice.getTotalAmount()));
                events.publish("finance.invoice.issued", invoice.getStudentId(), "invoice", invoice.getId(), payload);
            }
        }
        period.setStatus("PUBLISHED");
        period.setPublishedAt(Instant.now());
        periods.save(period);
        return created;
    }

    private void provisionBankTransfers(List<Invoice> batchInvoices) {
        if (!bankTransfers.enabled()) return;
        Instant now = Instant.now();
        List<Payment> provisioned = new ArrayList<>();
        for (Invoice invoice : batchInvoices) {
            boolean exists = payments.findByInvoiceId(invoice.getId()).stream()
                    .anyMatch(payment -> "MB_BANK_TRANSFER".equals(payment.getMethod())
                            && "PENDING".equals(payment.getStatus()));
            if (exists) continue;

            String paymentId = Ids.gen("pay");
            Payment payment = Payment.builder()
                    .id(paymentId)
                    .invoiceId(invoice.getId())
                    .amount(invoice.getTotalAmount())
                    .method("MB_BANK_TRANSFER")
                    .status("PENDING")
                    .txnRef("MB_BANK_TRANSFER-" + Ids.gen("tx"))
                    .note("QR chuyển khoản MB được tạo tự động khi phát hành hóa đơn")
                    .autoProvisioned(true)
                    .createdAt(now)
                    .updatedAt(now)
                    .build();
            BankTransferInstructions instructions = bankTransfers.instructions(invoice, payment);
            payment.setBankTransferContent(instructions.transferContent());
            payment.setBankQrUrl(instructions.qrImageUrl());
            provisioned.add(payment);
        }
        payments.saveAll(provisioned);
        payments.flush();
    }

    private BillingComputation computeBilling(FeePeriod period) {
        TargetSelection periodTarget = storedPeriodTarget(period);
        List<FeePeriodItem> items = periodItems.findByFeePeriodId(period.getId());
        Map<String, TargetSelection> itemScopes = new HashMap<>();
        for (FeePeriodItem item : items) {
            itemScopes.put(item.getId(), storedItemTarget(item));
        }

        List<Invoice> existingInvoices = invoices.findByFeePeriodId(period.getId());
        Map<String, Invoice> existingByStudent = existingInvoices.stream()
                .collect(java.util.stream.Collectors.toMap(Invoice::getStudentId, invoice -> invoice, (first, ignored) -> first));
        Map<String, String> gradeByClass = new HashMap<>();
        List<BillingStudent> billable = new ArrayList<>();
        int targetedStudentCount = 0;

        for (UserDto student : users.list("STUDENT", null, null)) {
            if (!"ACTIVE".equalsIgnoreCase(student.status())) {
                continue;
            }
            if (!belongsToAcademicYear(student, period.getAcademicYearId())) {
                continue;
            }
            String gradeLevel = student.classId() == null ? null
                    : gradeByClass.computeIfAbsent(student.classId(), structure::gradeLevelOf);
            if (!matches(periodTarget, student, gradeLevel)) {
                continue;
            }
            targetedStudentCount++;

            List<FeePeriodItem> applicableItems = items.stream()
                    .filter(item -> matches(itemScopes.get(item.getId()), student, gradeLevel))
                    .toList();
            if (applicableItems.isEmpty()) {
                continue;
            }
            long total = applicableItems.stream().mapToLong(FeePeriodItem::getAmount).sum();
            if (total <= 0) {
                throw ApiException.badRequest("Tổng tiền hóa đơn phải lớn hơn 0");
            }
            billable.add(new BillingStudent(
                    student, applicableItems, total, existingByStudent.get(student.id())));
        }
        return new BillingComputation(targetedStudentCount, items, billable, existingInvoices);
    }

    private boolean belongsToAcademicYear(UserDto student, String academicYearId) {
        if (academicYearId == null || academicYearId.isBlank()) {
            return true;
        }
        if (student.classId() == null || student.classId().isBlank()) {
            return false;
        }
        return academicYearId.equals(structure.getClass(student.classId()).getAcademicYearId());
    }

    private boolean matches(TargetSelection target, UserDto student, String gradeLevel) {
        return switch (target.type()) {
            case "ALL" -> true;
            case "GRADE" -> gradeLevel != null && target.ids().contains(gradeLevel.toUpperCase(Locale.ROOT));
            case "CLASS" -> student.classId() != null && target.ids().contains(student.classId());
            case "STUDENT" -> target.ids().contains(student.id());
            default -> false;
        };
    }

    private String initialInvoiceStatus(LocalDate dueDate) {
        return dueDate != null && dueDate.isBefore(LocalDate.now()) ? "OVERDUE" : "PENDING";
    }

    private record BillingStudent(UserDto student, List<FeePeriodItem> items,
                                  long totalAmount, Invoice existingInvoice) {}

    private record BillingComputation(int targetedStudentCount, List<FeePeriodItem> items,
                                      List<BillingStudent> students, List<Invoice> existingInvoices) {}

    @Transactional
    public List<Invoice> listInvoices(String studentId, String parentId, String status) {
        List<Invoice> base;
        if (studentId != null) {
            base = invoices.findByStudentId(studentId);
        } else if (parentId != null) {
            base = invoices.findByParentId(parentId);
        } else {
            base = invoices.findAll();
        }
        List<Invoice> changed = new ArrayList<>();
        for (Invoice invoice : base) {
            if (invoice.getDueDate() != null
                    && invoice.getDueDate().isBefore(LocalDate.now())
                    && Set.of("PENDING", "PARTIAL").contains(invoice.getStatus())) {
                invoice.setStatus("OVERDUE");
                changed.add(invoice);
            }
        }
        if (!changed.isEmpty()) {
            invoices.saveAll(changed);
        }
        Map<String, FeePeriod> periodById = periods.findAll().stream()
                .collect(java.util.stream.Collectors.toMap(
                        FeePeriod::getId, java.util.function.Function.identity(), (left, right) -> left));
        Map<String, com.sse.app.academic.structure.Semester> semesterById = structure.listSemesters(null).stream()
                .collect(java.util.stream.Collectors.toMap(
                        com.sse.app.academic.structure.Semester::getId,
                        java.util.function.Function.identity(), (left, right) -> left));
        Map<String, com.sse.app.academic.structure.AcademicYear> yearById = structure.listYears().stream()
                .collect(java.util.stream.Collectors.toMap(
                        com.sse.app.academic.structure.AcademicYear::getId,
                        java.util.function.Function.identity(), (left, right) -> left));
        return base.stream()
                .filter(invoice -> status == null || status.equalsIgnoreCase(invoice.getStatus()))
                .sorted(Comparator.comparingInt((Invoice invoice) -> invoiceStatusOrder(invoice.getStatus()))
                        .thenComparing(Invoice::getDueDate, Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(Invoice::getIssuedAt, Comparator.nullsLast(Comparator.reverseOrder())))
                .map(invoice -> hydrateInvoiceScope(invoice, periodById, semesterById, yearById))
                .toList();
    }

    private Invoice hydrateInvoiceScope(
            Invoice invoice,
            Map<String, FeePeriod> periodById,
            Map<String, com.sse.app.academic.structure.Semester> semesterById,
            Map<String, com.sse.app.academic.structure.AcademicYear> yearById) {
        FeePeriod period = periodById.get(invoice.getFeePeriodId());
        if (period == null) return invoice;
        invoice.setFeePeriodCode(period.getCode());
        invoice.setFeePeriodName(period.getName());
        invoice.setFeeType(period.getFeeType());
        invoice.setAcademicYearId(period.getAcademicYearId());
        invoice.setSemesterId(period.getSemesterId());
        var semester = semesterById.get(period.getSemesterId());
        if (semester != null) invoice.setSemesterName(semester.getName());
        var year = yearById.get(period.getAcademicYearId());
        if (year != null) {
            invoice.setAcademicYearName(year.getName() == null || year.getName().isBlank()
                    ? year.getCode() : year.getName());
        }
        return invoice;
    }

    @Transactional
    public Invoice remindOverdue(String invoiceId) {
        Invoice invoice = getInvoiceForUpdate(invoiceId);
        if (invoice.getDueDate() != null
                && invoice.getDueDate().isBefore(LocalDate.now())
                && Set.of("PENDING", "PARTIAL").contains(invoice.getStatus())) {
            invoice.setStatus("OVERDUE");
            invoices.save(invoice);
        }
        if (!"OVERDUE".equals(invoice.getStatus())) {
            throw ApiException.badRequest("Chỉ có thể gửi nhắc nhở cho hóa đơn đã quá hạn");
        }
        long remaining = Math.max(0, invoice.getTotalAmount() - invoice.getPaidAmount());
        if (remaining == 0) {
            throw ApiException.badRequest("Hóa đơn đã được thanh toán đủ");
        }
        events.publish("finance.invoice.reminder", invoice.getStudentId(), "invoice", invoice.getId(),
                Map.of(
                        "studentId", invoice.getStudentId(),
                        "message", String.format("Hóa đơn %s đã quá hạn. Số tiền còn phải thanh toán: %,d VND.",
                                invoice.getCode(), remaining)
                ));
        invoice.setLastReminderAt(Instant.now());
        invoice.setReminderCount(invoice.getReminderCount() == null
                ? 1 : invoice.getReminderCount() + 1);
        invoices.save(invoice);
        return invoice;
    }

    private int invoiceStatusOrder(String status) {
        return switch (status == null ? "" : status.toUpperCase(Locale.ROOT)) {
            case "PENDING" -> 0;
            case "PARTIAL" -> 1;
            case "OVERDUE" -> 2;
            case "PAID" -> 3;
            case "CANCELLED", "VOID" -> 4;
            default -> 5;
        };
    }

    public Invoice getInvoice(String id) {
        return invoices.findById(id).orElseThrow(() -> ApiException.notFound("Hóa đơn"));
    }

    private Invoice getInvoiceForUpdate(String id) {
        return invoices.findByIdForUpdate(id).orElseThrow(() -> ApiException.notFound("Hóa đơn"));
    }

    public Map<String, Object> invoiceDetail(String id) {
        Invoice invoice = getInvoice(id);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("invoice", invoice);
        result.put("items", invoiceItems.findByInvoiceId(id));
        result.put("payments", payments.findByInvoiceId(id));
        return result;
    }

    public record InvoiceSummary(String studentId, String parentId, String status,
                                 long totalAmount, long paidAmount) {}

    public List<InvoiceSummary> dashboardInvoices(Set<String> studentIds) {
        return invoices.findAll().stream()
                .filter(invoice -> studentIds == null || studentIds.contains(invoice.getStudentId()))
                .filter(invoice -> !"CANCELLED".equals(invoice.getStatus()) && !"VOID".equals(invoice.getStatus()))
                .map(invoice -> new InvoiceSummary(
                        invoice.getStudentId(),
                        invoice.getParentId(),
                        invoice.getStatus(),
                        invoice.getTotalAmount(),
                        invoice.getPaidAmount()
                ))
                .toList();
    }

    @Transactional
    public void seedDefaultPeriodAndInvoices() {
        if (!periods.findAll().isEmpty()) {
            return;
        }
        FeePeriod period = periods.save(FeePeriod.builder()
                .id("fp-hk1")
                .code("HK1-2025")
                .name("Học phí HK1 2025-2026")
                .status("OPEN")
                .targetType("ALL")
                .dueDate(LocalDate.parse("2026-01-15"))
                .createdAt(Instant.now())
                .build());
        periodItems.save(FeePeriodItem.builder()
                .id("fpi-1")
                .feePeriodId(period.getId())
                .name("Học phí")
                .amount(1_500_000)
                .targetType("ALL")
                .build());
        periodItems.save(FeePeriodItem.builder()
                .id("fpi-2")
                .feePeriodId(period.getId())
                .name("Bảo hiểm y tế")
                .amount(300_000)
                .targetType("ALL")
                .build());
        generateInvoices(period.getId());
    }

    public Map<String, Object> revenueReport() {
        List<Invoice> activeInvoices = invoices.findAll().stream()
                .filter(invoice -> !"CANCELLED".equals(invoice.getStatus()) && !"VOID".equals(invoice.getStatus()))
                .toList();
        long total = activeInvoices.stream().mapToLong(Invoice::getTotalAmount).sum();
        long paid = activeInvoices.stream().mapToLong(Invoice::getPaidAmount).sum();
        long paidCount = activeInvoices.stream().filter(invoice -> "PAID".equals(invoice.getStatus())).count();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("invoiceCount", activeInvoices.size());
        result.put("paidCount", paidCount);
        result.put("totalAmount", total);
        result.put("paidAmount", paid);
        result.put("outstanding", Math.max(0, total - paid));
        return result;
    }

    private TargetSelection resolveTarget(String requestedType, List<String> requestedIds, String legacyGrades) {
        String type = normalizeTargetType(requestedType);
        List<String> sourceIds = requestedIds == null ? List.of() : requestedIds;
        if (type == null) {
            if (legacyGrades != null && !legacyGrades.isBlank()) {
                type = "GRADE";
                sourceIds = Arrays.asList(legacyGrades.split(","));
            } else if (!sourceIds.isEmpty()) {
                throw ApiException.badRequest("Phải chọn loại phạm vi khi có danh sách đối tượng");
            } else {
                type = "ALL";
            }
        } else if (sourceIds.isEmpty() && "GRADE".equals(type)
                && legacyGrades != null && !legacyGrades.isBlank()) {
            sourceIds = Arrays.asList(legacyGrades.split(","));
        }

        LinkedHashSet<String> normalizedIds = new LinkedHashSet<>();
        for (String id : sourceIds) {
            String normalized = blankToNull(id);
            if (normalized != null) {
                normalizedIds.add("GRADE".equals(type) ? normalizeGradeId(normalized) : normalized);
            }
        }
        if ("ALL".equals(type) && !normalizedIds.isEmpty()) {
            throw ApiException.badRequest("Phạm vi ALL không nhận danh sách đối tượng");
        }
        if (!"ALL".equals(type) && normalizedIds.isEmpty()) {
            throw ApiException.badRequest("Phạm vi " + type + " phải có ít nhất một đối tượng");
        }
        return new TargetSelection(type, List.copyOf(normalizedIds));
    }

    private String normalizeTargetType(String value) {
        String normalized = blankToNullUpper(value);
        if (normalized == null) {
            return null;
        }
        normalized = switch (normalized) {
            case "GRADES" -> "GRADE";
            case "CLASSES" -> "CLASS";
            case "STUDENTS" -> "STUDENT";
            default -> normalized;
        };
        if (!TARGET_TYPES.contains(normalized)) {
            throw ApiException.badRequest("Phạm vi áp dụng không hợp lệ");
        }
        return normalized;
    }

    private String normalizeGradeId(String value) {
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        if (normalized.matches("10|11|12")) {
            normalized = "K" + normalized;
        }
        if (!Set.of("K10", "K11", "K12").contains(normalized)) {
            throw ApiException.badRequest("Khối chỉ được là K10, K11 hoặc K12");
        }
        return normalized;
    }

    private void validateTargets(TargetSelection target, String academicYearId) {
        if (target.ids().size() > 500) {
            throw ApiException.badRequest("Mỗi phạm vi chỉ được tối đa 500 đối tượng");
        }
        if ("GRADE".equals(target.type())) {
            target.ids().forEach(this::normalizeGradeId);
            return;
        }
        if ("CLASS".equals(target.type())) {
            for (String classId : target.ids()) {
                var schoolClass = structure.getClass(classId);
                if (academicYearId != null && !academicYearId.isBlank()
                        && !academicYearId.equals(schoolClass.getAcademicYearId())) {
                    throw ApiException.badRequest("Lớp " + schoolClass.getCode() + " không thuộc năm học của đợt thu");
                }
            }
            return;
        }
        if ("STUDENT".equals(target.type())) {
            for (String studentId : target.ids()) {
                UserDto student = users.dtoById(studentId);
                if (!"STUDENT".equals(student.role())) {
                    throw ApiException.badRequest("Đối tượng " + studentId + " không phải học sinh");
                }
                if (!belongsToAcademicYear(student, academicYearId)) {
                    throw ApiException.badRequest("Học sinh " + student.fullName() + " không thuộc năm học của đợt thu");
                }
            }
        }
    }

    private void validateItemTargetWithinPeriod(FeePeriod period, TargetSelection itemTarget) {
        if ("ALL".equals(itemTarget.type())) {
            return;
        }
        TargetSelection periodTarget = storedPeriodTarget(period);
        for (String studentId : itemTarget.ids()) {
            UserDto student = users.dtoById(studentId);
            String gradeLevel = student.classId() == null ? null : structure.gradeLevelOf(student.classId());
            if (!matches(periodTarget, student, gradeLevel)) {
                throw ApiException.badRequest("Học sinh " + student.fullName()
                        + " không thuộc phạm vi của đợt thu");
            }
        }
    }

    private void savePeriodTargets(String periodId, TargetSelection target) {
        if ("ALL".equals(target.type())) {
            return;
        }
        periodTargets.saveAll(target.ids().stream()
                .map(targetId -> FeePeriodTarget.builder()
                        .id(Ids.gen("fpt"))
                        .feePeriodId(periodId)
                        .targetType(target.type())
                        .targetId(targetId)
                        .build())
                .toList());
    }

    private void saveItemTargets(String itemId, TargetSelection target) {
        if ("ALL".equals(target.type())) {
            return;
        }
        itemTargets.saveAll(target.ids().stream()
                .map(targetId -> FeePeriodItemTarget.builder()
                        .id(Ids.gen("fpit"))
                        .feePeriodItemId(itemId)
                        .targetType(target.type())
                        .targetId(targetId)
                        .build())
                .toList());
    }

    private FeePeriod hydratePeriodTargets(FeePeriod period) {
        TargetSelection target = storedPeriodTarget(period);
        period.setTargetType(target.type());
        period.setTargetIds(new ArrayList<>(target.ids()));
        if ("GRADE".equals(target.type())) {
            period.setApplyToGrades(String.join(",", target.ids()));
        }
        return period;
    }

    private FeePeriodItem hydrateItemTargets(FeePeriodItem item) {
        TargetSelection target = storedItemTarget(item);
        item.setTargetType(target.type());
        item.setTargetIds(new ArrayList<>(target.ids()));
        if ("GRADE".equals(target.type()) && target.ids().size() == 1) {
            item.setGradeLevel(target.ids().get(0));
        }
        return item;
    }

    private TargetSelection storedPeriodTarget(FeePeriod period) {
        String type = (period.getTargetType() == null || period.getTargetType().isBlank())
                && period.getApplyToGrades() != null && !period.getApplyToGrades().isBlank()
                ? "GRADE" : effectiveTargetType(period.getTargetType());
        List<String> ids = periodTargets.findByFeePeriodId(period.getId()).stream()
                .map(FeePeriodTarget::getTargetId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (ids.isEmpty() && "GRADE".equals(type) && period.getApplyToGrades() != null) {
            ids = Arrays.stream(period.getApplyToGrades().split(","))
                    .filter(value -> !value.isBlank())
                    .map(this::normalizeGradeId)
                    .distinct()
                    .toList();
        }
        return new TargetSelection(type, ids);
    }

    private TargetSelection storedItemTarget(FeePeriodItem item) {
        String type = (item.getTargetType() == null || item.getTargetType().isBlank())
                && item.getGradeLevel() != null && !item.getGradeLevel().isBlank()
                ? "GRADE" : effectiveTargetType(item.getTargetType());
        List<String> ids = itemTargets.findByFeePeriodItemId(item.getId()).stream()
                .map(FeePeriodItemTarget::getTargetId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (ids.isEmpty() && "GRADE".equals(type) && item.getGradeLevel() != null) {
            ids = List.of(normalizeGradeId(item.getGradeLevel()));
        }
        return new TargetSelection(type, ids);
    }

    private String effectiveTargetType(String value) {
        String normalized = normalizeTargetType(value);
        return normalized == null ? "ALL" : normalized;
    }

    private String blankToDefault(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value;
    }

    private record TargetSelection(String type, List<String> ids) {}

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String normalizeFeeType(String value) {
        String normalized = blankToNull(value);
        normalized = normalized == null ? "OTHER" : normalized.toUpperCase(Locale.ROOT);
        if (!FEE_TYPES.contains(normalized)) {
            throw ApiException.badRequest("Loại khoản thu không hợp lệ");
        }
        return normalized;
    }

    private String blankToNullUpper(String value) {
        String normalized = blankToNull(value);
        return normalized == null ? null : normalized.toUpperCase(Locale.ROOT);
    }
}
