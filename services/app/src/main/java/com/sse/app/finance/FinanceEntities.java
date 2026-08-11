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
    /** SCHOOL | GRADE | CLASS | STUDENTS. */
    private String scopeType;
    private String scopeGradeLevel;
    private String scopeClassId;
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
    /** Tên phụ huynh được bổ sung khi trả API, không lưu lặp trong hóa đơn. */
    @Transient
    private String parentName;
    private String feePeriodId;
    private long totalAmount;
    private long paidAmount;
    /** UNPAID | PARTIAL | PAID | OVERDUE | PARTIALLY_REFUNDED | REFUNDED | CANCELLED */
    private String status;
    private long refundedAmount;
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

/** Giao dịch thanh toán học phí. */
@Entity
@Table(name = "payments", indexes = @Index(name = "idx_pay_invoice", columnList = "invoiceId"))
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
class Payment {
    @Id
    private String id;
    private String invoiceId;
    private long amount;
    /** VIETQR | CASH */
    private String method;
    /** PENDING | SUCCESS | FAILED */
    private String status;
    private String txnRef;
    /** Mã biên nhận hiển thị cho phụ huynh và kế toán. */
    private String receiptCode;
    private String payerName;
    private String note;
    private String recordedBy;
    private Instant createdAt;
    private Instant paidAt;
}

@Entity
@Table(name = "fee_period_recipients", uniqueConstraints =
        @UniqueConstraint(name = "uk_fee_period_recipient", columnNames = {"fee_period_id", "student_id"}))
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
class FeePeriodRecipient {
    @Id
    private String id;
    @Column(name = "fee_period_id")
    private String feePeriodId;
    @Column(name = "student_id")
    private String studentId;
}

@Entity
@Table(name = "fee_period_adjustments", uniqueConstraints =
        @UniqueConstraint(name = "uk_fee_period_adjustment", columnNames = {"fee_period_id", "student_id"}))
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
class FeePeriodAdjustment {
    @Id
    private String id;
    @Column(name = "fee_period_id")
    private String feePeriodId;
    @Column(name = "student_id")
    private String studentId;
    /** EXCLUDE | DISCOUNT. */
    private String type;
    private long amount;
    private String reason;
}

/** Lịch sử hoàn tiền được Kế toán/Admin xác nhận. */
@Entity
@Table(name = "invoice_refunds", indexes = @Index(name = "idx_refund_invoice", columnList = "invoiceId"))
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
class InvoiceRefund {
    @Id
    private String id;
    private String invoiceId;
    private long amount;
    private String method;
    private String reason;
    private String status;
    private String createdBy;
    private Instant createdAt;
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
