package com.sse.app.finance;

import com.sse.app.academic.structure.SchoolClass;
import com.sse.app.academic.structure.StructureService;
import com.sse.app.common.ApiException;
import com.sse.app.finance.FinanceReportDtos.FinanceCashFlowRow;
import com.sse.app.finance.FinanceReportDtos.FinanceDebtDetailRow;
import com.sse.app.finance.FinanceReportDtos.FinanceDebtGroupRow;
import com.sse.app.finance.FinanceReportDtos.FinanceMethodRow;
import com.sse.app.finance.FinanceReportDtos.FinanceReportFilter;
import com.sse.app.finance.FinanceReportDtos.FinanceReportResponse;
import com.sse.app.finance.FinanceReportDtos.FinanceReportSummary;
import com.sse.app.identity.User;
import com.sse.app.identity.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class FinanceReportService {
    static final ZoneId SCHOOL_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final Set<String> SUPPORTED_METHODS = Set.of(
            "VNPAY", "MOMO", "CASH", "MB_BANK_TRANSFER");
    private static final Set<String> SUPPORTED_FEE_TYPES = Set.of(
            "TUITION", "MEAL", "TRANSPORT", "ACTIVITY", "OTHER");
    private static final Set<String> SUPPORTED_SETTLEMENT_STATUSES = Set.of(
            "PAID", "UNPAID", "OVERDUE");
    private static final Set<String> EXCLUDED_INVOICE_STATUSES = Set.of("CANCELLED", "VOID");
    private static final int MAX_RANGE_DAYS = 366;

    private final InvoiceRepository invoices;
    private final PaymentRepository payments;
    private final PaymentRefundRepository refunds;
    private final FeePeriodRepository feePeriods;
    private final UserRepository users;
    private final StructureService structure;

    public FinanceReportService(InvoiceRepository invoices,
                                PaymentRepository payments,
                                PaymentRefundRepository refunds,
                                FeePeriodRepository feePeriods,
                                UserRepository users,
                                StructureService structure) {
        this.invoices = invoices;
        this.payments = payments;
        this.refunds = refunds;
        this.feePeriods = feePeriods;
        this.users = users;
        this.structure = structure;
    }

    @Transactional(readOnly = true)
    public FinanceReportResponse report(FinanceReportFilter requestedFilter) {
        FinanceReportFilter filter = normalize(requestedFilter);
        LocalDate today = LocalDate.now(SCHOOL_ZONE);

        Map<String, User> studentById = users.findAll().stream()
                .filter(user -> "STUDENT".equals(user.getRole()))
                .collect(Collectors.toMap(User::getId, Function.identity(), (left, right) -> left));
        Map<String, SchoolClass> classById = structure.listClasses(null, null).stream()
                .collect(Collectors.toMap(SchoolClass::getId, Function.identity(), (left, right) -> left));
        Map<String, FeePeriod> periodById = feePeriods.findAll().stream()
                .collect(Collectors.toMap(FeePeriod::getId, Function.identity(), (left, right) -> left));

        List<InvoiceContext> scopedInvoices = invoices.findAll().stream()
                .filter(invoice -> !EXCLUDED_INVOICE_STATUSES.contains(value(invoice.getStatus())))
                .map(invoice -> context(invoice, studentById, classById, periodById))
                .filter(context -> matchesScope(context, filter, today))
                .toList();
        Set<String> scopedInvoiceIds = scopedInvoices.stream()
                .map(context -> context.invoice().getId())
                .collect(Collectors.toSet());

        Instant from = filter.fromDate().atStartOfDay(SCHOOL_ZONE).toInstant();
        Instant to = filter.toDate().plusDays(1).atStartOfDay(SCHOOL_ZONE).toInstant();
        List<Payment> allPayments = payments.findAll();
        Map<String, Payment> paymentById = allPayments.stream()
                .collect(Collectors.toMap(Payment::getId, Function.identity(), (left, right) -> left));
        List<PaymentRefund> allRefunds = refunds.findAll();
        Set<String> refundedPaymentIds = allRefunds.stream()
                .filter(refund -> "COMPLETED".equals(value(refund.getStatus())))
                .map(PaymentRefund::getPaymentId)
                .collect(Collectors.toSet());
        List<Payment> settledPayments = allPayments.stream()
                .filter(payment -> scopedInvoiceIds.contains(payment.getInvoiceId()))
                .filter(payment -> isCollectedPayment(payment, refundedPaymentIds))
                .filter(payment -> within(payment.getPaidAt(), from, to))
                .filter(payment -> filter.method() == null || filter.method().equals(value(payment.getMethod())))
                .toList();
        List<PaymentRefund> completedRefunds = allRefunds.stream()
                .filter(refund -> "COMPLETED".equals(value(refund.getStatus())))
                .filter(refund -> scopedInvoiceIds.contains(refund.getInvoiceId()))
                .filter(refund -> within(refund.getCompletedAt(), from, to))
                .filter(refund -> {
                    Payment payment = paymentById.get(refund.getPaymentId());
                    return filter.method() == null || payment != null
                            && filter.method().equals(value(payment.getMethod()));
                })
                .toList();

        List<FinanceCashFlowRow> daily = dailyCashFlow(filter, settledPayments, completedRefunds);
        List<FinanceMethodRow> methods = methodCashFlow(filter, settledPayments, completedRefunds, paymentById);
        List<FinanceDebtDetailRow> debtDetails = scopedInvoices.stream()
                .filter(context -> outstanding(context.invoice()) > 0)
                .map(context -> debtDetail(context, today))
                .sorted(debtDetailOrder())
                .toList();

        long totalReceivable = scopedInvoices.stream().mapToLong(context -> nonNegative(context.invoice().getTotalAmount())).sum();
        long currentPaid = scopedInvoices.stream().mapToLong(context -> nonNegative(context.invoice().getPaidAmount())).sum();
        long outstanding = scopedInvoices.stream().mapToLong(context -> outstanding(context.invoice())).sum();
        int paidInvoiceCount = (int) scopedInvoices.stream().filter(context -> outstanding(context.invoice()) == 0).count();
        int overdueInvoiceCount = (int) scopedInvoices.stream().filter(context -> isOverdue(context.invoice(), today)).count();
        long overdueAmount = scopedInvoices.stream()
                .filter(context -> isOverdue(context.invoice(), today))
                .mapToLong(context -> outstanding(context.invoice()))
                .sum();
        long gross = settledPayments.stream().mapToLong(Payment::getAmount).sum();
        long refunded = completedRefunds.stream().mapToLong(PaymentRefund::getAmount).sum();

        FinanceReportSummary summary = new FinanceReportSummary(
                scopedInvoices.size(), paidInvoiceCount, scopedInvoices.size() - paidInvoiceCount,
                overdueInvoiceCount, totalReceivable, currentPaid, outstanding, overdueAmount,
                settledPayments.size(), gross, completedRefunds.size(), refunded, gross - refunded);

        return new FinanceReportResponse(filter, Instant.now(), summary, daily, methods,
                groupDebt(scopedInvoices, "FEE_PERIOD", context -> new GroupIdentity(
                        defaultValue(context.periodId(), "NO_PERIOD"),
                        defaultValue(context.periodCode(), "-"),
                        defaultValue(context.periodName(), "Không xác định đợt thu")), today),
                groupDebt(scopedInvoices, "GRADE", context -> new GroupIdentity(
                        context.gradeLevel(), context.gradeLevel(), gradeName(context.gradeLevel())), today),
                groupDebt(scopedInvoices, "CLASS", context -> new GroupIdentity(
                        context.classId(), context.classCode(), context.className()), today),
                debtDetails);
    }

    private boolean isCollectedPayment(Payment payment, Set<String> refundedPaymentIds) {
        String status = value(payment.getStatus());
        return "SUCCESS".equals(status)
                || "REVERSED".equals(status) && refundedPaymentIds.contains(payment.getId());
    }

    private FinanceReportFilter normalize(FinanceReportFilter requested) {
        LocalDate today = LocalDate.now(SCHOOL_ZONE);
        LocalDate toDate = requested == null || requested.toDate() == null ? today : requested.toDate();
        LocalDate fromDate = requested == null || requested.fromDate() == null
                ? toDate.withDayOfMonth(1) : requested.fromDate();
        if (fromDate.isAfter(toDate)) {
            throw ApiException.badRequest("Ngày bắt đầu không được sau ngày kết thúc");
        }
        if (toDate.isAfter(today)) {
            throw ApiException.badRequest("Ngày kết thúc báo cáo không được ở tương lai");
        }
        if (ChronoUnit.DAYS.between(fromDate, toDate) >= MAX_RANGE_DAYS) {
            throw ApiException.badRequest("Mỗi báo cáo chỉ được chọn tối đa 366 ngày");
        }

        String method = upperOrNull(requested == null ? null : requested.method());
        if (method != null && !SUPPORTED_METHODS.contains(method)) {
            throw ApiException.badRequest("Phương thức thanh toán không hợp lệ");
        }
        String grade = upperOrNull(requested == null ? null : requested.gradeLevel());
        if (grade != null && grade.matches("10|11|12")) grade = "K" + grade;
        String feeType = upperOrNull(requested == null ? null : requested.feeType());
        if (feeType != null && !SUPPORTED_FEE_TYPES.contains(feeType)) {
            throw ApiException.badRequest("Loại khoản thu không hợp lệ");
        }
        String settlementStatus = upperOrNull(requested == null ? null : requested.settlementStatus());
        if (settlementStatus != null && !SUPPORTED_SETTLEMENT_STATUSES.contains(settlementStatus)) {
            throw ApiException.badRequest("Tình trạng đóng phí không hợp lệ");
        }
        return new FinanceReportFilter(fromDate, toDate,
                trimOrNull(requested == null ? null : requested.feePeriodId()),
                grade,
                trimOrNull(requested == null ? null : requested.classId()),
                trimOrNull(requested == null ? null : requested.studentId()),
                method,
                feeType,
                trimOrNull(requested == null ? null : requested.semesterId()),
                settlementStatus);
    }

    private InvoiceContext context(Invoice invoice,
                                   Map<String, User> studentById,
                                   Map<String, SchoolClass> classById,
                                   Map<String, FeePeriod> periodById) {
        User student = studentById.get(invoice.getStudentId());
        String classId = student == null ? null : trimOrNull(student.getClassId());
        SchoolClass schoolClass = classId == null ? null : classById.get(classId);
        FeePeriod period = invoice.getFeePeriodId() == null ? null : periodById.get(invoice.getFeePeriodId());
        String classCode = schoolClass == null
                ? defaultValue(student == null ? null : student.getClassName(), "Chưa phân lớp")
                : defaultValue(schoolClass.getCode(), schoolClass.getName());
        String gradeLevel = schoolClass == null
                ? gradeFromClassCode(classCode)
                : defaultValue(schoolClass.getGradeLevel(), gradeFromClassCode(classCode));
        return new InvoiceContext(invoice,
                period == null ? invoice.getFeePeriodId() : period.getId(),
                period == null ? null : period.getCode(),
                period == null ? null : period.getName(),
                period == null ? "OTHER" : defaultValue(period.getFeeType(), "OTHER"),
                period == null ? null : period.getSemesterId(),
                student == null ? null : student.getStudentCode(),
                defaultValue(invoice.getStudentName(), student == null ? null : student.getFullName()),
                defaultValue(classId, "UNASSIGNED"),
                defaultValue(classCode, "Chưa phân lớp"),
                schoolClass == null ? defaultValue(classCode, "Chưa phân lớp")
                        : defaultValue(schoolClass.getName(), classCode),
                defaultValue(gradeLevel, "UNASSIGNED"));
    }

    private boolean matchesScope(InvoiceContext context, FinanceReportFilter filter, LocalDate today) {
        return (filter.feePeriodId() == null || filter.feePeriodId().equals(context.periodId()))
                && (filter.feeType() == null || filter.feeType().equals(context.feeType()))
                && (filter.semesterId() == null || filter.semesterId().equals(context.semesterId()))
                && (filter.gradeLevel() == null || filter.gradeLevel().equals(context.gradeLevel()))
                && (filter.classId() == null || filter.classId().equals(context.classId()))
                && (filter.studentId() == null || filter.studentId().equals(context.invoice().getStudentId()))
                && matchesSettlement(context.invoice(), filter.settlementStatus(), today);
    }

    private boolean matchesSettlement(Invoice invoice, String settlementStatus, LocalDate today) {
        if (settlementStatus == null) return true;
        return switch (settlementStatus) {
            case "PAID" -> outstanding(invoice) == 0;
            case "UNPAID" -> outstanding(invoice) > 0;
            case "OVERDUE" -> isOverdue(invoice, today);
            default -> false;
        };
    }

    private List<FinanceCashFlowRow> dailyCashFlow(FinanceReportFilter filter,
                                                    List<Payment> settledPayments,
                                                    List<PaymentRefund> completedRefunds) {
        Map<LocalDate, FlowTotals> totals = new LinkedHashMap<>();
        LocalDate cursor = filter.fromDate();
        while (!cursor.isAfter(filter.toDate())) {
            totals.put(cursor, new FlowTotals());
            cursor = cursor.plusDays(1);
        }
        for (Payment payment : settledPayments) {
            FlowTotals total = totals.get(payment.getPaidAt().atZone(SCHOOL_ZONE).toLocalDate());
            if (total != null) total.addPayment(payment.getAmount());
        }
        for (PaymentRefund refund : completedRefunds) {
            FlowTotals total = totals.get(refund.getCompletedAt().atZone(SCHOOL_ZONE).toLocalDate());
            if (total != null) total.addRefund(refund.getAmount());
        }
        return totals.entrySet().stream()
                .map(entry -> entry.getValue().daily(entry.getKey()))
                .toList();
    }

    private List<FinanceMethodRow> methodCashFlow(FinanceReportFilter filter,
                                                   List<Payment> settledPayments,
                                                   List<PaymentRefund> completedRefunds,
                                                   Map<String, Payment> paymentById) {
        Map<String, FlowTotals> totals = new LinkedHashMap<>();
        if (filter.method() != null) totals.put(filter.method(), new FlowTotals());
        for (Payment payment : settledPayments) {
            totals.computeIfAbsent(defaultValue(payment.getMethod(), "UNKNOWN"), ignored -> new FlowTotals())
                    .addPayment(payment.getAmount());
        }
        for (PaymentRefund refund : completedRefunds) {
            Payment payment = paymentById.get(refund.getPaymentId());
            String method = payment == null ? "UNKNOWN" : defaultValue(payment.getMethod(), "UNKNOWN");
            totals.computeIfAbsent(method, ignored -> new FlowTotals()).addRefund(refund.getAmount());
        }
        return totals.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> entry.getValue().method(entry.getKey()))
                .toList();
    }

    private List<FinanceDebtGroupRow> groupDebt(List<InvoiceContext> contexts,
                                                String dimension,
                                                Function<InvoiceContext, GroupIdentity> identity,
                                                LocalDate today) {
        Map<GroupIdentity, DebtTotals> totals = new HashMap<>();
        for (InvoiceContext context : contexts) {
            totals.computeIfAbsent(identity.apply(context), ignored -> new DebtTotals())
                    .add(context.invoice(), today);
        }
        return totals.entrySet().stream()
                .map(entry -> entry.getValue().row(dimension, entry.getKey()))
                .sorted(Comparator.comparing(row -> naturalSortKey(row.code())))
                .toList();
    }

    private FinanceDebtDetailRow debtDetail(InvoiceContext context, LocalDate today) {
        Invoice invoice = context.invoice();
        boolean overdue = isOverdue(invoice, today);
        return new FinanceDebtDetailRow(invoice.getId(), invoice.getCode(), context.periodId(),
                context.periodCode(), context.periodName(), invoice.getStudentId(), context.studentCode(),
                context.studentName(), context.gradeLevel(), context.classId(), context.classCode(),
                nonNegative(invoice.getTotalAmount()), nonNegative(invoice.getPaidAmount()), outstanding(invoice),
                invoice.getDueDate(), overdue, overdue ? "OVERDUE" : invoice.getStatus());
    }

    private Comparator<FinanceDebtDetailRow> debtDetailOrder() {
        return Comparator.comparing(FinanceDebtDetailRow::overdue).reversed()
                .thenComparing(FinanceDebtDetailRow::dueDate, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(row -> naturalSortKey(row.classCode()))
                .thenComparing(row -> defaultValue(row.studentName(), row.studentId()), String.CASE_INSENSITIVE_ORDER);
    }

    private boolean isOverdue(Invoice invoice, LocalDate today) {
        return outstanding(invoice) > 0 && ("OVERDUE".equals(value(invoice.getStatus()))
                || invoice.getDueDate() != null && invoice.getDueDate().isBefore(today));
    }

    private long outstanding(Invoice invoice) {
        return Math.max(0, nonNegative(invoice.getTotalAmount()) - nonNegative(invoice.getPaidAmount()));
    }

    private long nonNegative(long amount) {
        return Math.max(0, amount);
    }

    private boolean within(Instant value, Instant from, Instant to) {
        return value != null && !value.isBefore(from) && value.isBefore(to);
    }

    private String gradeFromClassCode(String classCode) {
        if (classCode == null) return null;
        String normalized = classCode.trim().toUpperCase(Locale.ROOT);
        if (normalized.startsWith("10")) return "K10";
        if (normalized.startsWith("11")) return "K11";
        if (normalized.startsWith("12")) return "K12";
        return null;
    }

    private String gradeName(String gradeLevel) {
        if (gradeLevel == null || "UNASSIGNED".equals(gradeLevel)) return "Chưa phân khối";
        return gradeLevel.startsWith("K") ? "Khối " + gradeLevel.substring(1) : gradeLevel;
    }

    private String naturalSortKey(String value) {
        if (value == null) return "ZZZ";
        String normalized = value.toUpperCase(Locale.ROOT);
        StringBuilder key = new StringBuilder();
        StringBuilder digits = new StringBuilder();
        for (char character : normalized.toCharArray()) {
            if (Character.isDigit(character)) {
                digits.append(character);
            } else {
                if (!digits.isEmpty()) {
                    appendNaturalNumber(key, digits);
                    digits.setLength(0);
                }
                key.append(character);
            }
        }
        if (!digits.isEmpty()) appendNaturalNumber(key, digits);
        return key.toString();
    }

    private void appendNaturalNumber(StringBuilder key, StringBuilder digits) {
        int firstSignificant = 0;
        while (firstSignificant < digits.length() - 1 && digits.charAt(firstSignificant) == '0') {
            firstSignificant++;
        }
        String normalized = digits.substring(firstSignificant);
        key.append("#N")
                .append(String.format(Locale.ROOT, "%010d", normalized.length()))
                .append(':')
                .append(normalized)
                .append('#');
    }

    private String trimOrNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String upperOrNull(String value) {
        String normalized = trimOrNull(value);
        return normalized == null ? null : normalized.toUpperCase(Locale.ROOT);
    }

    private String defaultValue(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private String value(String value) {
        return value == null ? "" : value;
    }

    private record InvoiceContext(
            Invoice invoice,
            String periodId,
            String periodCode,
            String periodName,
            String feeType,
            String semesterId,
            String studentCode,
            String studentName,
            String classId,
            String classCode,
            String className,
            String gradeLevel) {}

    private record GroupIdentity(String key, String code, String name) {}

    private static final class FlowTotals {
        private int paymentCount;
        private long gross;
        private int refundCount;
        private long refunded;

        private void addPayment(long amount) {
            paymentCount++;
            gross += amount;
        }

        private void addRefund(long amount) {
            refundCount++;
            refunded += amount;
        }

        private FinanceCashFlowRow daily(LocalDate date) {
            return new FinanceCashFlowRow(date, paymentCount, gross, refundCount, refunded, gross - refunded);
        }

        private FinanceMethodRow method(String method) {
            return new FinanceMethodRow(method, paymentCount, gross, refundCount, refunded, gross - refunded);
        }
    }

    private final class DebtTotals {
        private int invoiceCount;
        private final Set<String> debtors = new LinkedHashSet<>();
        private int overdueCount;
        private long totalReceivable;
        private long currentPaid;
        private long outstanding;
        private long overdue;

        private void add(Invoice invoice, LocalDate today) {
            invoiceCount++;
            totalReceivable += nonNegative(invoice.getTotalAmount());
            currentPaid += nonNegative(invoice.getPaidAmount());
            long balance = outstanding(invoice);
            outstanding += balance;
            if (balance > 0) debtors.add(invoice.getStudentId());
            if (isOverdue(invoice, today)) {
                overdueCount++;
                overdue += balance;
            }
        }

        private FinanceDebtGroupRow row(String dimension, GroupIdentity identity) {
            return new FinanceDebtGroupRow(dimension, identity.key(), identity.code(), identity.name(),
                    invoiceCount, debtors.size(), overdueCount, totalReceivable, currentPaid, outstanding, overdue);
        }
    }
}
