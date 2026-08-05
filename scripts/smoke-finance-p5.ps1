param(
    [string]$BaseUrl = "http://127.0.0.1:4000",
    [string]$AdminUsername = "admin",
    [string]$AdminPassword = "admin@123",
    [string]$ApproverUsername = "admin.finance",
    [string]$ApproverPassword = "admin2@123",
    [string]$StudentId = "u-s-minh",
    [string]$OutputDir = ""
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

function Invoke-Download {
    param(
        [string]$Path,
        [string]$Token,
        [string]$Destination
    )
    $response = Invoke-WebRequest -Method GET -Uri "$BaseUrl$Path" -UseBasicParsing `
        -Headers @{ Authorization = "Bearer $Token" }
    $stream = $response.RawContentStream
    if ($stream.CanSeek) { $stream.Position = 0 }
    $target = [System.IO.File]::Open($Destination, [System.IO.FileMode]::Create)
    try { $stream.CopyTo($target) } finally { $target.Dispose() }
    return [pscustomobject]@{
        ContentType = [string]$response.Headers["Content-Type"]
        Disposition = [string]$response.Headers["Content-Disposition"]
        Length = (Get-Item -LiteralPath $Destination).Length
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

function Assert-Equal {
    param([object]$Actual, [object]$Expected, [string]$Label)
    if ($Actual -ne $Expected) {
        throw "$Label is incorrect. Expected '$Expected', got '$Actual'"
    }
}

Write-Host "SSE Finance P5 smoke against $BaseUrl"
$adminToken = Login $AdminUsername $AdminPassword
$approverToken = Login $ApproverUsername $ApproverPassword
$teacherToken = Login "gv.toan" "teacher@123"
$admin = Invoke-Json GET "/me" $null $adminToken
$approver = Invoke-Json GET "/me" $null $approverToken
if ($admin.role -ne "ADMIN" -or $approver.role -ne "ADMIN" -or $admin.id -eq $approver.id) {
    throw "P5 smoke requires two different active Admin accounts"
}

$suffix = Get-Date -Format "yyyyMMddHHmmssfff"
$today = Get-Date -Format "yyyy-MM-dd"
$periodId = "fp-p5-$suffix"
$invoiceAmount = 330007
$refundAmount = 30007
$expectedPaid = $invoiceAmount - $refundAmount

Invoke-Json POST "/fee-periods" @{
    id = $periodId
    code = "P5-$suffix"
    name = "P5 finance report smoke $suffix"
    targetType = "STUDENT"
    targetIds = @($StudentId)
    dueDate = (Get-Date).AddDays(-1).ToString("yyyy-MM-dd")
} $adminToken | Out-Null
Invoke-Json POST "/fee-periods/$periodId/items" @{
    id = "fpi-p5-$suffix"
    name = "P5 exact report fixture"
    amount = $invoiceAmount
    targetType = "ALL"
    targetIds = @()
} $adminToken | Out-Null
Invoke-Json POST "/fee-periods/$periodId/open" @{} $adminToken | Out-Null
$created = @(As-Array (Invoke-Json POST "/fee-periods/$periodId/generate-invoices" @{} $adminToken))
Assert-Equal $created.Count 1 "Generated invoice count"
$invoice = $created[0]
Assert-Equal $invoice.status "OVERDUE" "Initial overdue status"

$initiated = Invoke-Json POST "/payments" @{ invoiceId = $invoice.id; method = "CASH" } $adminToken
$settled = Invoke-Json POST "/payments/$($initiated.payment.id)/cash-confirm" @{} $adminToken
Assert-Equal $settled.payment.status "SUCCESS" "Settled payment status"

$refund = Invoke-Json POST "/payments/$($settled.payment.id)/refunds" @{
    amount = $refundAmount
    reason = "P5 net revenue report verification"
} $adminToken
$approved = Invoke-Json POST "/payment-refunds/$($refund.id)/approve" @{
    method = "CASH"
    reference = $null
} $approverToken
Assert-Equal $approved.status "COMPLETED" "Refund status"
Assert-Equal $approved.invoicePaidAmountAfter $expectedPaid "Invoice amount after refund"
Write-Host "[OK] isolated overdue invoice, payment and partial refund created"

$scope = "fromDate=$today&toDate=$today&feePeriodId=$periodId"
$report = Invoke-Json GET "/reports/finance?$scope" $null $adminToken
$summary = $report.summary
Assert-Equal $summary.invoiceCount 1 "Invoice count"
Assert-Equal $summary.paidInvoiceCount 0 "Paid invoice count"
Assert-Equal $summary.outstandingInvoiceCount 1 "Outstanding invoice count"
Assert-Equal $summary.overdueInvoiceCount 1 "Overdue invoice count"
Assert-Equal $summary.totalReceivable $invoiceAmount "Total receivable"
Assert-Equal $summary.currentPaidAmount $expectedPaid "Current paid amount"
Assert-Equal $summary.outstandingAmount $refundAmount "Outstanding amount"
Assert-Equal $summary.overdueAmount $refundAmount "Overdue amount"
Assert-Equal $summary.paymentCount 1 "Payment count"
Assert-Equal $summary.grossCollected $invoiceAmount "Gross collected"
Assert-Equal $summary.refundCount 1 "Refund count"
Assert-Equal $summary.refundAmount $refundAmount "Refund amount"
Assert-Equal $summary.netRevenue $expectedPaid "Net revenue"

$methodRow = @(As-Array $report.byMethod | Where-Object { $_.method -eq "CASH" })
Assert-Equal $methodRow.Count 1 "CASH method row count"
Assert-Equal $methodRow[0].netRevenue $expectedPaid "CASH net revenue"
Assert-Equal @(As-Array $report.debtByFeePeriod).Count 1 "Fee-period debt group count"
Assert-Equal @(As-Array $report.debtByGrade).Count 1 "Grade debt group count"
Assert-Equal @(As-Array $report.debtByClass).Count 1 "Class debt group count"
Assert-Equal @(As-Array $report.debts).Count 1 "Debt detail count"
Assert-Equal $report.debts[0].overdue $true "Debt overdue marker"
Write-Host "[OK] receivable, gross, refund, net revenue and overdue debt totals are exact"

$cashReport = Invoke-Json GET "/reports/finance?$scope&method=CASH" $null $adminToken
Assert-Equal $cashReport.summary.netRevenue $expectedPaid "Filtered CASH net revenue"
$otherMethod = Invoke-Json GET "/reports/finance?$scope&method=MOMO" $null $adminToken
Assert-Equal $otherMethod.summary.grossCollected 0 "MOMO gross amount"
Assert-Equal $otherMethod.summary.refundAmount 0 "MOMO refund amount"
Assert-Equal $otherMethod.summary.outstandingAmount $refundAmount "MOMO-filtered current debt"
Write-Host "[OK] method filtering affects cash flow while preserving current debt scope"

$tomorrow = (Get-Date).AddDays(1).ToString("yyyy-MM-dd")
Invoke-Json GET "/reports/finance?fromDate=$today&toDate=$tomorrow" $null $adminToken @(400) | Out-Null
Invoke-Json GET "/reports/finance?fromDate=$today&toDate=$today&method=CRYPTO" $null $adminToken @(400) | Out-Null
Invoke-Json GET "/reports/finance?$scope" $null $teacherToken @(403) | Out-Null
Invoke-Json GET "/reports/finance/export?format=PDF&$scope" $null $teacherToken @(403) | Out-Null
Write-Host "[OK] date/method validation and Admin-only access"

if ([string]::IsNullOrWhiteSpace($OutputDir)) {
    $OutputDir = Join-Path (Split-Path -Parent $PSScriptRoot) "services\app\target\p5-smoke"
}
New-Item -ItemType Directory -Force -Path $OutputDir | Out-Null
$xlsxPath = Join-Path $OutputDir "finance-p5-$suffix.xlsx"
$pdfPath = Join-Path $OutputDir "finance-p5-$suffix.pdf"
$xlsx = Invoke-Download "/reports/finance/export?format=XLSX&$scope" $adminToken $xlsxPath
$pdf = Invoke-Download "/reports/finance/export?format=PDF&$scope" $adminToken $pdfPath
$xlsxBytes = [System.IO.File]::ReadAllBytes($xlsxPath)
$pdfBytes = [System.IO.File]::ReadAllBytes($pdfPath)
if ($xlsx.Length -lt 5000 -or $xlsxBytes[0] -ne 0x50 -or $xlsxBytes[1] -ne 0x4B -or
        $xlsx.ContentType -notmatch "spreadsheetml" -or $xlsx.Disposition -notmatch "\.xlsx") {
    throw "The XLSX export is missing, too small or has invalid headers/signature"
}
$pdfSignature = [System.Text.Encoding]::ASCII.GetString($pdfBytes, 0, 4)
if ($pdf.Length -lt 10000 -or $pdfSignature -ne "%PDF" -or
        $pdf.ContentType -notmatch "application/pdf" -or $pdf.Disposition -notmatch "\.pdf") {
    throw "The PDF export is missing, too small or has invalid headers/signature"
}
Write-Host "[OK] valid XLSX and PDF files exported to $OutputDir"

$auditLogs = @(As-Array (Invoke-Json GET "/audit-logs?action=EXPORT" $null $adminToken))
$reportExports = @($auditLogs | Where-Object {
    $_.actorId -eq $admin.id -and $_.module -eq "reports" -and
    $_.entityType -eq "finance_report" -and
    ($_.entityId -match "\.xlsx$" -or $_.entityId -match "\.pdf$")
})
$xlsxAudit = $reportExports | Where-Object { $_.entityId -match "\.xlsx$" } | Select-Object -First 1
$pdfAudit = $reportExports | Where-Object { $_.entityId -match "\.pdf$" } | Select-Object -First 1
if (-not $xlsxAudit -or -not $pdfAudit -or
        $xlsxAudit.detail -notmatch "format=XLSX" -or $pdfAudit.detail -notmatch "format=PDF") {
    throw "Finance report export audit entries were not found"
}
Write-Host "[OK] every export is recorded in Admin audit logs"

Write-Host "SSE Finance P5 smoke completed successfully."
Write-Host "XLSX: $xlsxPath"
Write-Host "PDF : $pdfPath"
