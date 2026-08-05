package com.sse.app.finance;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "sse.payment")
public class PaymentProperties {
    private String publicBaseUrl = "http://127.0.0.1:4000";
    private String sandboxSecret = "dev-payment-secret-change-me";
    private Vnpay vnpay = new Vnpay();
    private Momo momo = new Momo();
    private BankTransfer bankTransfer = new BankTransfer();

    @Data
    public static class Vnpay {
        private boolean enabled = false;
        private String tmnCode = "";
        private String hashSecret = "";
        private String paymentUrl = "https://sandbox.vnpayment.vn/paymentv2/vpcpay.html";
        private String returnUrl = "http://127.0.0.1:5173/?paymentReturn=vnpay";
        private String ipnUrl = "http://127.0.0.1:4000/payments/vnpay/ipn";
        private int expireMinutes = 15;
    }

    @Data
    public static class Momo {
        private boolean enabled = false;
        private String partnerCode = "";
        private String accessKey = "";
        private String secretKey = "";
        private String createUrl = "https://test-payment.momo.vn/v2/gateway/api/create";
        private String redirectUrl = "http://127.0.0.1:5173/?paymentReturn=momo";
        private String ipnUrl = "http://127.0.0.1:4000/payments/momo/ipn";
        private String partnerName = "SSE School";
        private String storeId = "SSESchool";
    }

    @Data
    public static class BankTransfer {
        private boolean enabled = false;
        private String bankId = "MB";
        private String bankName = "MB Bank";
        private String accountNumber = "";
        private String accountName = "";
        private String transferPrefix = "SSE";
        private String qrBaseUrl = "https://img.vietqr.io/image";
    }
}
