# Git Workflow — Chống Conflict cho 5 người

## Branch model

```
main                 ← production-ready, chỉ tag release, PROTECTED
└── develop          ← integration, mỗi sprint merge vào đây, PROTECTED
    ├── feature/p1/<short-desc>     ← P1 (Tech Lead)
    ├── feature/p2/<short-desc>     ← P2
    ├── feature/p3/<short-desc>     ← P3
    ├── feature/p4/<short-desc>     ← P4
    ├── feature/p5/<short-desc>     ← P5
    └── hotfix/<short-desc>         ← khi cần fix gấp trên main
```

Ví dụ: `feature/p2/timetable-conflict-check`, `feature/p4/momo-ipn`.

## Quy tắc PR

1. **PR target = `develop`**, không bao giờ PR thẳng vào `main`.
2. **Trước khi push** chạy:
   ```bash
   git fetch origin
   git rebase origin/develop
   mvn -pl services/<your-service> verify
   ```
3. **PR phải pass CI** (build + test + lint).
4. **Reviewer:** ít nhất 1 người KHÁC (ưu tiên người sở hữu service mà PR đụng tới). Nếu PR đụng `common/*` → P1 bắt buộc review.
5. **Merge strategy:** `squash and merge`, commit message rõ ràng theo Conventional Commits:
   - `feat(identity): add refresh token rotation`
   - `fix(academic): timetable conflict check missed teacher slot`
   - `chore(infra): bump postgres to 16.3`

## Quy tắc ai chạm file nào (whitelist)

| Người | Được quyền sửa |
|---|---|
| P1 | `infrastructure/`, `common/`, `services/api-gateway/`, `services/identity-service/`, `pom.xml`, `docker-compose.dev.yml`, `.github/` |
| P2 | `services/academic-service/**` — chỉ các file P2 sở hữu theo prefix tên (xem [TEAM-ASSIGNMENT.md §3.2](../team/TEAM-ASSIGNMENT.md)); migration `V100__` → `V199__` |
| P3 | `services/academic-service/**` — chỉ các file P3 sở hữu theo prefix tên (xem [TEAM-ASSIGNMENT.md §3.3](../team/TEAM-ASSIGNMENT.md)); migration `V200__` → `V299__` |
| P4 | `services/finance-service/**`, `services/file-service/**` |
| P5 | `services/notification-service/**`, `docs/architecture/event-catalog.md` (cập nhật khi event mới) |

Các file dùng chung trong `academic-service` (`AcademicServiceApplication.java`, `config/`, `exception/`, `pom.xml`) — P2 tạo skeleton, sửa sau cần P2+P3 cùng approve.

## Đụng `common-*` (P1 sở hữu) thì sao?

Người khác cần utility mới:
1. Mở issue mô tả nhu cầu.
2. P1 implement hoặc giao lại — KHÔNG tự thêm class vào `common-*`.

## Migration Flyway — chống đụng version

Quy ước version range (chi tiết trong `services/academic-service/README.md`):
- Identity: `V1__` → `V99__` (P1)
- Academic: P2 `V100__` → `V199__`, P3 `V200__` → `V299__`
- Finance: `V1__` → `V99__` (P4) trong `finance-service/db/migration`
- Notification: `V1__` → `V99__` (P5)

Nếu hai người vô tình trùng version → người merge sau phải đổi tên.

## Commit hook (khuyên)

Trong `.git/hooks/pre-commit` (hoặc dùng husky):
- Reject commit thẳng vào `main`/`develop`.
- Chạy `mvn spotless:check` (P1 cấu hình ở S1).

## Daily sync

- Standup 10 phút sáng: hôm qua xong gì / hôm nay làm gì / blocker.
- Cuối tuần: demo nội bộ + merge develop → mọi PR còn open phải rebase.
