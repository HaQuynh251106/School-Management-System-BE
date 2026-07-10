package com.sse.app.finance;

import com.sse.app.academic.structure.StructureService;
import com.sse.app.common.ApiException;
import com.sse.app.common.Ids;
import com.sse.app.event.DomainEventPublisher;
import com.sse.app.finance.FinanceDtos.*;
import com.sse.app.identity.UserDto;
import com.sse.app.identity.UserService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

/** A7: Tài chính nội bộ — đợt thu, sinh hóa đơn (2.8), thanh toán (2.9, sandbox). */
@Service
public class FinanceService {

    private final FeePeriodRepository periods;
    private final FeePeriodItemRepository periodItems;
    private final InvoiceRepository invoices;
    private final InvoiceItemRepository invoiceItems;
    private final PaymentRepository payments;
    private final StructureService structure;
    private final UserService users;
    private final DomainEventPublisher events;

    public FinanceService(FeePeriodRepository periods, FeePeriodItemRepository periodItems,
                          InvoiceRepository invoices, InvoiceItemRepository invoiceItems,
                          PaymentRepository payments, StructureService structure,
                          UserService users, DomainEventPublisher events) {
        this.periods = periods;
        this.periodItems = periodItems;
        this.invoices = invoices;
        this.invoiceItems = invoiceItems;
        this.payments = payments;
        this.structure = structure;
        this.users = users;
        this.events = events;
    }

    // ---------- Đợt thu ----------
    public List<FeePeriod> listPeriods() { return periods.findAll(); }

    public FeePeriod createPeriod(CreateFeePeriodRequest r) {
        return periods.save(FeePeriod.builder()
                .id(r.id() == null || r.id().isBlank() ? Ids.gen("fp") : r.id())
                .code(r.code()).name(r.name()).status("DRAFT")
                .academicYearId(r.academicYearId()).applyToGrades(r.applyToGrades())
                .dueDate(r.dueDate()).createdAt(Instant.now()).build());
    }

    public List<FeePeriodItem> itemsOf(String periodId) { return periodItems.findByFeePeriodId(periodId); }

    public FeePeriodItem addItem(String periodId, AddFeeItemRequest r) {
        getPeriod(periodId);
        return periodItems.save(FeePeriodItem.builder()
                .id(r.id() == null || r.id().isBlank() ? Ids.gen("fpi") : r.id())
                .feePeriodId(periodId).name(r.name()).amount(r.amount()).gradeLevel(r.gradeLevel()).build());
    }

    public FeePeriod open(String periodId) {
        FeePeriod p = getPeriod(periodId);
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
        List<Invoice> created = new ArrayList<>();

        for (UserDto s : users.list("STUDENT", null, null)) {
            String gl = structure.gradeLevelOf(s.classId());
            if (gradeFilter != null && (gl == null || !gradeFilter.contains(gl))) continue;

            List<FeePeriodItem> applicable = items.stream()
                    .filter(it -> it.getGradeLevel() == null || it.getGradeLevel().equals(gl))
                    .toList();
            if (applicable.isEmpty()) continue;

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
                events.publish("finance.invoice.issued", parentId, "invoice", inv.getId(),
                        Map.of("studentId", s.id(),
                                "parentId", parentId,
                                "message", String.format("%s — %s: %,d₫", s.fullName(), inv.getCode(), total)));
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

    // ---------- Thanh toán (flowchart 2.9, sandbox tự succeed) ----------
    @Transactional
    public Map<String, Object> pay(PayRequest r) {
        Invoice inv = getInvoice(r.invoiceId());
        long remaining = inv.getTotalAmount() - inv.getPaidAmount();
        if (remaining <= 0) throw ApiException.badRequest("Hóa đơn đã thanh toán đủ");

        String method = r.method() == null ? "VNPAY" : r.method().toUpperCase();
        Payment pay = payments.save(Payment.builder()
                .id(Ids.gen("pay")).invoiceId(inv.getId()).amount(remaining).method(method)
                .status("SUCCESS").txnRef("SANDBOX-" + Ids.gen("tx"))
                .createdAt(Instant.now()).paidAt(Instant.now()).build());

        inv.setPaidAmount(inv.getPaidAmount() + remaining);
        inv.setStatus(inv.getPaidAmount() >= inv.getTotalAmount() ? "PAID" : "PARTIAL");
        invoices.save(inv);

        if (inv.getParentId() != null) {
            events.publish("finance.invoice.paid", inv.getParentId(), "invoice", inv.getId(),
                    Map.of("studentId", inv.getStudentId(),
                            "parentId", inv.getParentId(),
                            "paymentId", pay.getId(),
                            "message", String.format("Biên nhận %s: %,d₫ (%s)", inv.getCode(), remaining, method)));
        }
        Map<String, Object> m = new HashMap<>();
        m.put("payment", pay);
        m.put("invoice", inv);
        return m;
    }

    public List<Payment> paymentsOf(String invoiceId) { return payments.findByInvoiceId(invoiceId); }

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
