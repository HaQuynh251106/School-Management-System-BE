# OpenAPI Contracts

Quy uoc contract-first: contract phai duoc chot va commit truoc khi thay doi endpoint.

| File | Owner | Trang thai |
|---|---|---|
| `identity.yaml` | P1 | Chua tao |
| `academic.yaml` | P2 + P3 | Chua tao contract tong |
| `assignment.yaml` | Academic + Mobile | Da co contract F11 |
| `finance.yaml` | P4 + Mobile | Da co contract F17 va state machine hoa don S01 |
| `file.yaml` | P4 + Mobile | Da co contract upload/download private cho F11 |
| `chat.yaml` | BE-Chat + Mobile | Da co contract contact, message, unread va realtime F12 |
| `notification.yaml` | P5 + Mobile | Da co contract inbox va announcement F12 |
| `club.yaml` | Club + Finance + Mobile | Da co contract tao CLB, dang ky, duyet, waitlist, invoice va huy F13 |

Moi operation phai khai bao `x-roles`, `x-object-permission`, `x-idempotency`
va `x-owners`, dong thoi co response cho cac loi nghiep vu ap dung.

Spec dung OpenAPI 3.1 va `$ref` den `components/schemas` de tai su dung DTO.
`OpenApiContractTest` kiem tra YAML, metadata bat buoc va endpoint cot loi trong Docker build.
