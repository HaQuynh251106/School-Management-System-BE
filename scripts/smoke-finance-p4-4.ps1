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

function New-PaidFixture {
    param(
        [string]$Tag,
        [long]$Amount
    )
    $periodId = "fp-p44-$Tag"
    Invoke-Json POST "/fee-periods" @{
        id = $periodId
        code = "P44-$Tag"
        name = "P4.4 refund smoke $Tag"
        targetType = "STUDENT"
        targetIds = @($StudentId)
        dueDate = (Get-Date).AddDays(30).ToString("yyyy-MM-dd")
    } $adminToken | Out-Null
    Invoke-Json POST "/fee-periods/$periodId/items" @{
        id = "fpi-p44-$Tag"
        name = "P4.4 refund fixture"
        amount = $Amount
        targetType = "ALL"
        targetIds = @()
    } $adminToken | Out-Null
    Invoke-Json POST "/fee-periods/$periodId/open" @{} $adminToken | Out-Null
    $created = @(As-Array (Invoke-Json POST "/fee-periods/$periodId/generate-invoices" @{} $adminToken))
    if ($created.Count -ne 1) { throw "Expected exactly one P4.4 fixture invoice" }
    $invoice = $created[0]
    $initiated = Invoke-Json POST "/payments" @{ invoiceId = $invoice.id; method = "CASH" } $adminToken
    $settled = Invoke-Json POST "/payments/$($initiated.payment.id)/cash-confirm" @{} $adminToken
    if ($settled.payment.status -ne "SUCCESS" -or $settled.invoice.status -ne "PAID") {
        throw "P4.4 fixture payment was not settled"
    }
    return [pscustomobject]@{ Invoice = $invoice; Payment = $settled.payment }
}

Write-Host "SSE Finance P4.4 smoke against $BaseUrl"
$adminToken = Login $AdminUsername $AdminPassword
$approverToken = Login $ApproverUsername $ApproverPassword
$parentToken = Login $ParentUsername $ParentPassword
$teacherToken = Login "gv.toan" "teacher@123"
$makerAdmin = Invoke-Json GET "/me" $null $adminToken
$approverAdmin = Invoke-Json GET "/me" $null $approverToken
if ($makerAdmin.id -eq $approverAdmin.id -or $makerAdmin.role -ne "ADMIN" -or $approverAdmin.role -ne "ADMIN") {
    throw "P4.5 requires two different active Admin accounts"
}
$adminUsers = @(As-Array (Invoke-Json GET "/users?role=ADMIN" $null $adminToken))
if (-not ($adminUsers | Where-Object { $_.id -eq $makerAdmin.id }) -or
        -not ($adminUsers | Where-Object { $_.id -eq $approverAdmin.id })) {
    throw "The two maker-checker Admin accounts are not available"
}
$suffix = Get-Date -Format "yyyyMMddHHmmssfff"
Write-Host "[OK] maker-checker Admin accounts are different users"

$fixture = New-PaidFixture "A-$suffix" 210003
$invoice = $fixture.Invoice
$payment = $fixture.Payment
Write-Host "[OK] isolated paid invoice fixture created: $($invoice.code)"

$partial = Invoke-Json POST "/payments/$($payment.id)/refunds" @{
    amount = 70001
    reason = "P4.4 partial refund"
} $adminToken
if ($partial.status -ne "REQUESTED" -or $partial.refundType -ne "PARTIAL" -or
        $partial.paymentAmount -ne 210003 -or $partial.refundedAmountBefore -ne 0) {
    throw "Partial refund request did not persist its starting snapshot"
}
$reference = "P44-REF-$suffix"
Invoke-Json POST "/payment-refunds/$($partial.id)/approve" @{
    method = "MB_BANK_TRANSFER"
    reference = $reference
} $adminToken @(409) | Out-Null
Invoke-Json POST "/payment-refunds/$($partial.id)/approve" @{
    method = "MB_BANK_TRANSFER"
    reference = ""
} $approverToken @(400) | Out-Null
$approvedPartial = Invoke-Json POST "/payment-refunds/$($partial.id)/approve" @{
    method = "MB_BANK_TRANSFER"
    reference = $reference
} $approverToken
$replayedPartial = Invoke-Json POST "/payment-refunds/$($partial.id)/approve" @{
    method = "MB_BANK_TRANSFER"
    reference = $reference
} $approverToken
if ($approvedPartial.status -ne "COMPLETED" -or $approvedPartial.refundType -ne "PARTIAL" -or
        $approvedPartial.refundedAmountBefore -ne 0 -or $approvedPartial.refundedAmountAfter -ne 70001 -or
        $approvedPartial.invoicePaidAmountBefore -ne 210003 -or $approvedPartial.invoicePaidAmountAfter -ne 140002 -or
        $approvedPartial.invoiceStatusBefore -ne "PAID" -or $approvedPartial.invoiceStatusAfter -ne "PARTIAL" -or
        $approvedPartial.requestedBy -ne $makerAdmin.id -or $approvedPartial.approvedBy -ne $approverAdmin.id -or
        $replayedPartial.id -ne $approvedPartial.id) {
    throw "Partial refund snapshots or approval idempotency are incorrect"
}
$afterPartial = (Invoke-Json GET "/invoices/$($invoice.id)" $null $adminToken).invoice
if ($afterPartial.paidAmount -ne 140002 -or $afterPartial.status -ne "PARTIAL") {
    throw "Partial refund did not reduce the invoice exactly once"
}
Write-Host "[OK] self-approval blocked; independent Admin approved partial refund"

$full = Invoke-Json POST "/payments/$($payment.id)/refunds" @{
    amount = 140002
    reason = "P4.4 refund all remaining funds"
} $adminToken
if ($full.refundType -ne "FULL") { throw "Remaining full refund was not classified as FULL" }
$approvedFull = Invoke-Json POST "/payment-refunds/$($full.id)/approve" @{
    method = "CASH"
    reference = $null
} $approverToken
if ($approvedFull.status -ne "COMPLETED" -or $approvedFull.refundType -ne "FULL" -or
        $approvedFull.refundedAmountBefore -ne 70001 -or $approvedFull.refundedAmountAfter -ne 210003 -or
        $approvedFull.invoicePaidAmountBefore -ne 140002 -or $approvedFull.invoicePaidAmountAfter -ne 0 -or
        $approvedFull.invoiceStatusAfter -ne "PENDING" -or
        $approvedFull.requestedBy -ne $makerAdmin.id -or $approvedFull.approvedBy -ne $approverAdmin.id) {
    throw "Full remaining refund snapshots are incorrect"
}
$afterFull = (Invoke-Json GET "/invoices/$($invoice.id)" $null $adminToken).invoice
$history = As-Array (Invoke-Json GET "/payment-history" $null $adminToken)
$historyRow = $history | Where-Object { $_.paymentId -eq $payment.id } | Select-Object -First 1
if ($afterFull.paidAmount -ne 0 -or $historyRow.status -ne "REVERSED" -or
        $historyRow.refundedAmount -ne 210003 -or $historyRow.netAmount -ne 0) {
    throw "Full refund did not reverse the payment and zero the net amount"
}
Invoke-Json POST "/payments/$($payment.id)/refunds" @{
    amount = 1
    reason = "Over-refund probe"
} $adminToken @(409) | Out-Null
Write-Host "[OK] full remaining refund reverses payment and blocks over-refund"

$duplicateFixture = New-PaidFixture "B-$suffix" 50003
$duplicateRequest = Invoke-Json POST "/payments/$($duplicateFixture.Payment.id)/refunds" @{
    amount = 10001
    reason = "Duplicate reference probe"
} $adminToken
Invoke-Json POST "/payment-refunds/$($duplicateRequest.id)/reject" @{
    reason = "Self-rejection probe"
} $adminToken @(409) | Out-Null
Invoke-Json POST "/payment-refunds/$($duplicateRequest.id)/approve" @{
    method = "MB_BANK_TRANSFER"
    reference = $reference.ToLowerInvariant()
} $approverToken @(409) | Out-Null
$duplicateInvoice = (Invoke-Json GET "/invoices/$($duplicateFixture.Invoice.id)" $null $adminToken).invoice
if ($duplicateInvoice.paidAmount -ne 50003 -or $duplicateInvoice.status -ne "PAID") {
    throw "Duplicate reference attempt changed the second invoice"
}
Invoke-Json POST "/payment-refunds/$($duplicateRequest.id)/cancel" @{
    reason = "End duplicate reference probe"
} $adminToken | Out-Null
Write-Host "[OK] bank reference is mandatory and cannot be reused case-insensitively"

$parentRefunds = As-Array (Invoke-Json GET "/payment-refunds?studentId=$StudentId" $null $parentToken)
if (-not ($parentRefunds | Where-Object { $_.id -eq $partial.id }) -or
        -not ($parentRefunds | Where-Object { $_.id -eq $full.id })) {
    throw "Parent cannot view completed refunds for their child"
}
Invoke-Json GET "/payment-refunds" $null $teacherToken @(403) | Out-Null
$auditLogs = As-Array (Invoke-Json GET "/audit-logs?action=APPROVE_REFUND" $null $adminToken)
$partialAudit = $auditLogs | Where-Object { $_.entityId -eq $partial.id } | Select-Object -First 1
$fullAudit = $auditLogs | Where-Object { $_.entityId -eq $full.id } | Select-Object -First 1
if (-not $partialAudit -or -not $fullAudit -or
        $partialAudit.actorId -ne $approverAdmin.id -or $fullAudit.actorId -ne $approverAdmin.id -or
        $partialAudit.detail -notmatch "type=PARTIAL" -or $partialAudit.detail -notmatch "invoicePaid=210003->140002" -or
        $fullAudit.detail -notmatch "type=FULL" -or $fullAudit.detail -notmatch "invoicePaid=140002->0") {
    throw "P4.4 approval audit snapshots were not found"
}
Write-Host "[OK] parent access, role protection and two-Admin audit trail"
Write-Host "SSE Finance P4.4 smoke completed successfully."
