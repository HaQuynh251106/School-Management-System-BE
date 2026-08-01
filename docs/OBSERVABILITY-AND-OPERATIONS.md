# Quan sát hệ thống, cảnh báo và sao lưu

## Logging tập trung

Backend ghi đồng thời log console và JSON rolling vào `SSE_LOG_PATH`. Mỗi yêu cầu có
`requestId`, đồng thời được trả về qua header `X-Request-ID` để tra cứu từ lỗi giao diện.
Promtail chuyển log JSON sang Loki; không đưa `requestId` thành label để tránh cardinality cao.
Actuator chạy trên cổng quản trị nội bộ `4001`; compose production không publish cổng này ra Internet.

## Khởi động stack quan sát

```bash
docker compose --env-file .env.production \
  -f docker-compose.prod.yml \
  -f docker-compose.observability.yml up -d
```

- Grafana: cổng `3000` (bắt buộc đặt `SSE_GRAFANA_ADMIN_PASSWORD`).
- Prometheus: cổng `9090`.
- Alertmanager: cổng `9093`.
- Loki: cổng `3100`.

Prometheus cảnh báo khi Backend ngừng phản hồi, tỷ lệ 5xx trên 5%, p95 trên 2 giây
hoặc HikariCP dùng trên 85%. Cảnh báo luôn hiện trong Alertmanager/Grafana. Trước khi
production, đội vận hành phải bổ sung receiver email/webhook trong
`ops/alertmanager/alertmanager.yml` bằng cấu hình sinh từ secret manager.

## Backup định kỳ

Service `postgres-backup` thuộc compose production:

- tạo ngay một backup khi khởi động và lặp lại mỗi 24 giờ;
- dùng PostgreSQL custom format;
- chỉ công nhận file sau khi `pg_restore --list` thành công;
- tạo SHA-256 và metadata;
- mặc định giữ 30 ngày trong volume `postgres-backups`.

Biến cấu hình: `SSE_BACKUP_INTERVAL_SECONDS`, `SSE_BACKUP_RETENTION_DAYS`.
Phải sao chép backup sang object storage/off-site; volume Docker không thay thế bản sao ngoài máy.

## Kiểm tra khôi phục

Trên Windows/local dùng `scripts/restore-postgres.ps1` để restore vào database tách biệt.
Production phải diễn tập tối thiểu mỗi tháng và ghi lại: tên backup, checksum, số bảng,
Flyway thất bại, người thực hiện và thời gian hoàn tất. Không restore đè database chính
nếu Backend chưa dừng và chưa có backup an toàn ngay trước thao tác.
