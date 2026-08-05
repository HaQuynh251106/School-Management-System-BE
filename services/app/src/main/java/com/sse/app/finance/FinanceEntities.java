package com.sse.app.finance;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/** A7/D4: Finance entities. Money is stored as VND in long values. */
public final class FinanceEntities {
    private FinanceEntities() {}
}

@Entity
@Table(name = "fee_periods")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
class FeePeriod {
    @Id
    private String id;
    @Column(nullable = false)
    private String code;
    @Column(nullable = false)
    private String name;
    /** DRAFT | OPEN | PUBLISHED | CLOSED | CANCELLED */
    private String status;
    private String academicYearId;
    /** TUITION | MEAL | TRANSPORT | ACTIVITY | OTHER */
    private String feeType;
    /** Optional semester scope for filtering and reporting. */
    private String semesterId;
    /** Legacy P0 field kept for backward-compatible clients. */
    private String applyToGrades;
    /** ALL | GRADE | CLASS | STUDENT */
    private String targetType;
    @Transient
    @Builder.Default
    private List<String> targetIds = new ArrayList<>();
    private LocalDate dueDate;
    private Instant createdAt;
    private Instant publishedAt;
    private Instant closedAt;
    private Instant cancelledAt;
    @Column(length = 500)
    private String cancellationReason;
}

@Entity
@Table(name = "fee_period_items")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
class FeePeriodItem {
    @Id
    private String id;
    private String feePeriodId;
    private String name;
    private long amount;
    /** Legacy P0 field kept for backward-compatible clients. */
    private String gradeLevel;
    /** ALL | GRADE | CLASS | STUDENT */
    private String targetType;
    @Transient
    @Builder.Default
    private List<String> targetIds = new ArrayList<>();
}

@Entity
@Table(name = "fee_period_targets", uniqueConstraints =
        @UniqueConstraint(name = "uk_fee_period_target", columnNames = {"feePeriodId", "targetType", "targetId"}))
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
class FeePeriodTarget {
    @Id
    private String id;
    private String feePeriodId;
    private String targetType;
    private String targetId;
}

@Entity
@Table(name = "fee_period_item_targets", uniqueConstraints =
        @UniqueConstraint(name = "uk_fee_period_item_target", columnNames = {"feePeriodItemId", "targetType", "targetId"}))
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
class FeePeriodItemTarget {
    @Id
    private String id;
    private String feePeriodItemId;
    private String targetType;
    private String targetId;
}

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
    /** PENDING | OVERDUE | PARTIAL | PAID | CANCELLED | VOID */
    private String status;
    private Instant issuedAt;
    private LocalDate dueDate;
    private Instant lastReminderAt;
    @Builder.Default
    private Integer reminderCount = 0;
    @Transient
    private String feePeriodCode;
    @Transient
    private String feePeriodName;
    @Transient
    private String feeType;
    @Transient
    private String academicYearId;
    @Transient
    private String academicYearName;
    @Transient
    private String semesterId;
    @Transient
    private String semesterName;
}

@Entity
@Table(name = "invoice_items")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
class InvoiceItem {
    @Id
    private String id;
    private String invoiceId;
    private String feePeriodItemId;
    private String name;
    private long amount;
    private String sourceTargetType;
}

@Entity
@Table(name = "payments", indexes = @Index(name = "idx_pay_invoice", columnList = "invoiceId"))
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
class Payment {
    @Id
    private String id;
    private String invoiceId;
    private long amount;
    /** VNPAY | MOMO | CASH | MB_BANK_TRANSFER */
    private String method;
    /** PENDING | SUCCESS | FAILED | REVERSED | EXPIRED */
    private String status;
    private String txnRef;
    @Column(length = 500)
    private String note;
    // The SQL patch backfills legacy rows before enforcing NOT NULL.
    @Column
    @Builder.Default
    private boolean autoProvisioned = false;
    @Column(length = 255)
    private String bankTransferContent;
    @Column(length = 1000)
    private String bankQrUrl;
    private Instant createdAt;
    private Instant updatedAt;
    private Instant paidAt;
}

@Entity
@Table(name = "payment_gateway_transactions", indexes = {
        @Index(name = "idx_gateway_tx_payment", columnList = "paymentId")
}, uniqueConstraints = @UniqueConstraint(
        name = "uk_gateway_tx_provider_ref", columnNames = {"provider", "merchantTxnRef"}))
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
class PaymentGatewayTransaction {
    @Id
    private String id;
    private String paymentId;
    @Column(nullable = false)
    private String provider;
    @Column(nullable = false)
    private String merchantTxnRef;
    private String providerTransactionId;
    @Column(columnDefinition = "text")
    private String requestPayload;
    @Column(columnDefinition = "text")
    private String responsePayload;
    private Boolean signatureValid;
    @Column(nullable = false)
    private boolean processed;
    @Column(nullable = false)
    private int callbackCount;
    private String errorCode;
    @Column(length = 500)
    private String errorMessage;
    @Column(nullable = false)
    private Instant createdAt;
    private Instant updatedAt;
    private Instant lastCallbackAt;
    private Instant processedAt;
}

@Entity
@Table(name = "payment_proofs", indexes = {
        @Index(name = "idx_payment_proof_payment", columnList = "paymentId"),
        @Index(name = "idx_payment_proof_parent", columnList = "parentId"),
        @Index(name = "idx_payment_proof_status", columnList = "status")
}, uniqueConstraints = @UniqueConstraint(name = "uk_payment_proof_file", columnNames = "fileId"))
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
class PaymentProof {
    @Id
    private String id;
    @Column(nullable = false)
    private String paymentId;
    @Column(nullable = false)
    private String invoiceId;
    private String invoiceCode;
    private String parentId;
    @Column(nullable = false)
    private String studentId;
    private String studentCode;
    private String studentName;
    private long amount;
    @Column(nullable = false)
    private String fileId;
    private String fileName;
    private String contentType;
    private long sizeBytes;
    /** SUBMITTED | APPROVED | RETRY_REQUIRED */
    @Column(nullable = false)
    private String status;
    @Column(nullable = false)
    private String submittedBy;
    @Column(nullable = false)
    private Instant submittedAt;
    private String reviewedBy;
    private Instant reviewedAt;
    @Column(length = 500)
    private String reviewReason;
}

@Entity
@Table(name = "payment_receipts", indexes = {
        @Index(name = "idx_payment_receipt_invoice", columnList = "invoiceId"),
        @Index(name = "idx_payment_receipt_student", columnList = "studentId"),
        @Index(name = "idx_payment_receipt_parent", columnList = "parentId")
}, uniqueConstraints = {
        @UniqueConstraint(name = "uk_payment_receipt_number", columnNames = "receiptNumber"),
        @UniqueConstraint(name = "uk_payment_receipt_payment", columnNames = "paymentId")
})
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
class PaymentReceipt {
    @Id
    private String id;
    @Column(nullable = false, length = 80)
    private String receiptNumber;
    @Column(nullable = false)
    private String paymentId;
    @Column(nullable = false)
    private String invoiceId;
    private String invoiceCode;
    @Column(nullable = false)
    private String studentId;
    private String studentCode;
    private String studentName;
    private String parentId;
    private long amount;
    private String method;
    /** PENDING | ISSUED | FAILED | VOID */
    @Column(nullable = false, length = 24)
    private String status;
    private String fileId;
    @Column(nullable = false)
    private String issuedBy;
    @Column(nullable = false)
    private Instant issuedAt;
    private Instant generatedAt;
    @Column(nullable = false)
    @Builder.Default
    private int generationAttempts = 0;
    @Column(length = 500)
    private String generationError;
    private Integer revision;
    private String previousFileId;
    private String voidedBy;
    private Instant voidedAt;
    @Column(length = 500)
    private String voidReason;
}

@Entity
@Table(name = "bank_statement_entries",
        uniqueConstraints = @UniqueConstraint(name = "uk_bank_statement_txn",
                columnNames = {"bankCode", "transactionReference"}),
        indexes = {
                @Index(name = "idx_bank_statement_status", columnList = "status"),
                @Index(name = "idx_bank_statement_time", columnList = "transferredAt")
        })
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
class BankStatementEntry {
    @Id
    private String id;
    private String bankCode;
    private String transactionReference;
    private long amount;
    private Instant transferredAt;
    @Column(length = 1000)
    private String transferContent;
    private String status;
    private String matchedInvoiceId;
    private String matchedPaymentId;
    private String mismatchReason;
    private String importBatchId;
    private String importedBy;
    private Instant importedAt;
}

@Entity
@Table(name = "payment_refunds", indexes = {
        @Index(name = "idx_payment_refund_payment", columnList = "paymentId"),
        @Index(name = "idx_payment_refund_invoice", columnList = "invoiceId"),
        @Index(name = "idx_payment_refund_student", columnList = "studentId"),
        @Index(name = "idx_payment_refund_parent", columnList = "parentId"),
        @Index(name = "idx_payment_refund_status", columnList = "status")
}, uniqueConstraints = @UniqueConstraint(name = "uk_payment_refund_number", columnNames = "refundNumber"))
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
class PaymentRefund {
    @Id
    private String id;
    @Column(nullable = false, length = 80)
    private String refundNumber;
    @Column(nullable = false)
    private String paymentId;
    @Column(nullable = false)
    private String invoiceId;
    private String invoiceCode;
    @Column(nullable = false)
    private String studentId;
    private String studentCode;
    private String studentName;
    private String parentId;
    private long amount;
    /** PARTIAL | FULL */
    @Column(length = 16)
    private String refundType;
    private Long paymentAmount;
    private Long refundedAmountBefore;
    private Long refundedAmountAfter;
    private Long invoicePaidAmountBefore;
    private Long invoicePaidAmountAfter;
    @Column(length = 24)
    private String invoiceStatusBefore;
    @Column(length = 24)
    private String invoiceStatusAfter;
    @Column(nullable = false, length = 500)
    private String reason;
    /** REQUESTED | COMPLETED | REJECTED | CANCELLED */
    @Column(nullable = false, length = 24)
    private String status;
    @Column(nullable = false)
    private String requestedBy;
    @Column(nullable = false)
    private Instant requestedAt;
    private String approvedBy;
    private Instant approvedAt;
    private String rejectedBy;
    private Instant rejectedAt;
    @Column(length = 500)
    private String rejectionReason;
    private String cancelledBy;
    private Instant cancelledAt;
    @Column(length = 500)
    private String cancellationReason;
    /** MB_BANK_TRANSFER | CASH | OTHER */
    @Column(length = 40)
    private String refundMethod;
    @Column(length = 120)
    private String refundReference;
    private Instant completedAt;
    private Instant updatedAt;
}

@Entity
@Table(name = "payment_reconciliation_runs")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
class PaymentReconciliationRun {
    @Id
    private String id;
    @Column(nullable = false)
    private LocalDate reconciliationDate;
    private LocalDate fromDate;
    private LocalDate toDate;
    private Long minAmount;
    private Long maxAmount;
    @Column(name = "payment_method", length = 40)
    private String method;
    @Column(length = 255)
    private String scopeKey;
    /** BALANCED | DISCREPANCY */
    @Column(nullable = false, length = 24)
    private String status;
    private int paymentCount;
    private long grossAmount;
    private int refundCount;
    private long refundAmount;
    private long netAmount;
    private int discrepancyCount;
    @Column(nullable = false)
    private String runBy;
    @Column(nullable = false)
    private Instant runAt;
    @Column(nullable = false)
    @Builder.Default
    private int runCount = 1;
}

@Entity
@Table(name = "payment_reconciliation_method_summaries", indexes = {
        @Index(name = "idx_payment_reconciliation_method_run", columnList = "runId")
}, uniqueConstraints = @UniqueConstraint(
        name = "uk_payment_reconciliation_method", columnNames = {"runId", "method"}))
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
class PaymentReconciliationMethodSummary {
    @Id
    private String id;
    @Column(nullable = false)
    private String runId;
    @Column(nullable = false, length = 40)
    private String method;
    private int paymentCount;
    private long grossAmount;
    private int refundCount;
    private long refundAmount;
    private long netAmount;
}

@Entity
@Table(name = "payment_reconciliation_issues", indexes = {
        @Index(name = "idx_payment_reconciliation_issue_run", columnList = "runId"),
        @Index(name = "idx_payment_reconciliation_issue_entity", columnList = "entityType,entityId")
})
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
class PaymentReconciliationIssue {
    @Id
    private String id;
    @Column(nullable = false)
    private String runId;
    @Column(nullable = false, length = 60)
    private String issueType;
    @Column(nullable = false, length = 16)
    private String severity;
    @Column(nullable = false, length = 40)
    private String entityType;
    @Column(nullable = false)
    private String entityId;
    private Long expectedAmount;
    private Long actualAmount;
    @Column(nullable = false, length = 700)
    private String message;
    @Column(nullable = false)
    private Instant createdAt;
}
