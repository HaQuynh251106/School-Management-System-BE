# Kiến trúc Tổng quan

Tham chiếu chi tiết: plan `users-a1234-downloads-chu-c-na-ng-xlsx-parallel-haven (1).md` §4.

```
Web (React) / Mobile (Flutter)
                │
                ▼
       API Gateway (P1)  :8080
                │
   ┌────────┬──┴──┬───────────┬─────────────┐
   ▼        ▼     ▼           ▼             ▼
Identity Academic Finance Notification    File
(P1)     (P2+P3) (P4)      (P5)          (P4)
 :8081    :8082   :8083     :8084         :8085
   │        │       │          │            │
   │        │       │          │            ▼
 PG-id    PG-ac   PG-fi      PG-no        MinIO
 :5441    :5442   :5443      :5444
                              │
                              ▼
                            Mongo
                            :27017

       Bus: RabbitMQ :5672 (exchange `events.topic`)
       Mọi service publish event → P5 consume
```

## Nguyên tắc

1. **Mỗi service 1 DB độc lập** — không truy vấn chéo DB. Cần dữ liệu → REST internal hoặc subscribe event.
2. **Event-driven cross-service** — RabbitMQ topic exchange, mỗi event có tên `<domain>.<entity>.<action>`.
3. **Gateway là entry point duy nhất** từ client. Service nội bộ KHÔNG expose public.
4. **JWT RS256** — Identity ký bằng private key, Gateway/Service validate bằng public key (JWKS).
5. **Cấm hard FK cross-DB** — chỉ lưu `*_user_id` UUID, validate khi cần.
