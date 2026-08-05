param(
    [string]$BaseUrl = "http://127.0.0.1:4000",
    [string]$AdminUsername = "admin",
    [string]$AdminPassword = "admin@123"
)

$ErrorActionPreference = "Stop"

function Invoke-Json {
    param(
        [ValidateSet("GET", "POST", "PUT", "DELETE")]
        [string]$Method,
        [string]$Path,
        [object]$Body = $null,
        [string]$Token = $null,
        [int[]]$Expected = @(200)
    )
    $headers = @{}
    if ($Token) { $headers.Authorization = "Bearer $Token" }
    $params = @{
        Method = $Method
        Uri = "$BaseUrl$Path"
        ContentType = "application/json"
        UseBasicParsing = $true
    }
    if ($headers.Count) { $params.Headers = $headers }
    if ($null -ne $Body) { $params.Body = $Body | ConvertTo-Json -Depth 10 }
    try {
        $response = Invoke-WebRequest @params
        if ($Expected -notcontains [int]$response.StatusCode) {
            throw "Expected $($Expected -join '/') for $Method $Path, got $($response.StatusCode)"
        }
        if ([string]::IsNullOrWhiteSpace($response.Content)) { return $null }
        return $response.Content | ConvertFrom-Json
    } catch {
        $status = $_.Exception.Response.StatusCode.value__
        if ($status -and ($Expected -contains [int]$status)) { return $null }
        throw
    }
}

function Login([string]$Username, [string]$Password, [string]$DeviceToken) {
    return Invoke-Json POST "/auth/login" @{
        username = $Username
        password = $Password
        deviceToken = $DeviceToken
        platform = "WEB"
        deviceName = "Identity smoke browser"
    }
}

function As-Array([object]$Value) {
    if ($null -eq $Value) { return @() }
    $properties = @($Value.PSObject.Properties.Name)
    if (($properties -contains "value") -and ($properties -contains "Count")) {
        return @($Value.value)
    }
    return @($Value)
}

Write-Host "Identity P1 smoke against $BaseUrl"
$admin = Login $AdminUsername $AdminPassword "identity-admin-device"
$suffix = [guid]::NewGuid().ToString("N").Substring(0, 10)
$username = "identity.smoke.$suffix"
$initialPassword = "Initial@1234"
$changedPassword = "Changed@1234"
$resetPassword = "ResetStrong@123"
$finalPassword = "StudentFinal@123"

$roles = As-Array (Invoke-Json GET "/admin/rbac/roles" $null $admin.accessToken)
$permissions = As-Array (Invoke-Json GET "/admin/rbac/permissions" $null $admin.accessToken)
if ($roles.Count -lt 4 -or $permissions.Count -lt 16) {
    throw "RBAC catalog is incomplete"
}
Write-Host "[OK] detailed RBAC catalog"

$created = Invoke-Json POST "/users" @{
    username = $username
    password = $initialPassword
    fullName = "Identity Smoke User"
    role = "STUDENT"
    email = "$username@sse.test"
    status = "ACTIVE"
} $admin.accessToken
if (-not $created.passwordChangeRequired) {
    throw "New user was not marked for first-login password change"
}
Write-Host "[OK] new account requires first-login password change"

$firstLogin = Login $username $initialPassword "identity-student-device"
Invoke-Json GET "/classes" $null $firstLogin.accessToken @(403) | Out-Null
$me = Invoke-Json GET "/me" $null $firstLogin.accessToken
if (-not $me.passwordChangeRequired) { throw "First-login flag missing from /me" }
Write-Host "[OK] first-login user is restricted to password flow"

Invoke-Json PUT "/me/password" @{
    currentPassword = $initialPassword
    newPassword = $changedPassword
} $firstLogin.accessToken | Out-Null
Invoke-Json GET "/me" $null $firstLogin.accessToken @(401) | Out-Null
$changedLogin = Login $username $changedPassword "identity-student-device"
if ($changedLogin.user.passwordChangeRequired) {
    throw "Password-change flag was not cleared"
}
Write-Host "[OK] password change revokes old access and refresh session"

$sessions = As-Array (Invoke-Json GET "/me/sessions" $null $changedLogin.accessToken)
$devices = As-Array (Invoke-Json GET "/me/devices" $null $changedLogin.accessToken)
if (@($sessions).Count -lt 1 -or @($devices).Count -lt 1) {
    throw "Session/device registration is incomplete"
}
if (-not (@($sessions | Where-Object current).Count)) {
    throw "Current session was not identified"
}
Write-Host "[OK] self session and device management"

Invoke-Json GET "/admin/rbac/roles" $null $changedLogin.accessToken @(403) | Out-Null
Write-Host "[OK] non-admin cannot manage RBAC"

$adminReset = Invoke-Json POST "/users/$($created.id)/reset-password" @{
    newPassword = $resetPassword
    reason = "Identity smoke admin reset"
} $admin.accessToken
if (-not $adminReset.passwordChangeRequired -or $adminReset.revokedSessions -lt 1) {
    throw "Admin reset did not require password change or revoke sessions"
}
Invoke-Json GET "/me" $null $changedLogin.accessToken @(401) | Out-Null
$resetLogin = Login $username $resetPassword "identity-student-device"
Invoke-Json GET "/classes" $null $resetLogin.accessToken @(403) | Out-Null
Write-Host "[OK] admin reset revokes sessions and forces password change"

Invoke-Json PUT "/me/password" @{
    currentPassword = $resetPassword
    newPassword = $finalPassword
} $resetLogin.accessToken | Out-Null
$finalLogin = Login $username $finalPassword "identity-student-device"

Invoke-Json DELETE "/users/$($created.id)" @{
    reason = "Identity smoke soft delete"
} $admin.accessToken | Out-Null
Invoke-Json GET "/me" $null $finalLogin.accessToken @(401) | Out-Null
Invoke-Json POST "/auth/login" @{
    username = $username
    password = $finalPassword
} $null @(403) | Out-Null
$deleted = As-Array (Invoke-Json GET "/users?status=DELETED&includeDeleted=true&q=$username" $null $admin.accessToken)
if (-not (@($deleted | Where-Object id -eq $created.id).Count)) {
    throw "Soft-deleted account is missing from admin query"
}
Write-Host "[OK] soft delete preserves record and blocks all sessions"

$restored = Invoke-Json POST "/users/$($created.id)/restore" @{
    status = "PENDING"
    reason = "Identity smoke restore"
} $admin.accessToken
if ($restored.status -ne "PENDING") { throw "Restored account is not PENDING" }
Invoke-Json POST "/auth/login" @{
    username = $username
    password = $finalPassword
} $null @(403) | Out-Null
Invoke-Json POST "/users/$($created.id)/unlock" $null $admin.accessToken | Out-Null
$restoredLogin = Login $username $finalPassword "identity-student-device"
if (-not $restoredLogin.user.passwordChangeRequired) {
    throw "Restored account did not require password change"
}
Write-Host "[OK] restore uses PENDING activation and first-login protection"

$audit = As-Array (Invoke-Json GET "/audit-logs?module=identity" $null $admin.accessToken)
foreach ($action in @("USER_CREATE", "PASSWORD_RESET_BY_ADMIN", "USER_SOFT_DELETE", "USER_RESTORE")) {
    if (-not (@($audit | Where-Object action -eq $action).Count)) {
        throw "Missing audit action $action"
    }
}
Write-Host "[OK] sensitive identity actions are audited"

Invoke-Json DELETE "/users/$($created.id)" @{
    reason = "Identity smoke cleanup"
} $admin.accessToken | Out-Null
Write-Host "Identity P1 smoke completed successfully."
