package com.sse.app.finance;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.time.Instant;
import java.time.LocalDate;

interface FeePeriodRepository extends JpaRepository<FeePeriod, String> {
    boolean existsByCodeIgnoreCase(String code);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from FeePeriod p where p.id = :id")
    Optional<FeePeriod> findByIdForUpdate(@Param("id") String id);
}

interface FeePeriodItemRepository extends JpaRepository<FeePeriodItem, String> {
    List<FeePeriodItem> findByFeePeriodId(String feePeriodId);
}

interface FeePeriodTargetRepository extends JpaRepository<FeePeriodTarget, String> {
    List<FeePeriodTarget> findByFeePeriodId(String feePeriodId);
}

interface FeePeriodItemTargetRepository extends JpaRepository<FeePeriodItemTarget, String> {
    List<FeePeriodItemTarget> findByFeePeriodItemId(String feePeriodItemId);
    void deleteByFeePeriodItemId(String feePeriodItemId);
}

interface InvoiceRepository extends JpaRepository<Invoice, String> {
    List<Invoice> findByStudentId(String studentId);
    List<Invoice> findByParentId(String parentId);
    List<Invoice> findByFeePeriodId(String feePeriodId);
    Optional<Invoice> findByFeePeriodIdAndStudentId(String feePeriodId, String studentId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from Invoice i where i.id = :id")
    Optional<Invoice> findByIdForUpdate(@Param("id") String id);
}

interface InvoiceItemRepository extends JpaRepository<InvoiceItem, String> {
    List<InvoiceItem> findByInvoiceId(String invoiceId);
    void deleteByInvoiceId(String invoiceId);
}

interface PaymentRepository extends JpaRepository<Payment, String> {
    List<Payment> findByInvoiceId(String invoiceId);
    List<Payment> findByInvoiceIdInOrderByCreatedAtDesc(Collection<String> invoiceIds);
    List<Payment> findAllByOrderByCreatedAtDesc();
    Optional<Payment> findByTxnRef(String txnRef);
    List<Payment> findByPaidAtGreaterThanEqualAndPaidAtLessThan(Instant from, Instant to);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Payment p where p.id = :id")
    Optional<Payment> findByIdForUpdate(@Param("id") String id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Payment p where p.txnRef = :txnRef")
    Optional<Payment> findByTxnRefForUpdate(@Param("txnRef") String txnRef);
}

interface PaymentGatewayTransactionRepository extends JpaRepository<PaymentGatewayTransaction, String> {
    List<PaymentGatewayTransaction> findByPaymentIdOrderByCreatedAtAsc(String paymentId);
    List<PaymentGatewayTransaction> findByPaymentIdIn(Collection<String> paymentIds);
    Optional<PaymentGatewayTransaction> findByProviderAndProviderTransactionId(
            String provider, String providerTransactionId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from PaymentGatewayTransaction t "
            + "where t.provider = :provider and t.merchantTxnRef = :merchantTxnRef")
    Optional<PaymentGatewayTransaction> findByProviderAndMerchantTxnRefForUpdate(
            @Param("provider") String provider,
            @Param("merchantTxnRef") String merchantTxnRef);
}

interface PaymentProofRepository extends JpaRepository<PaymentProof, String> {
    List<PaymentProof> findByPaymentIdOrderBySubmittedAtDesc(String paymentId);
    List<PaymentProof> findByPaymentIdIn(Collection<String> paymentIds);
    List<PaymentProof> findByParentIdOrderBySubmittedAtDesc(String parentId);
    List<PaymentProof> findAllByOrderBySubmittedAtDesc();
    boolean existsByPaymentIdAndStatus(String paymentId, String status);
    boolean existsByFileId(String fileId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from PaymentProof p where p.id = :id")
    Optional<PaymentProof> findByIdForUpdate(@Param("id") String id);
}

interface PaymentReceiptRepository extends JpaRepository<PaymentReceipt, String> {
    Optional<PaymentReceipt> findByPaymentId(String paymentId);
    List<PaymentReceipt> findByPaymentIdIn(Collection<String> paymentIds);
}

interface BankStatementEntryRepository extends JpaRepository<BankStatementEntry, String> {
    Optional<BankStatementEntry> findByBankCodeAndTransactionReference(
            String bankCode, String transactionReference);
    List<BankStatementEntry> findByImportBatchIdOrderByTransferredAtAsc(String importBatchId);
    List<BankStatementEntry> findByStatusOrderByTransferredAtDesc(String status);
}

interface PaymentRefundRepository extends JpaRepository<PaymentRefund, String> {
    List<PaymentRefund> findAllByOrderByRequestedAtDesc();
    List<PaymentRefund> findByPaymentIdOrderByRequestedAtDesc(String paymentId);
    List<PaymentRefund> findByPaymentIdIn(Collection<String> paymentIds);
    List<PaymentRefund> findByParentIdOrderByRequestedAtDesc(String parentId);
    List<PaymentRefund> findByStudentIdOrderByRequestedAtDesc(String studentId);
    List<PaymentRefund> findByStatusAndCompletedAtGreaterThanEqualAndCompletedAtLessThanOrderByCompletedAtAsc(
            String status, Instant from, Instant to);
    boolean existsByRefundMethodAndRefundReferenceIgnoreCaseAndStatus(
            String refundMethod, String refundReference, String status);

    @Query("select coalesce(sum(r.amount), 0) from PaymentRefund r "
            + "where r.paymentId = :paymentId and r.status in :statuses")
    long sumAmountByPaymentIdAndStatusIn(@Param("paymentId") String paymentId,
                                         @Param("statuses") Collection<String> statuses);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from PaymentRefund r where r.id = :id")
    Optional<PaymentRefund> findByIdForUpdate(@Param("id") String id);
}

interface PaymentReconciliationRunRepository extends JpaRepository<PaymentReconciliationRun, String> {
    List<PaymentReconciliationRun> findAllByOrderByRunAtDesc();
    Optional<PaymentReconciliationRun> findByReconciliationDate(LocalDate reconciliationDate);
    Optional<PaymentReconciliationRun> findByScopeKey(String scopeKey);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from PaymentReconciliationRun r where r.reconciliationDate = :reconciliationDate")
    Optional<PaymentReconciliationRun> findByReconciliationDateForUpdate(
            @Param("reconciliationDate") LocalDate reconciliationDate);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from PaymentReconciliationRun r where r.scopeKey = :scopeKey")
    Optional<PaymentReconciliationRun> findByScopeKeyForUpdate(@Param("scopeKey") String scopeKey);
}

interface PaymentReconciliationMethodSummaryRepository
        extends JpaRepository<PaymentReconciliationMethodSummary, String> {
    List<PaymentReconciliationMethodSummary> findByRunIdOrderByMethodAsc(String runId);
    void deleteByRunId(String runId);
}

interface PaymentReconciliationIssueRepository extends JpaRepository<PaymentReconciliationIssue, String> {
    List<PaymentReconciliationIssue> findByRunIdOrderByCreatedAtAsc(String runId);
    void deleteByRunId(String runId);
}
