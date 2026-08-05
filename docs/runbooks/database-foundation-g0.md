# G0 Database Foundation

## Safety rules

- `sse_db` is the local working database. Never drop it to re-seed.
- Flyway owns schema changes. Hibernate runs with `ddl-auto=validate`.
- Production migrations contain schema and mandatory reference data only.
- Demo users and academic scenarios live in `db/seed`, outside Flyway.
- Run a backup and quality audit before every new migration.

## Current baseline

- PostgreSQL: 16
- Flyway migrations: `V1` to `V4`
- `V1`: exact schema captured from the working database on 2026-07-29.
- `V2`: Identity/RBAC foundation, audit/session columns, critical foreign keys,
  checks and indexes.
- `V3`: natural keys for classes, semesters and active teaching assignments.
- `V4`: unique conflict constraints for class, teacher and room timetable slots.

An existing non-empty database is baselined at version 1, then receives V2 and
later migrations. A fresh empty database executes V1 through V4.

## Backup

```powershell
cd C:\SchoolManagementSystem\BE

powershell.exe -NoProfile -ExecutionPolicy Bypass `
  -File .\scripts\backup-sse.ps1 `
  -Database sse_db `
  -DbUser postgres `
  -DbPassword postgres `
  -PgDumpPath "C:\Program Files\PostgreSQL\16\bin\pg_dump.exe"
```

Backups are written under `.backups\<timestamp>` and include:

- PostgreSQL custom-format dump.
- SHA-256 checksum and dump size.
- MinIO bind-volume snapshot when present.
- JSON manifest.

## Restore

Create an empty target database first. Restore into a verification database
before ever restoring `sse_db`.

```powershell
$env:PGPASSWORD = "postgres"
& "C:\Program Files\PostgreSQL\16\bin\createdb.exe" `
  -h 127.0.0.1 -p 5432 -U postgres sse_restore_verify

powershell.exe -NoProfile -ExecutionPolicy Bypass `
  -File .\scripts\restore-sse.ps1 `
  -BackupDirectory "C:\SchoolManagementSystem\BE\.backups\<timestamp>" `
  -ConfirmRestore RESTORE `
  -Database sse_restore_verify `
  -DbUser postgres `
  -DbPassword postgres `
  -PgRestorePath "C:\Program Files\PostgreSQL\16\bin\pg_restore.exe"
```

The restore script validates the manifest checksum before changing the target.

## Quality audit

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass `
  -File .\scripts\audit-database.ps1 `
  -Database sse_db `
  -DbUser postgres `
  -DbPassword postgres `
  -PsqlPath "C:\Program Files\PostgreSQL\16\bin\psql.exe"
```

The command fails when it finds duplicate identifiers, invalid roles/statuses,
orphan relations, invalid scores, missing classes or class-size mismatches.

## Seed profiles

- `baseline`: schema plus mandatory roles and permissions; safest default.
- `demo`: thirty students in every class of the active year, enough teachers,
  parent-child relations, two-semester teaching assignments, a conflict-free
  starter timetable and complete grades.
- `scenario`: demo plus intentional missing grades and lifecycle examples.

Apply the canonical scenario dataset:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass `
  -File .\scripts\seed-database.ps1 `
  -Dataset scenario `
  -Confirm SEED `
  -Database sse_db `
  -DbUser postgres `
  -DbPassword postgres `
  -PsqlPath "C:\Program Files\PostgreSQL\16\bin\psql.exe"
```

The seed is deterministic and idempotent. Re-running it does not increase the
final number of students, parents, assignments or grades.

For a fresh database, the same dataset can be applied at startup:

```powershell
$env:SSE_SEED_DATASET = "scenario"
```

Keep the default `baseline` for normal daily startup after data is loaded.

## Canonical academic scenarios

For each active class:

- Student `001` through `027`: all required grade categories.
- Student `028`: no grades.
- Student `029`: missing `MID`.
- Student `030`: missing `FINAL`.

All generated scores stay in the `0..10` range. Existing teacher-entered scores
are never replaced by the seed.

Default generated credentials:

- Teacher: `teacher@123`
- Student: `student@123`
- Parent: `parent@123`

Generated usernames start with `demo.gv.`, `demo.hs.` and `demo.ph.`.

## UAT and production data cleanup gate

The generated `demo.*` accounts are development fixtures only. Before UAT or
production acceptance:

1. Replace every development student, parent, teacher and non-system admin with
   realistic Vietnamese names, usernames, emails and phone numbers.
2. Remove the `demo.` prefix from all business-facing accounts and identifiers.
3. Keep automated-test identities in an isolated `test` seed/profile that is
   never loaded into UAT or production.
4. Preserve relationship coverage: parents with one child and multiple
   children, assigned teachers, complete grades, missing grades and students
   without grades.
5. Disable generated default passwords and require a first-login password
   change for imported or provisioned users.
6. Run duplicate, orphan, RBAC and relationship audits after the replacement.
7. Verify that no visible Web/API response contains development fixture names
   or the `demo` marker.

## Definition of done for every later database phase

1. Backup created and restore verified.
2. Quality audit passes before migration.
3. A new immutable Flyway migration is added.
4. Migration succeeds on both a restored database and an empty database.
5. Hibernate validation succeeds.
6. Seed runs twice with stable final counts.
7. Unit tests and smoke tests pass.
8. Quality audit passes after migration and seed.
