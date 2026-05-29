# notification-service

**Owner:** P5
**Cổng:** 8084
**DB:** `notification_db` (PostgreSQL — DDL §5.4) + MongoDB collection (§5.5)
**Phân hệ phụ trách:** E2, A6 (audit log Mongo), B6 (chat) một phần, C5, D3 + bổ sung S12, S14

## Trách nhiệm

- Consume MỌI event từ RabbitMQ (`*.events` exchange) và quyết định:
  - Recipient list (gọi identity-service nếu cần lookup parent).
  - Channel preferences (`user_notification_preferences`).
  - Template (`notification_templates` Handlebars).
- Gửi qua 3 channel: Push (FCM), Email (SendGrid hoặc Mailhog dev), In-app (Mongo + WebSocket).
- Retry với exponential backoff (max 3 lần) + delivery log.
- Audit log MongoDB (collection `audit_logs`) — consume từ tất cả service.
- Chat: `chat_threads`, `chat_messages` trong Mongo + WebSocket realtime.
- In-app notification center.

## Cấu trúc package

```
com.sse.notification
├── NotificationServiceApplication.java
├── config/         # RabbitConfig, MongoConfig, FcmConfig, SendGridConfig, WebSocketConfig
├── controller/     # NotificationController (in-app), PreferenceController, ChatController, AuditController
├── service/        # NotificationService, AuditLogService, ChatService
├── repository/     # JPA (notifications, templates) + MongoRepository (audit, chat, in_app)
├── entity/         # PostgreSQL entities
├── dto/{request,response}/
├── mapper/
├── template/       # HandlebarsRenderer + template loader
├── channel/
│   ├── push/       # FcmSender
│   ├── email/      # EmailSender (SendGrid/Mailhog)
│   └── inapp/      # InAppSender + WebSocket broadcaster
├── consumer/       # RabbitMQ @RabbitListener cho mỗi loại event
├── audit/          # AuditLogConsumer + Mongo writer
├── exception/
└── util/
```

## Endpoint chính

| Method | Path | Mô tả |
|---|---|---|
| GET | /notifications/me | In-app notifications cho user hiện tại |
| PATCH | /notifications/{id}/read | Đánh dấu đã đọc |
| GET/PUT | /me/notification-preferences | Sửa preference |
| POST | /announcements/broadcast | Admin broadcast (10K test) |
| GET | /chats/threads | Danh sách hội thoại |
| GET | /chats/threads/{id}/messages | Lịch sử chat (Mongo) |
| POST | /chats/threads/{id}/messages | Gửi tin nhắn |
| WS | /ws/chat | WebSocket chat realtime |
| GET | /audit-logs?actor&action&date | Audit log viewer (Admin only) |

## RabbitMQ Topology (P5 thiết kế ở S2)

- Exchange: `events.topic` (topic exchange, durable)
- Queue Notification: `notification.queue` (bind `*.event.*` trừ audit)
- Queue Audit: `audit.queue` (bind `*` — mọi event)
- DLQ: `notification.dlq` + `audit.dlq`

## Event consume

- `academic.attendance.absent` → cảnh báo PH
- `academic.grade.published` / `changed` → noti HS+PH
- `academic.assignment.published` → noti lớp
- `academic.submission.graded` → noti HS
- `finance.invoice.issued` → email PH
- `finance.invoice.paid` → biên nhận
- `identity.password.reset_requested` → email reset
- TẤT CẢ events → ghi audit Mongo

## Migration Flyway

`V1__init.sql` ... — P5 sở hữu toàn bộ.
