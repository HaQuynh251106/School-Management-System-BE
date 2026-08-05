# Identity Phase 1 testing

## Delivered behavior

- Admin reset uses a temporary password, requires a reason and forces the next
  login to change the password.
- Password reset, password change, lock and soft delete revoke refresh tokens
  and invalidate existing access tokens immediately.
- New, imported and restored accounts require a first-login password change.
- RBAC is stored in `roles`, `permissions`, `user_roles` and
  `role_permissions`; Identity and audit endpoints check permission codes.
- User states are `ACTIVE`, `LOCKED`, `PENDING` and `DELETED`.
- Delete is reversible and keeps academic and finance relationships.
- Users can view and revoke their sessions/devices. Admin can manage them for
  another user.
- Login success/failure and every sensitive Identity action are audited.

Existing business accounts are not forced to change their passwords by the
migration. This avoids locking out all current users. Admin reset can enroll
them into the new first-login flow.

## Start locally

```powershell
cd C:\SchoolManagementSystem\BE
docker compose -f docker-compose.dev.yml up -d rabbitmq minio

$env:SSE_DB_URL = "jdbc:postgresql://localhost:5432/sse_db"
$env:SSE_DB_USER = "postgres"
$env:SSE_DB_PASSWORD = "postgres"

& "C:\Users\thaid\.cache\sse-tools\apache-maven-3.9.16\bin\mvn.cmd" `
  -pl services/app -am package -DskipTests

& "C:\Program Files\Eclipse Adoptium\jdk-17.0.19.10-hotspot\bin\java.exe" `
  -jar .\services\app\target\sse-app.jar
```

In another PowerShell window:

```powershell
cd C:\SchoolManagementSystem\Web-FE
& "C:\Program Files\nodejs\npm.cmd" run dev
```

Open `http://127.0.0.1:5173`.

## Automated acceptance

```powershell
cd C:\SchoolManagementSystem\BE

powershell.exe -NoProfile -ExecutionPolicy Bypass `
  -File .\scripts\smoke-identity-p1.ps1 `
  -BaseUrl http://127.0.0.1:4000

powershell.exe -NoProfile -ExecutionPolicy Bypass `
  -File .\scripts\smoke-app.ps1 `
  -BaseUrl http://127.0.0.1:4000

powershell.exe -NoProfile -ExecutionPolicy Bypass `
  -File .\scripts\audit-database.ps1
```

## FE acceptance flow

1. Login as `admin` / `admin@123`.
2. Open **Nguoi dung & phan quyen**.
3. Create an `ACTIVE` test user with a strong temporary password such as
   `Initial@1234`.
4. Login as that user in another browser session. Only profile and first-login
   password change may be used.
5. Change to another strong password. The app logs out and the old access and
   refresh tokens can no longer be used.
6. Login again, click the profile control in the top bar and open
   **Bao mat tai khoan**. Verify the current browser appears in both sessions
   and devices. Login from another browser or incognito window, choose
   **Dang xuat tat ca phien**, and verify both windows return to the login
   screen within five seconds.
7. Return as Admin. Reset the test user's password and enter a reason. Verify
   the user is logged out immediately and must change the temporary password.
8. Lock the user and verify login is denied; unlock and verify login works.
9. Delete the user with a reason. Select the **Da xoa** status filter, then
   restore it. The restored state is `PENDING`.
10. Activate the restored account. Its next login must change password.
11. Open the **Phan quyen vai tro** tab. Admin is intentionally omitted because
    it always has full access. Inspect Teacher, Student and Parent permissions;
    saving requires a reason and takes effect on the next request.
12. Open **Lich su he thong**, filter module `identity`, and verify
    `USER_CREATE`, `PASSWORD_RESET_BY_ADMIN`, `USER_SOFT_DELETE`,
    `USER_RESTORE`, session/device actions and failed logins.

Do not remove `IDENTITY_RBAC_MANAGE` or `AUDIT_READ` from the Admin role. The
backend automatically retains both permissions as a lockout safeguard.

## Main APIs

- `PUT /me/password`
- `GET|DELETE /me/sessions`
- `GET|POST|DELETE /me/devices`
- `POST /users/{id}/reset-password`
- `POST /users/{id}/lock|unlock|pending`
- `DELETE /users/{id}`
- `POST /users/{id}/restore`
- `GET /users/{id}/login-history|sessions|devices`
- `GET /admin/rbac/roles|permissions`
- `PUT /admin/rbac/roles/{roleId}/permissions`
