package com.sse.app.finance;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.List;
import java.util.Optional;
import java.time.Instant;

interface FeePeriodRepository extends JpaRepository<FeePeriod, String> {
    Optional<FeePeriod> findByCode(String code);
    List<FeePeriod> findByAcademicYearIdOrderByCreatedAtDesc(String academicYearId);
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

interface PaymentRepository extends JpaRepository<Payment, String>, JpaSpecificationExecutor<Payment> {
    List<Payment> findByInvoiceId(String invoiceId);
    Optional<Payment> findFirstByInvoiceIdAndStatusOrderByCreatedAtDesc(String invoiceId, String status);
}

interface PaymentGatewayTransactionRepository extends JpaRepository<PaymentGatewayTransaction, String> {
    Optional<PaymentGatewayTransaction> findByTxnRef(String txnRef);
    Optional<PaymentGatewayTransaction> findByPaymentId(String paymentId);
    List<PaymentGatewayTransaction> findByStatusAndCreatedAtBefore(String status, Instant cutoff);
    List<PaymentGatewayTransaction> findByGatewayAndStatusInOrderByCreatedAtDesc(
            String gateway, List<String> statuses);
}
