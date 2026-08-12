package com.sse.app.finance;

import com.sse.app.finance.FinanceDtos.GatewayCallbackRequest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

@RestController
class SandboxCheckoutController {
    private final FinanceService finance;
    private final SandboxPaymentGateway gateway;

    SandboxCheckoutController(FinanceService finance, SandboxPaymentGateway gateway) {
        this.finance = finance;
        this.gateway = gateway;
    }

    @GetMapping(value = "/payments/sandbox/checkout", produces = MediaType.TEXT_HTML_VALUE)
    ResponseEntity<String> checkout(@RequestParam String txnRef) {
        Map<String, Object> data = finance.sandboxCheckout(txnRef);
        Payment payment = (Payment) data.get("payment");
        Invoice invoice = (Invoice) data.get("invoice");
        String status = payment.getStatus();
        boolean pending = "PENDING".equals(status);
        String actions = pending ? """
                <form method="post" action="/payments/sandbox/checkout/complete">
                  <input type="hidden" name="txnRef" value="%s">
                  <button class="pay" name="outcome" value="SUCCESS">Xác nhận thanh toán</button>
                  <button class="cancel" name="outcome" value="FAILED">Hủy giao dịch</button>
                </form>
                """.formatted(escape(txnRef)) : "<p class=done>Giao dịch đã được xử lý.</p>";
        String html = """
                <!doctype html><html lang="vi"><head><meta charset="utf-8">
                <meta name="viewport" content="width=device-width,initial-scale=1">
                <title>Cổng thanh toán thử nghiệm</title><style>
                body{font-family:Arial,sans-serif;background:#f3f6fb;margin:0;color:#172033}
                main{max-width:440px;margin:40px auto;background:white;padding:24px;border:1px solid #d8e0ee;border-radius:8px}
                h1{font-size:22px;margin:0 0 8px}.tag{color:#2764e7;font-weight:700}.row{display:flex;justify-content:space-between;padding:12px 0;border-bottom:1px solid #edf0f5}
                button{width:100%%;padding:14px;margin-top:12px;border:0;border-radius:6px;font-weight:700}.pay{background:#2764e7;color:white}.cancel{background:#edf1f8}.done{color:#15803d;font-weight:700}
                </style></head><body><main><div class=tag>SSE PAYMENT SANDBOX</div><h1>Thanh toán hóa đơn</h1>
                <div class=row><span>Mã hóa đơn</span><strong>%s</strong></div>
                <div class=row><span>Số tiền</span><strong>%,d ₫</strong></div>
                <div class=row><span>Trạng thái</span><strong>%s</strong></div>%s
                <p>Đây là cổng thử nghiệm local. Chỉ IPN có chữ ký mới cập nhật công nợ.</p></main></body></html>
                """.formatted(escape(invoice.getCode()), payment.getAmount(), escape(status), actions);
        return ResponseEntity.ok().contentType(MediaType.TEXT_HTML).body(html);
    }

    @PostMapping(value = "/payments/sandbox/checkout/complete", produces = MediaType.TEXT_HTML_VALUE)
    ResponseEntity<String> complete(@RequestParam String txnRef, @RequestParam String outcome) {
        Map<String, Object> data = finance.sandboxCheckout(txnRef);
        Payment payment = (Payment) data.get("payment");
        String gatewayTransactionId = "SBX-GW-" + txnRef;
        String eventId = "SBX-EVT-" + UUID.randomUUID().toString().replace("-", "");
        long amount = payment.getAmount();
        String status = "SUCCESS".equalsIgnoreCase(outcome) ? "SUCCESS" : "FAILED";
        String signature = gateway.sign(txnRef, gatewayTransactionId, eventId, amount,
                SandboxPaymentGateway.CURRENCY, status);
        Map<String, Object> result = finance.processGatewayCallback(new GatewayCallbackRequest(
                gateway.merchantCode(), txnRef, gatewayTransactionId, eventId, amount,
                SandboxPaymentGateway.CURRENCY, status, signature));
        Payment updatedPayment = (Payment) result.get("payment");
        String message = "SUCCESS".equals(updatedPayment.getStatus())
                ? "Thanh toán thành công. Bạn có thể quay lại ứng dụng."
                : "Giao dịch đã bị hủy. Bạn có thể quay lại ứng dụng.";
        return ResponseEntity.ok().contentType(MediaType.TEXT_HTML).body("""
                <!doctype html><html lang="vi"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
                <title>Kết quả thanh toán</title></head><body style="font-family:Arial;padding:32px;text-align:center">
                <h1>%s</h1><p>Mã giao dịch: %s</p></body></html>
                """.formatted(message, escape(gatewayTransactionId)));
    }

    private String escape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\"", "&quot;");
    }
}
