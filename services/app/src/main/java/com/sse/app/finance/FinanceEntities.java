package com.sse.app.finance;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.time.LocalDate;

/** A7/D4: Các thực thể tài chính nội bộ (tiền tính bằng VND — kiểu long). */
public final class FinanceEntities {
    private FinanceEntities() {}
}

/** Đợt thu học phí. */
@Entity
@Table(name = "fee_periods")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
class FeePeriod {
    @Id
    private String id;
    private String code;
    private String name;
    /** DRAFT | OPEN | CLOSED */
    private String status;
    private String academicYearId;
    /** CSV khối áp dụng (vd "K10,K11"); null = mọi khối. */
    private String applyToGrades;
    private LocalDate dueDate;
    private Instant createdAt;
}

/** Định mức của đợt thu (theo khối hoặc chung). */
@Entity
@Table(name = "fee_period_items")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
class FeePeriodItem {
    @Id
    private String id;
    private String feePeriodId;
    private String name;
    private long amount;
    /** null = áp dụng mọi khối. */
    private String gradeLevel;
}

/** Hóa đơn của 1 học sinh trong 1 đợt thu. */
@Entity
@Table(name = "invoices", indexes = {
        @Index(name = "idx_inv_student", columnList = "studentId"),
        @Index(name = "idx_inv_parent", columnList = "parentId")
})
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
class Invoice {
    @Id
    private String id;
    private String code;
    private String studentId;
    private String studentName;
    private String parentId;
    private String feePeriodId;
    private long totalAmount;
    private long paidAmount;
    /** PENDING | PARTIAL | PAID */
    private String status;
    private Instant issuedAt;
    private LocalDate dueDate;
}

@Entity
@Table(name = "invoice_items")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
class InvoiceItem {
    @Id
    private String id;
    private String invoiceId;
    private String name;
    private long amount;
}

/** Giao dịch thanh toán (S8 — bản sandbox tự succeed, không gọi cổng thật). */
@Entity
@Table(name = "payments", indexes = @Index(name = "idx_pay_invoice", columnList = "invoiceId"))
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
class Payment {
    @Id
    private String id;
    private String invoiceId;
    private long amount;
    /** VNPAY | MOMO | CASH */
    private String method;
    /** PENDING | SUCCESS | FAILED */
    private String status;
    private String txnRef;
    private Instant createdAt;
    private Instant paidAt;
}
