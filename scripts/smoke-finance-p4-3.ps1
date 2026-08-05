param(
    [string]$BaseUrl = "http://127.0.0.1:4000",
    [string]$AdminUsername = "admin",
    [string]$AdminPassword = "admin@123",
    [string]$StudentId = "u-s-minh"
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

Write-Host "SSE Finance P4.3 smoke against $BaseUrl"
$adminToken = Login $AdminUsername $AdminPassword
$teacherToken = Login "gv.toan" "teacher@123"
$suffix = Get-Date -Format "yyyyMMddHHmmssfff"
$periodId = "fp-p43-$suffix"
$amount = 123457
$businessDate = Get-Date -Format "yyyy-MM-dd"

Invoke-Json POST "/fee-periods" @{
    id = $periodId
    code = "P43-$suffix"
    name = "P4.3 reconciliation smoke"
    targetType = "STUDENT"
    targetIds = @($StudentId)
    dueDate = (Get-Date).AddDays(30).ToString("yyyy-MM-dd")
} $adminToken | Out-Null
Invoke-Json POST "/fee-periods/$periodId/items" @{
    id = "fpi-p43-$suffix"
    name = "P4.3 exact amount fixture"
    amount = $amount
    targetType = "ALL"
    targetIds = @()
} $adminToken | Out-Null
Invoke-Json POST "/fee-periods/$periodId/open" @{} $adminToken | Out-Null
$createdInvoices = @(As-Array (Invoke-Json POST "/fee-periods/$periodId/generate-invoices" @{} $adminToken))
if ($createdInvoices.Count -ne 1) { throw "Expected exactly one P4.3 fixture invoice" }
$invoice = $createdInvoices[0]
$initiated = Invoke-Json POST "/payments" @{ invoiceId = $invoice.id; method = "CASH" } $adminToken
$confirmed = Invoke-Json POST "/payments/$($initiated.payment.id)/cash-confirm" @{} $adminToken
if ($confirmed.payment.status -ne "SUCCESS") { throw "P4.3 fixture payment was not settled" }
Write-Host "[OK] isolated CASH payment fixture created"

$scope = @{
    fromDate = $businessDate
    toDate = $businessDate
    minAmount = $amount
    maxAmount = $amount
    method = "CASH"
}
$firstRun = Invoke-Json POST "/finance/reconciliations" $scope $adminToken
$secondRun = Invoke-Json POST "/finance/reconciliations" $scope $adminToken
if ($firstRun.id -ne $secondRun.id -or $secondRun.runCount -le $firstRun.runCount) {
    throw "The same reconciliation scope was not updated idempotently"
}
if ($secondRun.fromDate -ne $businessDate -or
        $secondRun.toDate -ne $businessDate -or
        $secondRun.method -ne "CASH" -or
        $secondRun.minAmount -ne $amount -or
        $secondRun.maxAmount -ne $amount) {
    throw "Reconciliation scope was not returned correctly"
}
$cashSummary = As-Array $secondRun.methodSummaries | Where-Object { $_.method -eq "CASH" } | Select-Object -First 1
if (-not $cashSummary -or
        $cashSummary.grossAmount -lt $amount -or
        $cashSummary.netAmount -ne ($cashSummary.grossAmount - $cashSummary.refundAmount)) {
    throw "CASH method summary is incorrect"
}
$fixtureIssue = As-Array $secondRun.issues | Where-Object {
    $_.entityId -eq $invoice.id -or $_.entityId -eq $initiated.payment.id
} | Select-Object -First 1
if ($fixtureIssue) { throw "P4.3 fixture produced reconciliation issue: $($fixtureIssue.message)" }
Write-Host "[OK] date, amount and method filters are persisted and repeatable"

$otherScope = Invoke-Json POST "/finance/reconciliations" @{
    fromDate = $businessDate
    toDate = $businessDate
    minAmount = $amount
    maxAmount = $amount
    method = "MB_BANK_TRANSFER"
} $adminToken
if ($otherScope.id -eq $secondRun.id) { throw "Different payment methods reused the same reconciliation run" }
Write-Host "[OK] different scopes create independent reconciliation snapshots"

$legacyRun = Invoke-Json POST "/finance/reconciliations" @{ date = $businessDate } $adminToken
if ($legacyRun.fromDate -ne $businessDate -or $legacyRun.toDate -ne $businessDate) {
    throw "Legacy date-only reconciliation contract is broken"
}
Invoke-Json POST "/finance/reconciliations" @{
    fromDate = $businessDate
    toDate = $businessDate
    minAmount = 200000
    maxAmount = 100000
} $adminToken @(400) | Out-Null
Invoke-Json POST "/finance/reconciliations" @{
    fromDate = (Get-Date).AddDays(-31).ToString("yyyy-MM-dd")
    toDate = $businessDate
} $adminToken @(400) | Out-Null
Invoke-Json GET "/finance/reconciliations" $null $teacherToken @(403) | Out-Null
Write-Host "[OK] legacy API, validation and Admin-only access"
Write-Host "SSE Finance P4.3 smoke completed successfully."
