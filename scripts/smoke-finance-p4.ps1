param(
    [string]$BaseUrl = "http://127.0.0.1:4000",
    [string]$AdminUsername = "admin",
    [string]$AdminPassword = "admin@123",
    [string]$PaymentId = "",
    [string]$ParentUsername = "",
    [string]$ParentPassword = "parent@123"
)

$ErrorActionPreference = "Stop"

function Invoke-Json {
    param(
        [ValidateSet("GET", "POST")][string]$Method,
        [string]$Path,
        [object]$Body = $null,
        [string]$Token = $null,
        [int[]]$Expected = @(200)
    )
    $params = @{
        Method = $Method
        Uri = "$BaseUrl$Path"
        ContentType = "application/json"
        UseBasicParsing = $true
    }
    if ($Token) { $params.Headers = @{ Authorization = "Bearer $Token" } }
    if ($null -ne $Body) { $params.Body = ($Body | ConvertTo-Json -Depth 10) }
    try {
        $response = Invoke-WebRequest @params
        if ($Expected -notcontains [int]$response.StatusCode) {
            throw "Expected HTTP $($Expected -join '/') for $Method $Path, got $($response.StatusCode)"
        }
        if ([string]::IsNullOrWhiteSpace($response.Content)) { return $null }
        return $response.Content | ConvertFrom-Json
    } catch {
        $status = $_.Exception.Response.StatusCode.value__
        if ($status -and ($Expected -contains [int]$status)) { return $null }
        throw
    }
}

function As-Array {
    param([object]$Value)
    if ($null -eq $Value) { return @() }
    $properties = @($Value.PSObject.Properties.Name)
    if (($properties -contains "value") -and ($properties -contains "Count")) { return @($Value.value) }
    return @($Value)
}

function Login {
    param([string]$Username, [string]$Password)
    $result = Invoke-Json POST "/auth/login" @{ username = $Username; password = $Password }
    if (-not $result.accessToken) { throw "Login failed for $Username" }
    return $result.accessToken
}

Write-Host "SSE Finance P4.1 smoke against $BaseUrl"
$adminToken = Login $AdminUsername $AdminPassword
$successful = As-Array (Invoke-Json GET "/payment-history?status=SUCCESS" $null $adminToken)
if (-not $PaymentId) {
    $candidate = $successful | Select-Object -First 1
    if (-not $candidate) { throw "No successful payment exists. Confirm one payment before running P4.1 smoke." }
    $PaymentId = $candidate.paymentId
}

$target = $successful | Where-Object { $_.paymentId -eq $PaymentId } | Select-Object -First 1
if (-not $target) { throw "Payment $PaymentId is not a successful payment visible to Admin." }
Write-Host "[OK] Admin history contains payment $PaymentId"

$first = Invoke-Json POST "/payments/$([uri]::EscapeDataString($PaymentId))/receipt/issue" @{} $adminToken
$second = Invoke-Json POST "/payments/$([uri]::EscapeDataString($PaymentId))/receipt/issue" @{} $adminToken
if ($first.status -ne "ISSUED") { throw "Receipt generation failed: $($first.generationError)" }
if ($first.id -ne $second.id -or $first.receiptNumber -ne $second.receiptNumber) {
    throw "Receipt issue is not idempotent for payment $PaymentId"
}
if ($second.generationAttempts -ne $first.generationAttempts) {
    throw "Repeated issue unexpectedly generated another PDF"
}
Write-Host "[OK] receipt issue is idempotent: $($first.receiptNumber)"

$download = Invoke-Json GET "/payments/$([uri]::EscapeDataString($PaymentId))/receipt" $null $adminToken
$tempPath = Join-Path $env:TEMP "$($first.receiptNumber)-smoke.pdf"
try {
    Invoke-WebRequest -UseBasicParsing -Uri $download.downloadUrl -OutFile $tempPath
    $bytes = [System.IO.File]::ReadAllBytes($tempPath)
    if ($bytes.Length -lt 1000 -or [System.Text.Encoding]::ASCII.GetString($bytes, 0, 5) -ne "%PDF-") {
        throw "Downloaded receipt is not a valid PDF"
    }
    Write-Host "[OK] receipt PDF downloaded from MinIO ($($bytes.Length) bytes)"
} finally {
    if (Test-Path -LiteralPath $tempPath) { Remove-Item -LiteralPath $tempPath -Force }
}

$pending = As-Array (Invoke-Json GET "/payment-history?status=PENDING" $null $adminToken) | Select-Object -First 1
if ($pending) {
    Invoke-Json POST "/payments/$([uri]::EscapeDataString($pending.paymentId))/receipt/issue" @{} $adminToken @(409) | Out-Null
    Write-Host "[OK] pending payment cannot receive a receipt"
}

if ($ParentUsername) {
    $parentToken = Login $ParentUsername $ParentPassword
    $parentHistory = As-Array (Invoke-Json GET "/payment-history" $null $parentToken)
    $owned = $parentHistory | Where-Object { $_.paymentId -eq $PaymentId } | Select-Object -First 1
    if (-not $owned) { throw "Parent $ParentUsername does not own payment $PaymentId" }
    $parentDownload = Invoke-Json GET "/payments/$([uri]::EscapeDataString($PaymentId))/receipt" $null $parentToken
    if ($parentDownload.receipt.receiptNumber -ne $first.receiptNumber) {
        throw "Parent receipt does not match Admin receipt"
    }
    Write-Host "[OK] parent can view and download an owned receipt"
}

Write-Host "SSE Finance P4.1 smoke completed successfully."
