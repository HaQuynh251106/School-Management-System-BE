# file-service

**Owner:** P4
**Cổng:** 8085
**DB:** `finance_db` không dùng — file-service có thể không cần DB riêng (lưu metadata trong DB của service gọi). Nếu cần, tạo schema nhỏ `file_db` chỉ có 1 bảng `file_records`.

## Trách nhiệm

- Wrapper trước MinIO (S3-compatible).
- Cấp presigned URL upload + download (1 lần, hết hạn 15 phút).
- Validate kích thước, mime type (whitelist: pdf, docx, jpg, png, mp4 ≤ 50MB).
- Quét virus cơ bản (optional, qua ClamAV — GĐ2 nếu có thời gian).
- Tracking metadata file (ai upload, dùng cho entity nào).

## Cấu trúc package

```
com.sse.file
├── FileServiceApplication.java
├── config/         # MinioConfig
├── controller/     # FileController (presigned URL endpoints)
├── service/        # FileService, PresignedUrlService
├── repository/     # FileRecordRepository (nếu dùng DB)
├── entity/
├── dto/{request,response}/
├── minio/          # MinioClientWrapper, BucketInitializer
└── exception/
```

## Endpoint chính

| Method | Path | Mô tả |
|---|---|---|
| POST | /files/presigned-upload | Trả URL PUT MinIO + fileKey |
| POST | /files/{fileKey}/confirm | Xác nhận upload xong (lưu metadata) |
| GET | /files/{fileKey}/presigned-download | Trả URL GET hết hạn |
| DELETE | /files/{fileKey} | Xóa file (chỉ owner hoặc admin) |

## Bucket convention

- `assignments/{assignmentId}/{uuid}-{fileName}`
- `submissions/{submissionId}/{uuid}-{fileName}`
- `avatars/{userId}/{uuid}.{ext}`
- `chat-attachments/{threadId}/{uuid}-{fileName}`

## Liên kết với academic-service

- P3 khi tạo `assignment_attachments` / `submission_attachments` chỉ lưu `file_key` từ file-service trả về.
- File-service KHÔNG biết về assignment, chỉ giữ file. Service domain (academic) tự quản lý quan hệ.
