# OpenAPI Contracts

Quy ước "API contract first": mỗi service commit 1 file `<service>.yaml` ở đây trước khi code endpoint.

| File (sẽ tạo) | Owner |
|---|---|
| `identity.yaml` | P1 |
| `academic.yaml` | P2 + P3 (chia tag trong 1 file, hoặc 2 file `academic-core.yaml` / `academic-advanced.yaml`) |
| `finance.yaml` | P4 |
| `file.yaml` | P4 |
| `notification.yaml` | P5 |

Build pipeline (P1 cấu hình S1) sẽ validate spec + sinh client SDK cho frontend.

Spec tham khảo: OpenAPI 3.1, dùng `$ref` đến `components/schemas` để tái sử dụng DTO.
