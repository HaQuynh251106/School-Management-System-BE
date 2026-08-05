param(
    [Parameter(Mandatory = $true)]
    [string]$InvoiceId,
    [string]$BaseUrl = "http://127.0.0.1:4000",
    [string]$ParentUsername = "ph.nguyen",
    [string]$ParentPassword = "parent@123",
    [string]$AdminUsername = "admin",
    [string]$AdminPassword = "admin@123",
    [string]$PaymentSandboxSecret = "dev-payment-secret-change-me",
    [ValidateSet("SUCCESS", "FAILED")]
    [string]$CallbackStatus = "SUCCESS",
    [ValidateRange(1, 20)]
    [int]$ReplayCount = 10
)

$ErrorActionPreference = "Stop"

if ([string]::IsNullOrWhiteSpace($InvoiceId) -or
        $InvoiceId -match '^ID_INVOICE' -or
        $InvoiceId -match '^<.*>$') {
    throw "InvoiceId is a placeholder. Pass a real invoice ID from sse_db, for example: -InvoiceId inv-xxxxxxxxxx"
}

function Invoke-Api {
    param(
        [ValidateSet("GET", "POST")]
        [string]$Method,
        [string]$Path,
        [object]$Body = $null,
        [string]$Token = $null
    )
    $headers = @{}
    if ($Token) { $headers.Authorization = "Bearer $Token" }
    $params = @{
        Method = $Method
        Uri = "$BaseUrl$Path"
        ContentType = "application/json"
        UseBasicParsing = $true
    }
    if ($headers.Count -gt 0) { $params.Headers = $headers }
    if ($null -ne $Body) { $params.Body = $Body | ConvertTo-Json -Depth 10 }
    try {
        $response = Invoke-WebRequest @params
    } catch {
        $statusCode = $null
        $responseBody = $null
        if ($_.Exception.Response) {
            $statusCode = [int]$_.Exception.Response.StatusCode
            try {
                $reader = [System.IO.StreamReader]::new($_.Exception.Response.GetResponseStream())
                try { $responseBody = $reader.ReadToEnd() } finally { $reader.Dispose() }
            } catch {
                $responseBody = $null
            }
        }
        $detail = if ([string]::IsNullOrWhiteSpace($responseBody)) {
            $_.Exception.Message
        } else {
            $responseBody
        }
        throw "API $Method $Path failed with HTTP $statusCode. $detail"
    }
    if ([string]::IsNullOrWhiteSpace($response.Content)) { return $null }
    return $response.Content | ConvertFrom-Json
}

function Login([string]$Username, [string]$Password) {
    return Invoke-Api POST "/auth/login" @{ username = $Username; password = $Password }
}

function New-HmacSignature {
    param([System.Collections.IDictionary]$Payload, [string]$Secret)
    $canonical = (($Payload.GetEnumerator() | Sort-Object Key | ForEach-Object {
        "$($_.Key)=$($_.Value)"
    }) -join "&")
    $hmac = [System.Security.Cryptography.HMACSHA256]::new(
        [System.Text.Encoding]::UTF8.GetBytes($Secret))
    try {
        $hash = $hmac.ComputeHash([System.Text.Encoding]::UTF8.GetBytes($canonical))
        return ([System.BitConverter]::ToString($hash)).Replace("-", "").ToLowerInvariant()
    } finally {
        $hmac.Dispose()
    }
}

$parent = Login $ParentUsername $ParentPassword
$admin = Login $AdminUsername $AdminPassword
$before = Invoke-Api GET "/invoices/$InvoiceId" $null $parent.accessToken
$beforePaid = [long]$before.invoice.paidAmount

$initiated = Invoke-Api POST "/payments" @{ invoiceId = $InvoiceId; method = "VNPAY" } $parent.accessToken
if ($initiated.payment.status -ne "PENDING") { throw "Payment mới không ở trạng thái PENDING" }
if ([long]$initiated.invoice.paidAmount -ne $beforePaid) { throw "Tạo payment đã làm thay đổi invoice" }

$returnBefore = Invoke-Api GET "/payments/vnpay/return?paymentId=$($initiated.payment.id)"
if ($returnBefore.status -ne "PENDING") { throw "Browser return đã làm thay đổi payment" }

$callback = [ordered]@{
    provider = "VNPAY"
    txnRef = [string]$initiated.payment.txnRef
    amount = [string]$initiated.payment.amount
    status = $CallbackStatus
    providerTransactionId = "P2-TEST-$([guid]::NewGuid().ToString('N'))"
    responseCode = $(if ($CallbackStatus -eq "SUCCESS") { "00" } else { "24" })
}
$invalid = [ordered]@{}
foreach ($entry in $callback.GetEnumerator()) { $invalid[$entry.Key] = $entry.Value }
$invalid.signature = "invalid"
$invalidResult = Invoke-Api POST "/payments/vnpay/ipn" $invalid
if ($invalidResult.accepted -or $invalidResult.processed) { throw "Callback sai chữ ký đã được chấp nhận" }

$pending = Invoke-Api GET "/payments/$($initiated.payment.id)" $null $parent.accessToken
if ($pending.status -ne "PENDING") { throw "Callback sai chữ ký đã thay đổi payment" }

$callback.signature = New-HmacSignature $callback $PaymentSandboxSecret
$processed = Invoke-Api POST "/payments/vnpay/ipn" $callback
if ((-not $processed.accepted) -or
        (-not $processed.processed) -or
        ($processed.paymentStatus -ne $CallbackStatus)) {
    throw "Callback có chữ ký hợp lệ không được xử lý đúng"
}

for ($attempt = 1; $attempt -le $ReplayCount; $attempt++) {
    $replayed = Invoke-Api POST "/payments/vnpay/ipn" $callback
    if (-not $replayed.accepted -or $replayed.processed) {
        throw "Callback lặp lần $attempt đã bị xử lý lại"
    }
}

$after = Invoke-Api GET "/invoices/$InvoiceId" $null $parent.accessToken
$payment = Invoke-Api GET "/payments/$($initiated.payment.id)" $null $parent.accessToken
$logs = @(Invoke-Api GET "/payments/$($initiated.payment.id)/gateway-transactions" $null $admin.accessToken)
$expectedCallbackCount = $ReplayCount + 2
if ($logs.Count -ne 1 -or $logs[0].callbackCount -ne $expectedCallbackCount) {
    throw "Số lần callback không đúng: mong đợi $expectedCallbackCount"
}

if ($CallbackStatus -eq "SUCCESS") {
    $expectedPaid = $beforePaid + [long]$initiated.payment.amount
    if ([long]$after.invoice.paidAmount -ne $expectedPaid) {
        throw "Invoice không được cộng đúng một lần"
    }
} elseif ([long]$after.invoice.paidAmount -ne $beforePaid) {
    throw "Callback thất bại đã làm thay đổi invoice"
}

$returnAfter = Invoke-Api GET "/payments/vnpay/return?paymentId=$($initiated.payment.id)"
[pscustomobject]@{
    invoiceId = $InvoiceId
    paymentId = $payment.id
    txnRef = $payment.txnRef
    initialStatus = "PENDING"
    finalPaymentStatus = $payment.status
    finalInvoiceStatus = $after.invoice.status
    paidAmountBefore = $beforePaid
    paidAmountAfter = [long]$after.invoice.paidAmount
    callbackCount = $logs[0].callbackCount
    browserReturnBefore = $returnBefore.status
    browserReturnAfter = $returnAfter.status
} | Format-List
