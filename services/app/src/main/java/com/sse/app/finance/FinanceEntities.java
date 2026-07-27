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
    @Column(nullable = false)
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
    /** Lớp/khối tại thời điểm phát hành, không thay đổi khi học sinh chuyển lớp. */
    private String classId;
    private String classCode;
    private String gradeLevel;
    private String parentId;
    private String feePeriodId;
    private long totalAmount;
    private long paidAmount;
    /** PENDING | PARTIAL | PAID */
    private String status;
    private Instant issuedAt;
    private LocalDate dueDate;
    @Version
    private long version;
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
    /** MOMO | CASH */
    private String method;
    /** PENDING | SUCCESS | FAILED */
    private String status;
    private String txnRef;
    private Instant createdAt;
    private Instant paidAt;
}

/** Trạng thái trao đổi độc lập với cổng thanh toán, phục vụ callback và đối soát. */
@Entity
@Table(name = "payment_gateway_transactions", indexes =
        @Index(name = "idx_gateway_txn_status", columnList = "status,createdAt"))
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
class PaymentGatewayTransaction {
    @Id
    private String id;
    @Column(nullable = false, unique = true)
    private String paymentId;
    @Column(nullable = false, unique = true)
    private String txnRef;
    @Column(nullable = false, length = 32)
    private String gateway;
    @Column(nullable = false, length = 32)
    private String status;
    @Column(length = 4000)
    private String requestPayload;
    @Column(length = 4000)
    private String callbackPayload;
    private Boolean signatureValid;
    @Column(nullable = false)
    private Instant createdAt;
    @Column(nullable = false)
    private Instant updatedAt;
}
