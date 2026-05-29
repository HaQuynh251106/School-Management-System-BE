# School Management System — Backend Monorepo

> Hệ thống quản lý trường học microservices. Java 17 + Spring Boot 3.x. 6 service độc lập, PostgreSQL 16 (4 DB) + MongoDB 7 + RabbitMQ + MinIO.

## 🚀 Mới vào dự án? Đọc đây trước

➡️ **[docs/GETTING-STARTED.md](docs/GETTING-STARTED.md)** — Hướng dẫn cài máy + chạy demo + tự thêm nghiệp vụ. **Bắt buộc đọc nếu bạn chưa quen Spring Boot.**

➡️ **[docs/team/TEAM-ASSIGNMENT.md](docs/team/TEAM-ASSIGNMENT.md)** — Phân công 5 thành viên, ai làm bảng nào, endpoint nào.

## Cấu trúc

```
School Management System/
├── docs/
│   ├── GETTING-STARTED.md      ★ Hướng dẫn cho người mới
│   ├── team/TEAM-ASSIGNMENT.md  ★ Phân công + ownership matrix
│   └── architecture/            Kiến trúc, event, git workflow
│
├── infrastructure/             # Docker, K8s, Nginx
│
├── common/                     # Thư viện dùng chung
│
├── services/                   # 6 microservice
│   ├── api-gateway/            (8080) — Owner: P1
│   ├── identity-service/       (8081) — Owner: P1  ★ có sample Role CRUD chạy được
│   ├── academic-service/       (8082) — Owner: P2 + P3
│   ├── finance-service/        (8083) — Owner: P4
│   ├── notification-service/   (8084) — Owner: P5
│   └── file-service/           (8085) — Owner: P4
│
├── docker-compose.dev.yml      # PG x4 + Mongo + Rabbit + MinIO + Mailhog
├── pom.xml                     # Parent multi-module Maven
└── .gitignore
```

## Layout chuẩn của mỗi service (Spring Boot Layered Architecture)

```
service-x/
├── pom.xml
└── src/
    ├── main/
    │   ├── java/com/sse/<name>/
    │   │   ├── <Name>Application.java   # Entry point (main)
    │   │   ├── config/                  # @Configuration beans
    │   │   ├── controller/              # @RestController (REST endpoint)
    │   │   ├── service/                 # @Service (business logic)
    │   │   ├── repository/              # @Repository extends JpaRepository
    │   │   ├── entity/                  # @Entity (map với bảng DB)
    │   │   ├── dto/
    │   │   │   ├── request/             # input từ client
    │   │   │   └── response/            # output cho client
    │   │   ├── mapper/                  # convert entity ↔ DTO
    │   │   ├── event/
    │   │   │   ├── publisher/           # gửi event lên RabbitMQ
    │   │   │   └── listener/            # consume event
    │   │   ├── exception/               # custom exception + handler
    │   │   └── util/                    # helper
    │   └── resources/
    │       ├── application.yml          # cấu hình port, DB, ...
    │       └── db/migration/            # Flyway: V1__*.sql, V2__*.sql
    └── test/java/...
```

## Chạy nhanh (sau khi cài JDK 17 + Maven + Docker)

```bash
# 1. Khởi động hạ tầng (PG x4, Mongo, Rabbit, MinIO)
docker compose -f docker-compose.dev.yml up -d

# 2. Build toàn bộ
mvn -T 1C clean install -DskipTests

# 3. Chạy identity-service (đã có demo Role CRUD)
cd services/identity-service
mvn spring-boot:run

# 4. Test
curl http://localhost:8081/roles
```

Service đã chạy → xem chi tiết test từng endpoint trong [GETTING-STARTED.md](docs/GETTING-STARTED.md).

## Port mapping

| Service | Port | DB Port |
|---|---|---|
| api-gateway | 8080 | — |
| identity-service | 8081 | 5441 (identity_db) |
| academic-service | 8082 | 5442 (academic_db) |
| finance-service | 8083 | 5443 (finance_db) |
| notification-service | 8084 | 5444 (notification_db) + Mongo 27017 |
| file-service | 8085 | — (chỉ MinIO 9000) |
| RabbitMQ | — | 5672 (AMQP) + 15672 (UI) |
| MinIO | — | 9000 (S3) + 9001 (UI) |
| Mailhog | — | 1025 (SMTP) + 8025 (UI) |

## Tài liệu

| File | Mô tả |
|---|---|
| **[docs/GETTING-STARTED.md](docs/GETTING-STARTED.md)** | ★ Cài máy + chạy demo + tự code nghiệp vụ mới |
| **[docs/team/TEAM-ASSIGNMENT.md](docs/team/TEAM-ASSIGNMENT.md)** | ★ Phân công 5 người + ma trận DB/endpoint |
| [docs/architecture/system-overview.md](docs/architecture/system-overview.md) | Sơ đồ kiến trúc tổng quan |
| [docs/architecture/event-catalog.md](docs/architecture/event-catalog.md) | Danh mục RabbitMQ event |
| [docs/architecture/git-workflow.md](docs/architecture/git-workflow.md) | Quy tắc branch + PR |

## Tham chiếu nguồn

- Plan tổng + DDL chi tiết: `~/Downloads/users-a1234-downloads-chu-c-na-ng-xlsx-parallel-haven (1).md`
- Flowchart & Use Case (Mermaid): `~/Downloads/SSE-FLOWCHART-USECASE.md`
