# finance-service

**Owner:** P4
**Cổng:** 8083
**DB:** `finance_db` (PostgreSQL — DDL §5.3)
**Phân hệ phụ trách:** A7, D4 + bổ sung S4, S8, S9 (phần invoice items)

## Trách nhiệm

- Quản lý `fee_categories`, `fee_periods`, `fee_period_items`.
- Sinh `invoices` tự động theo khối/HS khi đợt thu mở.
- `payments` + `payment_gateway_transactions` (audit raw payload VNPAY/MoMo).
- Tích hợp VNPAY + MoMo (sandbox trước, prod sau).
- `refunds` (sprint S10).
- Cung cấp báo cáo công nợ cho A8.

## Cấu trúc package

```
com.sse.finance
├── FinanceServiceApplication.java
├── config/
├── controller/      # FeePeriodController, InvoiceController, PaymentController, RefundController
├── service/         # InvoiceService, PaymentService, FeePeriodService
├── repository/
├── entity/
├── dto/{request,response}/
├── mapper/
├── gateway/
│   ├── vnpay/       # VnpayClient, VnpaySignatureUtil, VnpayCallbackHandler
│   └── momo/        # MomoClient, MomoSignatureUtil
├── event/
│   ├── publisher/   # finance.invoice.issued, finance.invoice.paid
│   └── listener/    # academic.extracurricular.enrolled → tạo invoice tự động
└── exception/
```

## Endpoint chính

| Method | Path | Mô tả |
|---|---|---|
| POST | /fee-periods | Tạo đợt thu |
| POST | /fee-periods/{id}/generate-invoices | Bulk sinh invoice |
| GET | /invoices?studentId&status | List invoice |
| GET | /invoices/{id} | Chi tiết hóa đơn |
| POST | /payments | Tạo payment + redirect URL VNPAY/MoMo |
| GET | /payments/vnpay/callback | IPN callback (verify HMAC) |
| GET | /payments/momo/callback | IPN callback |
| POST | /refunds | Yêu cầu hoàn tiền |

## Event publish

- `finance.invoice.issued`
- `finance.invoice.paid`
- `finance.payment.failed`
- `finance.refund.completed`

## Event consume

- `academic.extracurricular.enrolled` → tạo invoice ngoại khóa

## Migration Flyway

Versioning: `V1__init.sql` ... — P4 sở hữu toàn bộ.
