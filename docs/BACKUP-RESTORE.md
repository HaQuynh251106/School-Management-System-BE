# Backup và khôi phục PostgreSQL

## Nguyên tắc

- Backup dùng định dạng custom của PostgreSQL, có SHA-256 và metadata đi kèm.
- Mỗi backup phải vượt qua `pg_restore --list` trước khi được coi là hợp lệ.
- Khôi phục mặc định vào database kiểm tra riêng, không ghi đè dữ liệu chính.
- Chỉ được ghi đè `sse_db` khi đã dừng Backend và truyền đồng thời `-ReplaceDatabase -ConfirmRestore`.

## Tạo backup

```powershell
.\scripts\backup-postgres.ps1
```

Nếu PostgreSQL chạy bằng Docker, nên dùng đúng phiên bản công cụ trong container:

```powershell
.\scripts\backup-postgres.ps1 -DockerContainer school-management-system-rbac-postgres
```

Kết quả nằm trong `backups/` gồm `.dump`, `.sha256` và `.json`. Nên chạy hằng ngày, lưu thêm một bản ngoài máy chủ và giữ tối thiểu 30 ngày.

## Diễn tập khôi phục không phá hủy dữ liệu

```powershell
$backup = Get-ChildItem .\backups\*.dump | Sort-Object LastWriteTime -Descending | Select-Object -First 1
.\scripts\restore-postgres.ps1 -BackupFile $backup.FullName -TargetDatabase sse_restore_verify -ReplaceDatabase -ConfirmRestore -DockerContainer school-management-system-rbac-postgres
```

Sau khi kiểm tra xong:

```powershell
$env:PGPASSWORD = $env:SSE_DB_PASSWORD
dropdb -h 127.0.0.1 -p 5433 -U sse sse_restore_verify
```

## Khôi phục database chính

1. Dừng Backend.
2. Tạo thêm một backup an toàn của database hiện tại.
3. Chạy:

```powershell
.\scripts\restore-postgres.ps1 -BackupFile <duong-dan.dump> -TargetDatabase sse_db -ReplaceDatabase -ConfirmRestore
```

4. Khởi động Backend và kiểm tra `/actuator/health`, đăng nhập và các màn hình quan trọng.

## Sửa lịch sử Flyway đã bị thiếu file

Chỉ chạy sau khi đã tạo và xác minh backup:

```powershell
.\scripts\repair-flyway.ps1 -VerifiedBackupFile .\backups\sse_db-YYYYMMDD-HHMMSS.dump
```

Script thực hiện lần lượt `repair`, `migrate` và `validate`; nếu một bước thất bại, Backend không được khởi động lại cho tới khi đã kiểm tra nguyên nhân hoặc khôi phục backup.

## Lịch vận hành đề xuất

- Backup tự động: 01:00 mỗi ngày.
- Diễn tập restore: mỗi tháng một lần.
- Backup trước mọi migration hoặc lần phát hành production.
- Sao lưu cả thư mục upload được cấu hình bởi `SSE_STORAGE_PATH`; database backup không chứa tệp upload.
