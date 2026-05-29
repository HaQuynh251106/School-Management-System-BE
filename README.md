# Smart School Ecosystem — Backend Monorepo

> Hệ thống quản lý trường học microservices. Java 17 + Spring Boot 3.x. 6 service độc lập, PostgreSQL 16 (4 DB) + MongoDB 7 + RabbitMQ + MinIO.

## Cấu trúc

```
sse-backend/
├── docs/                       # Tài liệu
│   └── team/TEAM-ASSIGNMENT.md ← ★ Đọc file này trước
│
├── infrastructure/             # Docker, K8s, Nginx
│
├── common/                     # 1 thư viện dùng chung cho tất cả service
│   └── src/main/java/com/sse/common/{core,security,messaging,web}/
│
├── services/                   # 6 microservice
│   ├── api-gateway/
│   ├── identity-service/
│   ├── academic-service/
│   ├── finance-service/
│   ├── notification-service/
│   └── file-service/
│
├── scripts/
├── .github/workflows/
├── docker-compose.dev.yml
├── pom.xml                     # Parent multi-module
└── .gitignore
```

## Layout mỗi service (chuẩn Spring Boot — flat)

```
service-x/
├── pom.xml
├── README.md
└── src/
    ├── main/
    │   ├── java/com/sse/<name>/
    │   │   ├── <Name>Application.java
    │   │   ├── config/
    │   │   ├── controller/
    │   │   ├── service/
    │   │   ├── repository/
    │   │   ├── entity/
    │   │   ├── dto/
    │   │   │   ├── request/
    │   │   │   └── response/
    │   │   ├── mapper/
    │   │   ├── event/
    │   │   │   ├── publisher/
    │   │   │   └── listener/
    │   │   ├── exception/
    │   │   └── util/
    │   └── resources/
    │       ├── application.yml
    │       └── db/migration/   # Flyway
    └── test/java/...
```

## Bắt đầu nhanh

```bash
# 1. Khởi động hạ tầng
docker compose -f docker-compose.dev.yml up -d

# 2. Build toàn bộ
mvn -T 1C clean install -DskipTests

# 3. Chạy từng service
cd services/identity-service && mvn spring-boot:run    # 8081
cd services/academic-service && mvn spring-boot:run    # 8082
cd services/finance-service && mvn spring-boot:run     # 8083
cd services/notification-service && mvn spring-boot:run # 8084
cd services/file-service && mvn spring-boot:run        # 8085
cd services/api-gateway && mvn spring-boot:run         # 8080 (entry)
```

## Tài liệu

| File | Mô tả |
|---|---|
| [docs/team/TEAM-ASSIGNMENT.md](docs/team/TEAM-ASSIGNMENT.md) | **Phân công 5 thành viên** |
| [docs/architecture/system-overview.md](docs/architecture/system-overview.md) | Kiến trúc tổng quan |
| [docs/architecture/event-catalog.md](docs/architecture/event-catalog.md) | Danh mục RabbitMQ event |
| [docs/architecture/git-workflow.md](docs/architecture/git-workflow.md) | Git workflow |

## Tham chiếu

- Plan tổng + DDL: `~/Downloads/users-a1234-downloads-chu-c-na-ng-xlsx-parallel-haven (1).md`
- Flowchart & Use Case: `~/Downloads/SSE-FLOWCHART-USECASE.md`
