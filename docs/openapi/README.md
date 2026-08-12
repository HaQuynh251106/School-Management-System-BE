# OpenAPI Contracts

Quy uoc contract-first: contract phai duoc chot va commit truoc khi thay doi endpoint.

| File | Owner | Trang thai |
|---|---|---|
| `identity.yaml` | Identity + Mobile | Da co contract dang nhap, phien va ho so ca nhan |
| `academic.yaml` | Academic + Mobile | Da co contract doc co cau va TKB cot loi |
| `assignment.yaml` | Academic + Mobile | Da co contract F11 |
| `finance.yaml` | P4 + Mobile | Da co contract F17 va state machine hoa don S01 |
| `file.yaml` | P4 + Mobile | Da co contract upload/download private cho F11 |
| `chat.yaml` | BE-Chat + Mobile | Da co contract contact, message, unread va realtime F12 |
| `notification.yaml` | P5 + Mobile | Da co contract inbox va announcement F12 |
| `club.yaml` | Club + Finance + Mobile | Da co contract tao CLB, dang ky, duyet, waitlist, invoice va huy F13 |
| `report.yaml` | Dashboard + Report + Mobile | Da co contract dashboard, bao cao ca nhan, bao cao Admin va export |

Moi operation phai khai bao `x-roles`, `x-object-permission`, `x-idempotency`
va `x-owners`, dong thoi co response cho cac loi nghiep vu ap dung.

Spec dung OpenAPI 3.1 va `$ref` den `components/schemas` de tai su dung DTO.
`OpenApiContractTest` kiem tra YAML, metadata bat buoc va endpoint cot loi trong Docker build.
