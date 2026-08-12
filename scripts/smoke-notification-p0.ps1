param(
    [string]$BaseUrl = "http://127.0.0.1:4000",
    [string]$Email = "thaidinh740@gmail.com",
    [string]$AdminUsername = "admin",
    [string]$AdminPassword = "admin@123",
    [int]$TimeoutSeconds = 30
)

$ErrorActionPreference = "Stop"

function Invoke-JsonRequest {
    param(
        [Parameter(Mandatory = $true)][string]$Method,
        [Parameter(Mandatory = $true)][string]$Uri,
        [object]$Body,
        [hashtable]$Headers = @{}
    )

    $arguments = @{
        Method = $Method
        Uri = $Uri
        Headers = $Headers
    }
    if ($null -ne $Body) {
        $arguments.ContentType = "application/json; charset=utf-8"
        $arguments.Body = $Body | ConvertTo-Json -Depth 8
    }
    Invoke-RestMethod @arguments
}

Write-Host "SSE notification P0 smoke against $BaseUrl"

$login = Invoke-JsonRequest -Method Post -Uri "$BaseUrl/auth/login" -Body @{
    username = $AdminUsername
    password = $AdminPassword
}
$accessToken = if ($login.accessToken) { $login.accessToken } else { $login.data.accessToken }
if (-not $accessToken) {
    throw "Khong lay duoc access token Admin."
}
$headers = @{ Authorization = "Bearer $accessToken" }
$startedAt = [DateTimeOffset]::UtcNow

$providerStatus = Invoke-JsonRequest -Method Get `
    -Uri "$BaseUrl/admin/notification-providers/status" -Headers $headers
Write-Host "[OK] provider status: mode=$($providerStatus.mode), SendGrid=$($providerStatus.sendGridConfigured), FCM=$($providerStatus.fcmConfigured)"
if ($providerStatus.mode -eq "REAL" -and -not $providerStatus.sendGridConfigured) {
    throw "Backend dang o REAL mode nhung SendGrid chua duoc cau hinh day du."
}

$forgot = Invoke-JsonRequest -Method Post -Uri "$BaseUrl/auth/forgot-password" -Body @{
    email = $Email
}
if (-not $forgot.ok) {
    throw "API forgot-password khong tra ve ok=true."
}
Write-Host "[OK] forgot-password accepted for $Email"

$delivery = $null
$deadline = [DateTimeOffset]::UtcNow.AddSeconds($TimeoutSeconds)
do {
    Start-Sleep -Milliseconds 500
    $attempts = Invoke-JsonRequest -Method Get `
        -Uri "$BaseUrl/admin/notification-deliveries" -Headers $headers
    $delivery = $attempts | Where-Object {
        $_.provider -eq "SENDGRID" -and
        $_.channel -eq "EMAIL" -and
        [DateTimeOffset]$_.attemptedAt -ge $startedAt
    } | Select-Object -First 1
} while (-not $delivery -and [DateTimeOffset]::UtcNow -lt $deadline)

if (-not $delivery) {
    throw "Khong tim thay SendGrid delivery moi trong $TimeoutSeconds giay."
}
if ($delivery.status -ne "SENT") {
    throw "SendGrid delivery that bai: $($delivery.errorMessage)"
}

$isMock = "$($delivery.providerResponse)" -like "MOCK provider accepted*"
if ($isMock) {
    Write-Host "[OK] RabbitMQ -> SendGrid pipeline passed in MOCK mode"
    Write-Warning "Chua gui email that. Khoi dong backend voi SSE_NOTIFICATION_PROVIDER_MODE=real."
} else {
    Write-Host "[OK] SendGrid accepted the real email delivery"
}

[pscustomobject]@{
    email = $Email
    provider = $delivery.provider
    status = $delivery.status
    mode = if ($isMock) { "MOCK" } else { "REAL" }
    sendGridConfigured = $providerStatus.sendGridConfigured
    fcmConfigured = $providerStatus.fcmConfigured
    fcmCredentialSource = $providerStatus.fcmCredentialSource
    attemptedAt = $delivery.attemptedAt
    response = $delivery.providerResponse
} | Format-List
