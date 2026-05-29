# Event Catalog (RabbitMQ)

**Exchange:** `events.topic` (type=topic, durable)
**Format event:**

```json
{
  "id": "uuid",
  "type": "academic.attendance.absent",
  "source": "academic-service",
  "occurredAt": "2026-03-05T08:30:00Z",
  "payload": { /* schema theo từng loại */ }
}
```

## Quy ước routing key

`<domain>.<entity>.<action>` — ví dụ `academic.grade.published`.

## Bảng đầy đủ

| Routing key | Producer | Consumer | Payload chính |
|---|---|---|---|
| `identity.user.login` | identity (P1) | notification audit (P5) | userId, success, ip |
| `identity.user.created` | identity (P1) | academic (P2/P3) cache | userId, role |
| `identity.user.locked` | identity (P1) | notification audit (P5) | userId |
| `identity.password.reset_requested` | identity (P1) | notification (P5) → email | userId, tokenUrl |
| `academic.timetable.changed` | academic (P2) | notification (P5) | classId, semesterId |
| `academic.attendance.absent` | academic (P2) | notification (P5) → push+email PH | studentId, date, period |
| `academic.attendance.recorded` | academic (P2) | audit (P5) | classId, slotId, date |
| `academic.grade.published` | academic (P3) | notification (P5) → push HS+PH | gradeId, studentId, score, subjectId |
| `academic.grade.changed` | academic (P3) | notification + audit (P5) | gradeId, oldScore, newScore, changedBy |
| `academic.assignment.published` | academic (P3) | notification (P5) → lớp | assignmentId, classId, deadline |
| `academic.submission.graded` | academic (P3) | notification (P5) → HS | submissionId, score |
| `academic.extracurricular.enrolled` | academic (P3) | finance (P4) → tạo invoice | enrollmentId, studentId, fee |
| `academic.year.finalized` | academic (P3) | audit (P5) | academicYearId, summaryStats |
| `finance.invoice.issued` | finance (P4) | notification (P5) → email PH | invoiceId, parentId, total |
| `finance.invoice.paid` | finance (P4) | notification (P5) → biên nhận | invoiceId, amount, method |
| `finance.payment.failed` | finance (P4) | audit (P5) + alert ops | paymentId, reason |
| `finance.refund.completed` | finance (P4) | notification (P5) | refundId |

## Quy tắc

- **Versioning:** nếu cần thêm field → BACKWARD COMPATIBLE (chỉ thêm, không sửa/xóa). Nếu phải breaking → publish event mới `*.v2`.
- **At-least-once:** mọi consumer phải idempotent (check `event.id` để dedup).
- **DLQ:** `<queue>.dlq` cho mọi queue chính. P5 quản lý.
