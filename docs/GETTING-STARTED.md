# Hướng dẫn bắt đầu code — dành cho người mới

> Đọc xong + làm theo file này, bạn sẽ:
> 1. Hiểu cấu trúc Spring Boot 3 tầng (Controller / Service / Repository).
> 2. Tự chạy được `identity-service` và gọi API trên Postman.
> 3. Biết tạo file mới theo đúng mẫu — copy logic Role để làm User, Class, Grade…

---

## Phần 1 — Khái niệm bắt buộc phải nắm (5 phút đọc)

### Spring Boot là gì?

Framework Java giúp viết REST API nhanh. Bạn chỉ cần:
- Đánh dấu class với annotation (`@RestController`, `@Service`, …) → Spring tự inject, tự route, tự tạo bean.
- Khai báo `application.yml` → Spring đọc cấu hình DB, port, …
- Chạy `main()` → server lên.

### Kiến trúc 3 tầng (Layered Architecture)

Mỗi nghiệp vụ (ví dụ "quản lý Role") cần 3-5 file, xếp theo tầng:

```
HTTP Request
    │
    ▼
┌─────────────────────┐
│  Controller         │  ← nhận request, trả response
│  (RoleController)   │     KHÔNG có logic, chỉ gọi Service
└──────────┬──────────┘
           │ gọi
           ▼
┌─────────────────────┐
│  Service            │  ← logic nghiệp vụ
│  (RoleService)      │     validate, tính toán, transaction
└──────────┬──────────┘
           │ gọi
           ▼
┌─────────────────────┐
│  Repository         │  ← truy vấn DB
│  (RoleRepository)   │     extends JpaRepository — Spring tự sinh code
└──────────┬──────────┘
           │ map với
           ▼
┌─────────────────────┐
│  Entity             │  ← class Java map 1-1 với bảng SQL
│  (Role.java)        │     có @Entity, @Table, @Column
└─────────────────────┘
```

Ngoài ra:
- **DTO (Data Transfer Object)** — class trung gian giữa client và entity. Có 2 loại:
  - `request/CreateRoleRequest.java` — nhận từ client
  - `response/RoleResponse.java` — trả về client
- **Flyway migration** — file SQL `V1__*.sql` chạy tự động khi server khởi động, tạo bảng DB.

---

## Phần 2 — Cài đặt máy (làm 1 lần duy nhất)

### 1. JDK 17

```bash
# macOS với Homebrew
brew install --cask temurin@17

# Verify
java -version    # phải hiện "openjdk 17"
```

### 2. Maven 3.9+

```bash
brew install maven
mvn -version
```

### 3. Docker Desktop

Tải tại https://www.docker.com/products/docker-desktop/ và mở app.

```bash
docker --version
docker compose version
```

### 4. IntelliJ IDEA (Community Edition miễn phí là đủ)

Tải tại https://www.jetbrains.com/idea/download/. Cài plugin **Lombok** (Settings → Plugins → search "Lombok" → Install).

### 5. Postman (test API)

Tải tại https://www.postman.com/downloads/.

---

## Phần 3 — Chạy demo `identity-service` end-to-end (làm theo y nguyên)

### Bước 1: Clone repo + mở project

```bash
cd ~
git clone https://github.com/HaQuynh251106/School-Management-System-BE.git "School Management System"
cd "School Management System"
```

Mở IntelliJ → **File → Open** → chọn file `pom.xml` ở thư mục gốc → bấm **Open as Project**.

IntelliJ sẽ tự nhận đây là multi-module Maven, mất ~1 phút để tải dependency lần đầu.

### Bước 2: Khởi động hạ tầng (PostgreSQL + Mongo + Rabbit + MinIO)

```bash
docker compose -f docker-compose.dev.yml up -d
```

Kiểm tra container đã chạy:

```bash
docker ps
# Phải thấy: sse-pg-identity, sse-pg-academic, sse-pg-finance,
#            sse-pg-notification, sse-mongo, sse-rabbit, sse-minio, sse-mailhog
```

Nếu muốn xem DB bằng GUI, dùng **DBeaver** hoặc **TablePlus**, kết nối:
- Host: `localhost`
- Port: `5441` (identity_db), `5442` (academic_db), `5443` (finance_db), `5444` (notification_db)
- User: `sse` / Password: `sse_dev`

### Bước 3: Chạy `identity-service`

Trong IntelliJ:
- Mở file `services/identity-service/src/main/java/com/sse/identity/IdentityServiceApplication.java`
- Right-click → **Run 'IdentityServiceApplication'**

Bạn sẽ thấy log:

```
... o.f.c.i.database.base.BaseDatabaseType: Database: jdbc:postgresql://localhost:5441/identity_db
... o.f.core.internal.command.DbMigrate: Migrating schema "public" to version "1 - create roles"
... o.s.b.w.embedded.tomcat.TomcatWebServer: Tomcat started on port 8081
... c.s.identity.IdentityServiceApplication: Started IdentityServiceApplication in 3.2 seconds
```

Service đã chạy ở port **8081**.

### Bước 4: Test API bằng Postman (hoặc curl)

**Test 1 — Lấy tất cả role (đã có sẵn 4 role từ migration V1):**

```bash
curl http://localhost:8081/roles
```

Kết quả:

```json
[
  {"id":1,"code":"ADMIN","name":"Quản trị viên","description":"...","createdAt":"..."},
  {"id":2,"code":"TEACHER","name":"Giáo viên","description":"...","createdAt":"..."},
  {"id":3,"code":"STUDENT","name":"Học sinh","description":"...","createdAt":"..."},
  {"id":4,"code":"PARENT","name":"Phụ huynh","description":"...","createdAt":"..."}
]
```

**Test 2 — Tạo role mới:**

```bash
curl -X POST http://localhost:8081/roles \
  -H "Content-Type: application/json" \
  -d '{"code":"GUEST","name":"Khách","description":"Chỉ xem"}'
```

Kết quả 201:

```json
{"id":5,"code":"GUEST","name":"Khách","description":"Chỉ xem","createdAt":"..."}
```

**Test 3 — Validate:**

```bash
curl -X POST http://localhost:8081/roles \
  -H "Content-Type: application/json" \
  -d '{"code":"","name":""}'
```

Sẽ trả 400 vì `@NotBlank` chặn.

✅ **Nếu cả 3 test pass — bạn đã hiểu pipeline.** Sang Phần 4 để học cách tự thêm nghiệp vụ mới.

---

## Phần 4 — Tự thêm nghiệp vụ mới (làm thêm "Permission")

Lặp lại pattern của Role, lần này thêm bảng `permissions`. Đây là bài tập để chắc chắn bạn nắm pattern.

### Bước 1 — Tạo migration

Tạo file `services/identity-service/src/main/resources/db/migration/V2__create_permissions.sql`:

```sql
CREATE TABLE permissions (
    id          BIGSERIAL PRIMARY KEY,
    code        VARCHAR(100) NOT NULL UNIQUE,
    name        VARCHAR(150) NOT NULL,
    module      VARCHAR(50)  NOT NULL,
    created_at  TIMESTAMP    NOT NULL DEFAULT now()
);
```

### Bước 2 — Tạo Entity

Tạo file `entity/Permission.java`:

```java
package com.sse.identity.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "permissions")
@Getter @Setter @NoArgsConstructor
public class Permission {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 100)
    private String code;

    @Column(nullable = false, length = 150)
    private String name;

    @Column(nullable = false, length = 50)
    private String module;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();
}
```

### Bước 3 — Tạo Repository

Tạo file `repository/PermissionRepository.java`:

```java
package com.sse.identity.repository;

import com.sse.identity.entity.Permission;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PermissionRepository extends JpaRepository<Permission, Long> {
    boolean existsByCode(String code);
}
```

### Bước 4 — Tạo DTO

`dto/request/CreatePermissionRequest.java`:

```java
package com.sse.identity.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class CreatePermissionRequest {
    @NotBlank private String code;
    @NotBlank private String name;
    @NotBlank private String module;
}
```

`dto/response/PermissionResponse.java`:

```java
package com.sse.identity.dto.response;

import lombok.Data;

@Data
public class PermissionResponse {
    private Long id;
    private String code;
    private String name;
    private String module;
}
```

### Bước 5 — Tạo Service

`service/PermissionService.java`:

```java
package com.sse.identity.service;

import com.sse.identity.dto.request.CreatePermissionRequest;
import com.sse.identity.dto.response.PermissionResponse;
import com.sse.identity.entity.Permission;
import com.sse.identity.repository.PermissionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;

@Service
@RequiredArgsConstructor
public class PermissionService {

    private final PermissionRepository repo;

    @Transactional(readOnly = true)
    public List<PermissionResponse> getAll() {
        return repo.findAll().stream().map(this::toDto).toList();
    }

    @Transactional
    public PermissionResponse create(CreatePermissionRequest req) {
        if (repo.existsByCode(req.getCode())) {
            throw new IllegalArgumentException("code đã tồn tại");
        }
        Permission p = new Permission();
        p.setCode(req.getCode());
        p.setName(req.getName());
        p.setModule(req.getModule());
        return toDto(repo.save(p));
    }

    private PermissionResponse toDto(Permission p) {
        PermissionResponse r = new PermissionResponse();
        r.setId(p.getId());
        r.setCode(p.getCode());
        r.setName(p.getName());
        r.setModule(p.getModule());
        return r;
    }
}
```

### Bước 6 — Tạo Controller

`controller/PermissionController.java`:

```java
package com.sse.identity.controller;

import com.sse.identity.dto.request.CreatePermissionRequest;
import com.sse.identity.dto.response.PermissionResponse;
import com.sse.identity.service.PermissionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/permissions")
@RequiredArgsConstructor
public class PermissionController {

    private final PermissionService service;

    @GetMapping
    public List<PermissionResponse> getAll() {
        return service.getAll();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public PermissionResponse create(@Valid @RequestBody CreatePermissionRequest req) {
        return service.create(req);
    }
}
```

### Bước 7 — Restart và test

Trong IntelliJ, nhấn ⏹ stop, rồi ▶ run lại `IdentityServiceApplication`. Flyway sẽ tự chạy `V2__create_permissions.sql`.

```bash
curl -X POST http://localhost:8081/permissions \
  -H "Content-Type: application/json" \
  -d '{"code":"USER_CREATE","name":"Tạo user","module":"identity"}'

curl http://localhost:8081/permissions
```

✅ Xong. Bạn vừa lặp lại pattern 5 tầng.

---

## Phần 5 — Mẫu copy nhanh cho từng entity

Khi cần thêm entity mới (ví dụ `Class`, `Grade`, `Invoice`), **lặp y nguyên 7 bước** trên với 7 file:

| # | Loại | Đường dẫn | Nội dung |
|---|---|---|---|
| 1 | Migration SQL | `db/migration/V<n>__create_<tên>.sql` | `CREATE TABLE ...` |
| 2 | Entity | `entity/<Tên>.java` | `@Entity @Table` + field |
| 3 | Repository | `repository/<Tên>Repository.java` | `extends JpaRepository<Tên, Long>` |
| 4 | Request DTO | `dto/request/Create<Tên>Request.java` | Input từ client + `@NotBlank` |
| 5 | Response DTO | `dto/response/<Tên>Response.java` | Output cho client |
| 6 | Service | `service/<Tên>Service.java` | Logic + transaction |
| 7 | Controller | `controller/<Tên>Controller.java` | REST endpoint |

**Quy tắc đặt tên:**
- Entity: số ít, PascalCase — `Class`, `Grade`, `Invoice`.
- Bảng DB: số nhiều, snake_case — `classes`, `grades`, `invoices`.
- File `*Repository`, `*Service`, `*Controller` đi kèm tên entity.

---

## Phần 6 — Câu hỏi thường gặp

### Q: Sao file Java có `private final RoleRepository roleRepository` mà không thấy chỗ nào `new`?

A: **Dependency Injection** — Spring tự tạo instance và gán qua constructor (do `@RequiredArgsConstructor` của Lombok). Bạn KHÔNG tự new.

### Q: Lombok làm cái gì?

A: Annotation `@Getter`, `@Setter`, `@RequiredArgsConstructor`, `@Data` tự sinh getter/setter/constructor lúc compile. Trong IntelliJ phải cài plugin Lombok thì mới không bị báo "method not found".

### Q: Tại sao có DTO mà không trả thẳng Entity?

A: 2 lý do:
1. **An toàn:** entity `User` có field `passwordHash` — nếu trả thẳng, lộ password.
2. **Linh hoạt:** đổi format API mà không phải đổi schema DB.

### Q: Flyway lỗi "Validate failed: Migrations have failed validation"?

A: Bạn đã sửa file `V1__*.sql` SAU KHI nó đã chạy. Nguyên tắc Flyway: **file migration đã chạy không được sửa**. Nếu cần đổi, tạo file mới `V<n+1>__*.sql`. Nếu chỉ là môi trường dev, có thể reset bằng cách:

```bash
docker compose -f docker-compose.dev.yml down -v   # xóa hết volume DB
docker compose -f docker-compose.dev.yml up -d     # tạo lại từ đầu
```

### Q: `@Transactional` để làm gì?

A: Đảm bảo nhiều câu SQL trong cùng method chạy "tất cả hoặc không cái nào". Ví dụ tạo invoice + tạo invoice_items: nếu items lỗi, invoice cũng rollback.

### Q: Sao gọi từ Postman tới `http://localhost:8081` được mà không qua gateway 8080?

A: Ở dev, mỗi service expose port riêng để debug dễ. Khi production, client chỉ gọi gateway 8080, gateway tự route. Hiện tại gateway chưa cấu hình route nên gọi trực tiếp service.

### Q: Khi nào dùng Mapper riêng (MapStruct) thay vì viết tay `toDto`?

A: Khi entity có > 10 field hoặc có nested object. Hiện tại 5-6 field viết tay là OK. Khi cần MapStruct, P1 sẽ thêm vào `common/`.

### Q: Push code lên Git như thế nào?

A:

```bash
# Tạo branch riêng (không commit thẳng vào main/develop)
git checkout -b feature/p1/permission-crud

# Code, commit
git add .
git commit -m "feat(identity): add permission CRUD"

# Push
git push -u origin feature/p1/permission-crud

# Lên GitHub tạo Pull Request, target = develop
```

---

## Phần 7 — Roadmap học tiếp

Sau khi nắm pattern cơ bản, học theo thứ tự:

1. **Validation nâng cao** — `@Email`, `@Pattern`, custom validator
2. **Exception handling** — `@ControllerAdvice` để return 4xx/5xx chuẩn
3. **Quan hệ entity** — `@ManyToOne`, `@OneToMany` (ví dụ Role ↔ Permission n-n)
4. **JPA query** — `@Query`, Specification, paging với `Pageable`
5. **Security** — Spring Security + JWT (P1 setup ở sprint S2)
6. **Testing** — `@SpringBootTest`, `MockMvc`, Testcontainers
7. **RabbitMQ** — `@RabbitListener` để consume event
8. **WebSocket** — STOMP cho chat realtime (P5 sprint S12)

Mỗi chủ đề có thể học qua **Baeldung** (https://www.baeldung.com/) hoặc **Spring docs**.

---

**Kết luận:** Code đầu tiên có thể chậm vì lạ. Sau 2-3 entity sẽ thành tay. Khi gặp lỗi, đọc kỹ stack trace (Spring báo lỗi rất rõ), Google nguyên dòng đầu tiên.
