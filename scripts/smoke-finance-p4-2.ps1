param(
    [string]$BaseUrl = "http://127.0.0.1:4000",
    [string]$AdminUsername = "admin",
    [string]$AdminPassword = "admin@123",
    [string]$ApproverUsername = "admin.finance",
    [string]$ApproverPassword = "admin2@123",
    [string]$ParentUsername = "ph.nguyen",
    [string]$ParentPassword = "parent@123",
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

Write-Host "SSE Finance P4.2 smoke against $BaseUrl"
$adminToken = Login $AdminUsername $AdminPassword
$approverToken = Login $ApproverUsername $ApproverPassword
$parentToken = Login $ParentUsername $ParentPassword
$teacherToken = Login "gv.toan" "teacher@123"
$suffix = Get-Date -Format "yyyyMMddHHmmssfff"
$periodId = "fp-p42-$suffix"
$periodCode = "P42-$suffix"
$dueDate = (Get-Date).AddDays(30).ToString("yyyy-MM-dd")

Invoke-Json POST "/fee-periods" @{
    id = $periodId
    code = $periodCode
    name = "P4.2 refund and reconciliation smoke"
    targetType = "STUDENT"
    targetIds = @($StudentId)
    dueDate = $dueDate
} $adminToken | Out-Null
Invoke-Json POST "/fee-periods/$periodId/items" @{
    id = "fpi-p42-$suffix"
    name = "P4.2 smoke fee"
    amount = 100000
    targetType = "ALL"
    targetIds = @()
} $adminToken | Out-Null
Invoke-Json POST "/fee-periods/$periodId/open" @{} $adminToken | Out-Null
$preview = Invoke-Json GET "/fee-periods/$periodId/preview" $null $adminToken
if ($preview.newInvoiceCount -ne 1 -or $preview.newTotalAmount -ne 100000) {
    throw "P4.2 fixture preview is incorrect"
}
$createdInvoices = @(As-Array (Invoke-Json POST "/fee-periods/$periodId/generate-invoices" @{} $adminToken))
if ($createdInvoices.Count -ne 1) { throw "Expected exactly one P4.2 fixture invoice" }
$invoice = $createdInvoices[0]
Write-Host "[OK] isolated invoice fixture created: $($invoice.code)"

$initiated = Invoke-Json POST "/payments" @{ invoiceId = $invoice.id; method = "CASH" } $adminToken
$confirmed = Invoke-Json POST "/payments/$($initiated.payment.id)/cash-confirm" @{} $adminToken
if ($confirmed.payment.status -ne "SUCCESS" -or $confirmed.invoice.status -ne "PAID") {
    throw "Fixture payment was not settled"
}
Write-Host "[OK] successful payment and receipt created"

$refund = Invoke-Json POST "/payments/$($initiated.payment.id)/refunds" @{
    amount = 40000
    reason = "Partial refund for P4.2 smoke"
} $adminToken
if ($refund.status -ne "REQUESTED") { throw "Refund was not created as REQUESTED" }
Invoke-Json POST "/payments/$($initiated.payment.id)/refunds" @{
    amount = 70000
    reason = "Probe over-refund"
} $adminToken @(409) | Out-Null
Invoke-Json POST "/payments/$($initiated.payment.id)/refunds" @{
    amount = 1000
    reason = "Parent must not create refunds"
} $parentToken @(403) | Out-Null
Invoke-Json GET "/payment-refunds" $null $teacherToken @(403) | Out-Null
Write-Host "[OK] refund reservation, over-refund guard and role protection"

Invoke-Json POST "/payment-refunds/$($refund.id)/approve" @{
    method = "MB_BANK_TRANSFER"
    reference = "P42-$suffix"
} $adminToken @(409) | Out-Null
$approved = Invoke-Json POST "/payment-refunds/$($refund.id)/approve" @{
    method = "MB_BANK_TRANSFER"
    reference = "P42-$suffix"
} $approverToken
$replayed = Invoke-Json POST "/payment-refunds/$($refund.id)/approve" @{
    method = "MB_BANK_TRANSFER"
    reference = "P42-$suffix"
} $approverToken
if ($approved.status -ne "COMPLETED" -or $replayed.id -ne $approved.id) {
    throw "Refund approval is not idempotent"
}
$updatedInvoiceDetail = Invoke-Json GET "/invoices/$($invoice.id)" $null $adminToken
$updatedInvoice = $updatedInvoiceDetail.invoice
if ($updatedInvoice.paidAmount -ne 60000 -or $updatedInvoice.status -ne "PARTIAL") {
    throw "Invoice was not reduced exactly once after partial refund"
}
$history = As-Array (Invoke-Json GET "/payment-history" $null $adminToken)
$historyRow = $history | Where-Object { $_.paymentId -eq $initiated.payment.id } | Select-Object -First 1
if (-not $historyRow -or $historyRow.refundedAmount -ne 40000 -or $historyRow.netAmount -ne 60000) {
    throw "Payment history refund totals are incorrect"
}
Write-Host "[OK] self-approval is blocked; second Admin approval is idempotent"

$parentRefunds = As-Array (Invoke-Json GET "/payment-refunds" $null $parentToken)
if (-not ($parentRefunds | Where-Object { $_.id -eq $refund.id })) {
    throw "Parent cannot see the completed refund for their child"
}
$deadline = (Get-Date).AddSeconds(25)
$refundNotification = $null
do {
    $notifications = As-Array (Invoke-Json GET "/notifications" $null $parentToken)
    $refundNotification = $notifications | Where-Object { $_.refType -eq "PAYMENT_REFUND" -and $_.refId -eq $refund.id } | Select-Object -First 1
    if (-not $refundNotification) { Start-Sleep -Milliseconds 500 }
} while (-not $refundNotification -and (Get-Date) -lt $deadline)
if (-not $refundNotification) { throw "Parent did not receive async refund notification" }
Write-Host "[OK] parent can view refund and received RabbitMQ notification"

$businessDate = Get-Date -Format "yyyy-MM-dd"
$firstRun = Invoke-Json POST "/finance/reconciliations" @{ date = $businessDate } $adminToken
$secondRun = Invoke-Json POST "/finance/reconciliations" @{ date = $businessDate } $adminToken
if ($firstRun.id -ne $secondRun.id -or $secondRun.runCount -le $firstRun.runCount) {
    throw "Daily reconciliation did not update idempotently"
}
if ($secondRun.netAmount -ne ($secondRun.grossAmount - $secondRun.refundAmount)) {
    throw "Reconciliation net amount formula is incorrect"
}
$fixtureIssue = As-Array $secondRun.issues | Where-Object {
    $_.entityId -eq $invoice.id -or $_.entityId -eq $initiated.payment.id
} | Select-Object -First 1
if ($fixtureIssue) { throw "P4.2 fixture produced reconciliation issue: $($fixtureIssue.message)" }
Write-Host "[OK] daily reconciliation is repeatable and fixture ledger is balanced"

$auditLogs = As-Array (Invoke-Json GET "/audit-logs?action=APPROVE_REFUND" $null $adminToken)
if (-not ($auditLogs | Where-Object { $_.entityId -eq $refund.id })) {
    throw "Refund approval audit log was not found"
}
Write-Host "[OK] refund requester/approver and Admin audit are persisted"
Write-Host "SSE Finance P4.2 smoke completed successfully."
