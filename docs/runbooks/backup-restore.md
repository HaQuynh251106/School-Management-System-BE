# SSE backup and restore

## Backup

Stop write-heavy operations, then run from `C:\SchoolManagementSystem\BE`:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass `
  -File .\scripts\backup-sse.ps1
```

The script creates a timestamped directory under `.backups`, containing a
PostgreSQL custom dump, a manifest, and MinIO data when the local data
directory exists.

## Restore

Restore is destructive and requires the literal confirmation `RESTORE`.
Stop the backend and MinIO before restoring:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass `
  -File .\scripts\restore-sse.ps1 `
  -BackupDirectory .\.backups\YYYYMMDD-HHMMSS `
  -ConfirmRestore RESTORE `
  -RestoreMinio
```

After restore, start dependencies and run `scripts\smoke-app.ps1`.
