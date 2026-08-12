package com.sse.app.finance;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

import java.util.List;
import java.util.Optional;
import java.time.Instant;

interface FeePeriodRepository extends JpaRepository<FeePeriod, String> {
    Optional<FeePeriod> findByCode(String code);
}

interface FeePeriodItemRepository extends JpaRepository<FeePeriodItem, String> {
    List<FeePeriodItem> findByFeePeriodId(String feePeriodId);
    void deleteByFeePeriodId(String feePeriodId);
}

interface InvoiceRepository extends JpaRepository<Invoice, String>, JpaSpecificationExecutor<Invoice> {
    List<Invoice> findByStudentId(String studentId);
    List<Invoice> findByParentId(String parentId);
    List<Invoice> findByFeePeriodId(String feePeriodId);
    Optional<Invoice> findByFeePeriodIdAndStudentId(String feePeriodId, String studentId);
    boolean existsByFeePeriodId(String feePeriodId);
}

interface InvoiceItemRepository extends JpaRepository<InvoiceItem, String> {
    List<InvoiceItem> findByInvoiceId(String invoiceId);
}

interface PaymentRepository extends JpaRepository<Payment, String> {
    List<Payment> findByInvoiceIdOrderByCreatedAtAsc(String invoiceId);
    Optional<Payment> findFirstByInvoiceIdAndStatusOrderByCreatedAtDesc(String invoiceId, String status);
}

interface FeePeriodRecipientRepository extends JpaRepository<FeePeriodRecipient, String> {
    List<FeePeriodRecipient> findByFeePeriodId(String feePeriodId);
    void deleteByFeePeriodId(String feePeriodId);
}

interface FeePeriodAdjustmentRepository extends JpaRepository<FeePeriodAdjustment, String> {
    List<FeePeriodAdjustment> findByFeePeriodId(String feePeriodId);
    Optional<FeePeriodAdjustment> findByFeePeriodIdAndStudentId(String feePeriodId, String studentId);
    void deleteByFeePeriodId(String feePeriodId);
}

interface InvoiceRefundRepository extends JpaRepository<InvoiceRefund, String> {
    List<InvoiceRefund> findByInvoiceIdOrderByCreatedAtAsc(String invoiceId);
}

interface PaymentGatewayTransactionRepository extends JpaRepository<PaymentGatewayTransaction, String> {
    Optional<PaymentGatewayTransaction> findByTxnRef(String txnRef);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from PaymentGatewayTransaction t where t.txnRef = :txnRef")
    Optional<PaymentGatewayTransaction> findByTxnRefForUpdate(@Param("txnRef") String txnRef);
    Optional<PaymentGatewayTransaction> findByPaymentId(String paymentId);
    Optional<PaymentGatewayTransaction> findByIdempotencyKey(String idempotencyKey);
    Optional<PaymentGatewayTransaction> findByGatewayTransactionId(String gatewayTransactionId);
    Optional<PaymentGatewayTransaction> findByCallbackEventId(String callbackEventId);
    List<PaymentGatewayTransaction> findByStatusAndCreatedAtBefore(String status, Instant cutoff);
    List<PaymentGatewayTransaction> findByGatewayAndStatusInOrderByCreatedAtDesc(
            String gateway, List<String> statuses);
}
