package com.sse.app.finance;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

interface FeePeriodRepository extends JpaRepository<FeePeriod, String> {
}

interface FeePeriodItemRepository extends JpaRepository<FeePeriodItem, String> {
    List<FeePeriodItem> findByFeePeriodId(String feePeriodId);
}

interface InvoiceRepository extends JpaRepository<Invoice, String> {
    List<Invoice> findByStudentId(String studentId);
    List<Invoice> findByParentId(String parentId);
    List<Invoice> findByFeePeriodId(String feePeriodId);
}

interface InvoiceItemRepository extends JpaRepository<InvoiceItem, String> {
    List<InvoiceItem> findByInvoiceId(String invoiceId);
}

interface PaymentRepository extends JpaRepository<Payment, String> {
    List<Payment> findByInvoiceId(String invoiceId);
}
