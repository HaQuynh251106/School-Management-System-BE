package com.sse.app.finance;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sse.app.audit.AuditService;
import com.sse.app.common.ApiException;
import com.sse.app.common.Ids;
import com.sse.app.event.DomainEventPublisher;
import com.sse.app.finance.FinanceDtos.BrowserReturnResponse;
import com.sse.app.finance.FinanceDtos.GatewayCallbackResponse;
import com.sse.app.finance.FinanceDtos.PayRequest;
import com.sse.app.finance.FinanceDtos.PaymentInitResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Owns payment state transitions and keeps gateway callbacks outside invoice CRUD. */
@Service
public class PaymentService {

    private static final Set<String> SUPPORTED_METHODS = Set.of("VNPAY", "MOMO", "CASH", "MB_BANK_TRANSFER");

    private final PaymentRepository payments;
    private final PaymentGatewayTransactionRepository gatewayTransactions;
    private final InvoiceRepository invoices;
    private final List<PaymentGateway> gateways;
    private final BankTransferService bankTransfers;
    private final ObjectMapper objectMapper;
    private final DomainEventPublisher events;
    private final PaymentReceiptService receiptService;
    private final AuditService audit;

    public PaymentService(PaymentRepository payments,
                          PaymentGatewayTransactionRepository gatewayTransactions,
                          InvoiceRepository invoices,
                          List<PaymentGateway> gateways,
                          BankTransferService bankTransfers,
                          ObjectMapper objectMapper,
                          DomainEventPublisher events,
                          PaymentReceiptService receiptService,
                          AuditService audit) {
        this.payments = payments;
        this.gatewayTransactions = gatewayTransactions;
        this.invoices = invoices;
        this.gateways = gateways;
        this.bankTransfers = bankTransfers;
        this.objectMapper = objectMapper;
        this.events = events;
        this.receiptService = receiptService;
        this.audit = audit;
    }

    @Transactional
    public PaymentInitResponse create(PayRequest request, boolean isAdmin) {
        return create(request, isAdmin, "127.0.0.1");
    }

    @Transactional
    public PaymentInitResponse create(PayRequest request, boolean isAdmin, String clientIp) {
        Invoice invoice = invoiceForUpdate(request.invoiceId());
        assertInvoicePayable(invoice);

        String method = normalizeMethod(request.method());
        if ("CASH".equals(method) && !isAdmin) {
            throw ApiException.forbidden("Chỉ Admin được tạo giao dịch tiền mặt");
        }

        long remaining = invoice.getTotalAmount() - invoice.getPaidAmount();
        if (remaining <= 0) {
            throw ApiException.badRequest("Hóa đơn đã thanh toán đủ");
        }

        Payment existingPending = payments.findByInvoiceId(invoice.getId()).stream()
                .filter(payment -> "PENDING".equals(payment.getStatus()))
                .filter(payment -> method.equals(payment.getMethod()))
                .filter(payment -> payment.getAmount() == remaining)
                .findFirst()
                .orElse(null);
        if (existingPending != null) {
            return pendingResponse(existingPending, invoice, clientIp);
        }

        Instant now = Instant.now();
        String paymentId = Ids.gen("pay");
        Payment payment = payments.saveAndFlush(Payment.builder()
                .id(paymentId)
                .invoiceId(invoice.getId())
                .amount(remaining)
                .method(method)
                .status("PENDING")
                .txnRef(merchantTxnRef(method, paymentId))
                .note(initialNote(method))
                .autoProvisioned(false)
                .createdAt(now)
                .updatedAt(now)
                .build());

        if ("CASH".equals(method)) {
            return new PaymentInitResponse(payment, invoice, "PENDING", null, null, null);
        }
        if ("MB_BANK_TRANSFER".equals(method)) {
            return new PaymentInitResponse(payment, invoice, "PENDING", null, null,
                    bankTransfers.instructions(invoice, payment));
        }

        PaymentGateway gateway = gatewayFor(method);
        PaymentGateway.GatewayInitiation initiated = gateway.initiate(new PaymentGateway.PaymentContext(
                payment.getId(), invoice.getId(), payment.getTxnRef(), payment.getAmount(), method, clientIp));

        gatewayTransactions.save(PaymentGatewayTransaction.builder()
                .id(Ids.gen("pgt"))
                .paymentId(payment.getId())
                .provider(method)
                .merchantTxnRef(payment.getTxnRef())
                .requestPayload(json(initiated.requestPayload()))
                .responsePayload(json(initiated.responsePayload()))
                .processed(false)
                .callbackCount(0)
                .createdAt(now)
                .updatedAt(now)
                .build());

        return new PaymentInitResponse(payment, invoice, "PENDING",
                initiated.paymentUrl(), initiated.callbackUrl(), null);
    }

    private PaymentInitResponse pendingResponse(Payment payment, Invoice invoice, String clientIp) {
        if ("CASH".equals(payment.getMethod())) {
            return new PaymentInitResponse(payment, invoice, "PENDING", null, null, null);
        }
        if ("MB_BANK_TRANSFER".equals(payment.getMethod())) {
            return new PaymentInitResponse(payment, invoice, "PENDING", null, null,
                    bankTransfers.instructions(invoice, payment));
        }
        if ("MOMO".equals(payment.getMethod())) {
            return storedMomoResponse(payment, invoice);
        }
        PaymentGateway.GatewayInitiation initiated = gatewayFor(payment.getMethod()).initiate(
                new PaymentGateway.PaymentContext(payment.getId(), invoice.getId(), payment.getTxnRef(),
                        payment.getAmount(), payment.getMethod(), clientIp));
        return new PaymentInitResponse(payment, invoice, "PENDING",
                initiated.paymentUrl(), initiated.callbackUrl(), null);
    }

    private PaymentInitResponse storedMomoResponse(Payment payment, Invoice invoice) {
        PaymentGatewayTransaction transaction = gatewayTransactions
                .findByProviderAndMerchantTxnRefForUpdate(payment.getMethod(), payment.getTxnRef())
                .orElseThrow(() -> ApiException.conflict(
                        "Không tìm thấy phiên thanh toán MoMo đang chờ; vui lòng liên hệ Admin"));
        Map<String, Object> response = jsonObject(transaction.getResponsePayload());
        Map<String, Object> request = jsonObject(transaction.getRequestPayload());
        String paymentUrl = firstNonBlank(textValue(response.get("payUrl")), textValue(response.get("paymentUrl")));
        String callbackUrl = firstNonBlank(textValue(response.get("ipnUrl")), textValue(request.get("ipnUrl")));
        if (paymentUrl == null) {
            throw ApiException.conflict("Phiên thanh toán MoMo đang chờ không có URL hợp lệ; vui lòng liên hệ Admin");
        }
        return new PaymentInitResponse(payment, invoice, "PENDING", paymentUrl, callbackUrl, null);
    }

    @Transactional
    public PaymentInitResponse confirmCash(String paymentId) {
        return confirmCash(paymentId, "SYSTEM");
    }

    @Transactional
    public PaymentInitResponse confirmCash(String paymentId, String confirmedBy) {
        Payment payment = paymentForUpdate(paymentId);
        if (!"CASH".equals(payment.getMethod())) {
            throw ApiException.badRequest("Giao dịch này không phải thanh toán tiền mặt");
        }

        Invoice invoice = invoiceForUpdate(payment.getInvoiceId());
        if ("SUCCESS".equals(payment.getStatus())) {
            receiptService.issueAfterSettlement(payment, invoice, confirmedBy);
            return new PaymentInitResponse(payment, invoice, "SUCCESS", null, null, null);
        }
        if (!"PENDING".equals(payment.getStatus())) {
            throw ApiException.conflict("Chỉ giao dịch PENDING mới được xác nhận");
        }
        assertInvoicePayable(invoice);
        if (payment.getAmount() > invoice.getTotalAmount() - invoice.getPaidAmount()) {
            throw ApiException.conflict("Số tiền giao dịch vượt công nợ còn lại");
        }

        settle(payment, invoice, Instant.now(), confirmedBy);
        return new PaymentInitResponse(payment, invoice, "SUCCESS", null, null, null);
    }

    @Transactional
    public PaymentInitResponse confirmBankTransfer(String paymentId) {
        return confirmBankTransfer(paymentId, "SYSTEM");
    }

    @Transactional
    public PaymentInitResponse confirmBankTransfer(String paymentId, String confirmedBy) {
        Payment payment = paymentForUpdate(paymentId);
        if (!"MB_BANK_TRANSFER".equals(payment.getMethod())) {
            throw ApiException.badRequest("Giao dịch này không phải chuyển khoản MB");
        }

        Invoice invoice = invoiceForUpdate(payment.getInvoiceId());
        if ("SUCCESS".equals(payment.getStatus())) {
            receiptService.issueAfterSettlement(payment, invoice, confirmedBy);
            return new PaymentInitResponse(payment, invoice, "SUCCESS", null, null,
                    bankTransfers.instructions(invoice, payment));
        }
        if (!"PENDING".equals(payment.getStatus())) {
            throw ApiException.conflict("Chỉ giao dịch PENDING mới được xác nhận");
        }
        assertInvoicePayable(invoice);
        if (payment.getAmount() > invoice.getTotalAmount() - invoice.getPaidAmount()) {
            throw ApiException.conflict("Số tiền giao dịch vượt công nợ còn lại");
        }

        settle(payment, invoice, Instant.now(), confirmedBy);
        return new PaymentInitResponse(payment, invoice, "SUCCESS", null, null,
                bankTransfers.instructions(invoice, payment));
    }

    @Transactional
    public GatewayCallbackResponse processIpn(String providerValue, Map<String, String> rawPayload) {
        String provider = normalizeProvider(providerValue);
        PaymentGateway gateway = gatewayFor(provider);
        PaymentGateway.GatewayVerification verification = gateway.verifyCallback(provider, rawPayload);
        String merchantRef = firstNonBlank(
                verification.txnRef(), rawPayload == null ? null : rawPayload.get("txnRef"));
        String logRef = merchantRef == null ? "UNKNOWN-" + Ids.gen("cb") : merchantRef;
        Instant now = Instant.now();

        Payment payment = merchantRef == null
                ? null
                : payments.findByTxnRefForUpdate(merchantRef).orElse(null);
        PaymentGatewayTransaction transaction = gatewayTransactions
                .findByProviderAndMerchantTxnRefForUpdate(provider, logRef)
                .orElseGet(() -> PaymentGatewayTransaction.builder()
                        .id(Ids.gen("pgt"))
                        .provider(provider)
                        .merchantTxnRef(logRef)
                        .processed(false)
                        .callbackCount(0)
                        .createdAt(now)
                        .build());

        transaction.setPaymentId(payment == null ? transaction.getPaymentId() : payment.getId());
        transaction.setResponsePayload(json(rawPayload == null ? Map.of() : rawPayload));
        transaction.setSignatureValid(verification.signatureValid());
        transaction.setCallbackCount(transaction.getCallbackCount() + 1);
        transaction.setLastCallbackAt(now);
        transaction.setUpdatedAt(now);

        if (!verification.signatureValid()) {
            return reject(transaction, payment, null,
                    valueOr(verification.errorCode(), "SIGNATURE_INVALID"),
                    valueOr(verification.errorMessage(), "Chữ ký callback không hợp lệ"));
        }
        if (verification.errorCode() != null && !verification.terminal()) {
            return reject(transaction, payment, null,
                    verification.errorCode(), valueOr(verification.errorMessage(), "Callback không hợp lệ"));
        }
        if (payment == null) {
            return reject(transaction, null, null, "PAYMENT_NOT_FOUND",
                    "Không tìm thấy payment theo txnRef");
        }
        if (!provider.equals(payment.getMethod())) {
            return reject(transaction, payment, null, "PAYMENT_PROVIDER_MISMATCH",
                    "Cổng callback không khớp phương thức của payment");
        }
        if (verification.amount() == null || verification.amount() != payment.getAmount()) {
            return reject(transaction, payment, null, "AMOUNT_MISMATCH",
                    "Số tiền callback không khớp payment");
        }
        if (verification.providerTransactionId() == null) {
            return reject(transaction, payment, null, "PROVIDER_TRANSACTION_MISSING",
                    "Callback thiếu mã giao dịch của cổng thanh toán");
        }
        transaction.setProviderTransactionId(verification.providerTransactionId());

        PaymentGatewayTransaction duplicateProviderTxn = gatewayTransactions
                .findByProviderAndProviderTransactionId(provider, verification.providerTransactionId())
                .orElse(null);
        if (duplicateProviderTxn != null && !duplicateProviderTxn.getId().equals(transaction.getId())) {
            return reject(transaction, payment, null, "PROVIDER_TRANSACTION_DUPLICATE",
                    "Mã giao dịch cổng đã được dùng cho payment khác");
        }

        Invoice currentInvoice = invoices.findById(payment.getInvoiceId()).orElse(null);
        if ("SUCCESS".equals(payment.getStatus()) && verification.successful()) {
            if (currentInvoice != null) {
                receiptService.issueAfterSettlement(payment, currentInvoice, "SYSTEM:" + provider);
            }
            clearError(transaction);
            gatewayTransactions.save(transaction);
            return response(true, false, payment, currentInvoice, transaction,
                    "Callback đã được xử lý trước đó");
        }
        if ("FAILED".equals(payment.getStatus()) && !verification.successful()) {
            clearError(transaction);
            gatewayTransactions.save(transaction);
            return response(true, false, payment, currentInvoice, transaction,
                    "Callback thất bại đã được ghi nhận trước đó");
        }
        if (!"PENDING".equals(payment.getStatus())) {
            return reject(transaction, payment, currentInvoice, "PAYMENT_NOT_PENDING",
                    "Payment không còn ở trạng thái PENDING");
        }
        if (!verification.terminal()) {
            clearError(transaction);
            gatewayTransactions.save(transaction);
            return response(true, false, payment, currentInvoice, transaction,
                    "Cổng thanh toán chưa trả trạng thái cuối cùng");
        }

        if (!verification.successful()) {
            payment.setStatus("FAILED");
            payment.setNote(valueOr(verification.errorMessage(), "Cổng thanh toán báo thất bại"));
            payment.setUpdatedAt(now);
            payments.save(payment);
            transaction.setProcessed(true);
            transaction.setProcessedAt(now);
            transaction.setErrorCode(valueOr(verification.errorCode(), "GATEWAY_FAILED"));
            transaction.setErrorMessage(payment.getNote());
            gatewayTransactions.save(transaction);
            publishFailure(payment, currentInvoice);
            return response(true, true, payment, currentInvoice, transaction,
                    "Đã ghi nhận giao dịch thất bại");
        }

        Invoice invoice = invoiceForUpdate(payment.getInvoiceId());
        if ("CANCELLED".equals(invoice.getStatus()) || "VOID".equals(invoice.getStatus())) {
            return reject(transaction, payment, invoice, "INVOICE_INACTIVE",
                    "Hóa đơn không còn hiệu lực");
        }
        long remaining = invoice.getTotalAmount() - invoice.getPaidAmount();
        if (remaining <= 0 || payment.getAmount() > remaining) {
            return reject(transaction, payment, invoice, "INVOICE_AMOUNT_INVALID",
                    "Hóa đơn không còn đủ công nợ cho giao dịch này");
        }

        settle(payment, invoice, now, "SYSTEM:" + provider);
        clearError(transaction);
        transaction.setProcessed(true);
        transaction.setProcessedAt(now);
        gatewayTransactions.save(transaction);
        return response(true, true, payment, invoice, transaction,
                "IPN hợp lệ, hóa đơn đã được cập nhật");
    }

    public BrowserReturnResponse browserReturn(String providerValue, String paymentId) {
        String provider = normalizeProvider(providerValue);
        Payment payment = get(paymentId);
        if (!provider.equals(payment.getMethod())) {
            throw ApiException.badRequest("Cổng thanh toán không khớp payment");
        }
        boolean finalStatus = Set.of("SUCCESS", "FAILED", "REVERSED", "EXPIRED").contains(payment.getStatus());
        String message = switch (payment.getStatus()) {
            case "SUCCESS" -> "Thanh toán đã được IPN xác nhận";
            case "FAILED" -> "Giao dịch không thành công";
            case "REVERSED" -> "Giao dịch đã được hoàn tác";
            case "EXPIRED" -> "Giao dịch đã hết hiệu lực vì hóa đơn được thanh toán bằng giao dịch khác";
            default -> "Đang chờ cổng thanh toán xác nhận qua IPN";
        };
        return new BrowserReturnResponse(payment.getId(), provider, payment.getStatus(), finalStatus, message,
                null, null, payment.getTxnRef(), payment.getAmount(), null);
    }

    /** Validates provider return data for display only. It never settles a payment or invoice. */
    public BrowserReturnResponse browserReturn(String providerValue, Map<String, String> rawPayload) {
        String provider = normalizeProvider(providerValue);
        String explicitPaymentId = rawPayload == null ? null : rawPayload.get("paymentId");
        boolean hasGatewayPayload = rawPayload != null && ("VNPAY".equals(provider)
                ? rawPayload.keySet().stream().anyMatch(key -> key != null && key.startsWith("vnp_"))
                : rawPayload.containsKey("signature") && rawPayload.containsKey("orderId"));
        if (!hasGatewayPayload) {
            if (explicitPaymentId == null || explicitPaymentId.isBlank()) {
                throw ApiException.badRequest("Return URL thieu paymentId hoac du lieu tu cong thanh toan");
            }
            return browserReturn(provider, explicitPaymentId);
        }

        PaymentGateway.GatewayVerification verification = gatewayFor(provider).verifyCallback(provider, rawPayload);
        if (!verification.signatureValid()) {
            return new BrowserReturnResponse(null, provider, "INVALID", true,
                    valueOr(verification.errorMessage(), "Chu ky Return URL khong hop le"),
                    false, false, verification.txnRef(), verification.amount(),
                    verification.providerTransactionId());
        }
        if (verification.errorCode() != null && !verification.terminal()) {
            return new BrowserReturnResponse(null, provider, "INVALID", true,
                    valueOr(verification.errorMessage(), "Du lieu Return URL khong hop le"),
                    true, false, verification.txnRef(), verification.amount(),
                    verification.providerTransactionId());
        }

        Payment payment = verification.txnRef() == null
                ? null
                : payments.findByTxnRef(verification.txnRef()).orElse(null);
        if (payment == null) {
            return new BrowserReturnResponse(null, provider, "NOT_FOUND", true,
                    "Khong tim thay giao dich tren he thong nha truong",
                    true, verification.successful(), verification.txnRef(), verification.amount(),
                    verification.providerTransactionId());
        }
        if (!provider.equals(payment.getMethod())
                || verification.amount() == null
                || verification.amount() != payment.getAmount()) {
            return new BrowserReturnResponse(payment.getId(), provider, "INVALID", true,
                    "Thong tin giao dich tra ve khong khop payment da khoi tao",
                    true, verification.successful(), payment.getTxnRef(), verification.amount(),
                    verification.providerTransactionId());
        }

        boolean finalStatus = isFinalPaymentStatus(payment.getStatus());
        String providerLabel = "MOMO".equals(provider) ? "MoMo" : "VNPAY";
        String message;
        if ("SUCCESS".equals(payment.getStatus())) {
            message = "Thanh toan da duoc IPN " + providerLabel + " xac nhan";
        } else if ("FAILED".equals(payment.getStatus())) {
            message = "Giao dich khong thanh cong";
        } else if ("EXPIRED".equals(payment.getStatus())) {
            message = "Giao dich da het hieu luc vi hoa don da duoc thanh toan";
        } else if (verification.successful()) {
            message = providerLabel + " bao thanh cong. He thong dang cho IPN xac nhan hoa don";
        } else {
            message = valueOr(verification.errorMessage(), providerLabel + " bao giao dich khong thanh cong");
        }
        return new BrowserReturnResponse(payment.getId(), provider, payment.getStatus(), finalStatus, message,
                true, verification.successful(), payment.getTxnRef(), verification.amount(),
                verification.providerTransactionId());
    }

    public Payment get(String paymentId) {
        return payments.findById(paymentId).orElseThrow(() -> ApiException.notFound("Payment"));
    }

    public List<Payment> paymentsOf(String invoiceId) {
        return payments.findByInvoiceId(invoiceId);
    }

    public List<PaymentGatewayTransaction> gatewayTransactionsOf(String paymentId) {
        get(paymentId);
        return gatewayTransactions.findByPaymentIdOrderByCreatedAtAsc(paymentId);
    }

    private void settle(Payment payment, Invoice invoice, Instant now, String confirmedBy) {
        payment.setStatus("SUCCESS");
        payment.setPaidAt(now);
        payment.setUpdatedAt(now);
        payment.setNote(switch (payment.getMethod()) {
            case "CASH" -> "Admin đã xác nhận thu tiền mặt";
            case "MB_BANK_TRANSFER" -> "Admin đã đối chiếu và duyệt biên lai chuyển khoản MB";
            default -> "IPN hợp lệ từ " + payment.getMethod();
        });
        payments.save(payment);

        invoice.setPaidAmount(invoice.getPaidAmount() + payment.getAmount());
        invoice.setStatus(invoice.getPaidAmount() >= invoice.getTotalAmount() ? "PAID" : "PARTIAL");
        invoices.save(invoice);
        expireOtherPendingPayments(payment, invoice, now);
        PaymentReceipt receipt = receiptService.issueAfterSettlement(payment, invoice, confirmedBy);
        publishSuccess(payment, invoice, receipt);
    }

    private void expireOtherPendingPayments(Payment settledPayment, Invoice invoice, Instant now) {
        if (!"PAID".equals(invoice.getStatus())) return;
        List<Payment> expired = payments.findByInvoiceId(invoice.getId()).stream()
                .filter(candidate -> !Objects.equals(candidate.getId(), settledPayment.getId()))
                .filter(candidate -> "PENDING".equals(candidate.getStatus()))
                .peek(candidate -> {
                    candidate.setStatus("EXPIRED");
                    candidate.setUpdatedAt(now);
                    candidate.setNote("Hóa đơn đã được thanh toán bằng giao dịch khác");
                })
                .toList();
        if (expired.isEmpty()) return;
        payments.saveAll(expired);
        for (Payment payment : expired) {
            audit.record("SYSTEM", "Hệ thống", "SYSTEM", "EXPIRE",
                    "finance", "payment", payment.getId(),
                    "Tự động hết hiệu lực payment " + payment.getTxnRef()
                            + "; invoice=" + invoice.getCode()
                            + "; settledBy=" + settledPayment.getId());
        }
    }

    private void publishSuccess(Payment payment, Invoice invoice, PaymentReceipt receipt) {
        if (invoice.getParentId() == null) return;
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("studentId", invoice.getStudentId());
        payload.put("parentId", invoice.getParentId());
        payload.put("paymentId", payment.getId());
        payload.put("receiptId", receipt.getId());
        payload.put("receiptNumber", receipt.getReceiptNumber());
        payload.put("receiptStatus", receipt.getStatus());
        payload.put("message", String.format("Biên nhận %s: %,d VND (%s)",
                receipt.getReceiptNumber(), payment.getAmount(), payment.getMethod()));
        events.publish("finance.invoice.paid", invoice.getParentId(), "invoice", invoice.getId(), payload);
    }

    private void publishFailure(Payment payment, Invoice invoice) {
        if (invoice == null || invoice.getParentId() == null) return;
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("studentId", invoice.getStudentId());
        payload.put("parentId", invoice.getParentId());
        payload.put("paymentId", payment.getId());
        payload.put("message", "Giao dịch " + payment.getTxnRef() + " không thành công");
        events.publish("finance.payment.failed", invoice.getParentId(), "invoice", invoice.getId(), payload);
    }

    private GatewayCallbackResponse reject(PaymentGatewayTransaction transaction, Payment payment,
                                           Invoice invoice, String code, String message) {
        transaction.setErrorCode(code);
        transaction.setErrorMessage(message);
        gatewayTransactions.save(transaction);
        return response(false, false, payment, invoice, transaction, message);
    }

    private GatewayCallbackResponse response(boolean accepted, boolean processed, Payment payment,
                                             Invoice invoice, PaymentGatewayTransaction transaction,
                                             String message) {
        return new GatewayCallbackResponse(
                accepted,
                processed,
                payment == null ? null : payment.getId(),
                payment == null ? null : payment.getStatus(),
                invoice == null ? null : invoice.getStatus(),
                transaction.getCallbackCount(),
                transaction.getErrorCode(),
                message);
    }

    private void clearError(PaymentGatewayTransaction transaction) {
        transaction.setErrorCode(null);
        transaction.setErrorMessage(null);
    }

    private void assertInvoicePayable(Invoice invoice) {
        if ("CANCELLED".equals(invoice.getStatus()) || "VOID".equals(invoice.getStatus())) {
            throw ApiException.badRequest("Hóa đơn không còn hiệu lực");
        }
    }

    private Invoice invoiceForUpdate(String invoiceId) {
        return invoices.findByIdForUpdate(invoiceId).orElseThrow(() -> ApiException.notFound("Hóa đơn"));
    }

    private Payment paymentForUpdate(String paymentId) {
        return payments.findByIdForUpdate(paymentId).orElseThrow(() -> ApiException.notFound("Payment"));
    }

    private PaymentGateway gatewayFor(String provider) {
        return gateways.stream()
                .filter(gateway -> gateway.supports(provider))
                .findFirst()
                .orElseThrow(() -> ApiException.badRequest("Cổng thanh toán không được hỗ trợ"));
    }

    private String normalizeMethod(String methodValue) {
        String method = methodValue == null || methodValue.isBlank()
                ? "VNPAY"
                : methodValue.trim().toUpperCase(Locale.ROOT);
        if (!SUPPORTED_METHODS.contains(method)) {
            throw ApiException.badRequest("Phương thức thanh toán không được hỗ trợ");
        }
        return method;
    }

    private String initialNote(String method) {
        return switch (method) {
            case "CASH" -> "Chờ Admin xác nhận đã thu tiền mặt";
            case "MB_BANK_TRANSFER" -> "Chờ phụ huynh gửi biên lai và Admin đối chiếu";
            default -> "Chờ cổng thanh toán gửi IPN hợp lệ";
        };
    }

    private String normalizeProvider(String providerValue) {
        String provider = providerValue == null ? "" : providerValue.trim().toUpperCase(Locale.ROOT);
        if (!Set.of("VNPAY", "MOMO").contains(provider)) {
            throw ApiException.badRequest("Cổng thanh toán không được hỗ trợ");
        }
        return provider;
    }

    private String merchantTxnRef(String method, String paymentId) {
        if ("VNPAY".equals(method)) {
            return "SSE" + paymentId.replaceAll("[^A-Za-z0-9]", "");
        }
        return method + "-" + Ids.gen("tx");
    }

    private boolean isFinalPaymentStatus(String status) {
        return Set.of("SUCCESS", "FAILED", "REVERSED", "EXPIRED").contains(status);
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Không thể lưu payload giao dịch", ex);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> jsonObject(String value) {
        if (value == null || value.isBlank()) return Map.of();
        try {
            return objectMapper.readValue(value, Map.class);
        } catch (JsonProcessingException ex) {
            throw ApiException.conflict("Dữ liệu phiên thanh toán đã lưu không hợp lệ");
        }
    }

    private String textValue(Object value) {
        if (value == null) return null;
        String text = value.toString().trim();
        return text.isBlank() ? null : text;
    }

    private String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) return first.trim();
        if (second != null && !second.isBlank()) return second.trim();
        return null;
    }

    private String valueOr(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
