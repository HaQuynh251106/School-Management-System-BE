param(
    [Parameter(Mandatory = $true)]
    [string]$InvoiceId,
    [Parameter(Mandatory = $true)]
    [string]$TmnCode,
    [Parameter(Mandatory = $true)]
    [string]$HashSecret,
    [string]$BaseUrl = "http://127.0.0.1:4000",
    [string]$ParentUsername = "ph.hoang",
    [string]$ParentPassword = "parent@123",
    [string]$AdminUsername = "admin",
    [string]$AdminPassword = "admin@123",
    [ValidateSet("SUCCESS", "FAILED")]
    [string]$CallbackStatus = "SUCCESS",
    [ValidateRange(1, 20)]
    [int]$ReplayCount = 10
)

$ErrorActionPreference = "Stop"

if ([string]::IsNullOrWhiteSpace($InvoiceId) -or $InvoiceId -match '^ID_INVOICE' -or $InvoiceId -match '^<.*>$') {
    throw "InvoiceId is a placeholder. Pass a real unpaid invoice ID."
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
            } catch { $responseBody = $null }
        }
        $detail = if ([string]::IsNullOrWhiteSpace($responseBody)) { $_.Exception.Message } else { $responseBody }
        throw "API $Method $Path failed with HTTP $statusCode. $detail"
    }
    if ([string]::IsNullOrWhiteSpace($response.Content)) { return $null }
    return $response.Content | ConvertFrom-Json
}

function Login([string]$Username, [string]$Password) {
    return Invoke-Api POST "/auth/login" @{ username = $Username; password = $Password }
}

function New-HmacSha512([string]$Data, [string]$Secret) {
    $hmac = [System.Security.Cryptography.HMACSHA512]::new(
        [System.Text.Encoding]::UTF8.GetBytes($Secret))
    try {
        $hash = $hmac.ComputeHash([System.Text.Encoding]::UTF8.GetBytes($Data))
        return ([System.BitConverter]::ToString($hash)).Replace("-", "").ToLowerInvariant()
    } finally {
        $hmac.Dispose()
    }
}

function ConvertTo-VnpQuery([System.Collections.IDictionary]$Payload, [switch]$IncludeHash) {
    $entries = $Payload.GetEnumerator() |
        Where-Object { $_.Key -like 'vnp_*' -and $_.Key -notin @('vnp_SecureHash', 'vnp_SecureHashType') } |
        Sort-Object Key
    $query = ($entries | ForEach-Object {
        [System.Net.WebUtility]::UrlEncode([string]$_.Key) + "=" +
            [System.Net.WebUtility]::UrlEncode([string]$_.Value)
    }) -join "&"
    if ($IncludeHash -and $Payload.Contains('vnp_SecureHash')) {
        $query += "&vnp_SecureHash=$($Payload['vnp_SecureHash'])"
    }
    return $query
}

$parent = Login $ParentUsername $ParentPassword
$admin = Login $AdminUsername $AdminPassword
$before = Invoke-Api GET "/invoices/$InvoiceId" $null $parent.accessToken
$beforePaid = [long]$before.invoice.paidAmount

$initiated = Invoke-Api POST "/payments" @{ invoiceId = $InvoiceId; method = "VNPAY" } $parent.accessToken
if ($initiated.payment.status -ne "PENDING") { throw "New VNPAY payment must be PENDING" }
if ([long]$initiated.invoice.paidAmount -ne $beforePaid) { throw "Payment initiation changed invoice amount" }
if ($initiated.paymentUrl -notmatch '^https://([a-z0-9-]+\.)*vnpayment\.vn/') {
    throw "Real VNPAY adapter is not enabled. Start backend with SSE_VNPAY_ENABLED=true and merchant credentials."
}

$paymentUri = [Uri]$initiated.paymentUrl
$segments = $paymentUri.Query.TrimStart('?').Split('&')
$secureHashSegment = $segments | Where-Object { $_ -like 'vnp_SecureHash=*' } | Select-Object -First 1
$requestHashData = ($segments | Where-Object { $_ -notlike 'vnp_SecureHash=*' }) -join '&'
$requestHash = [System.Net.WebUtility]::UrlDecode(($secureHashSegment -split '=', 2)[1])
if ((New-HmacSha512 $requestHashData $HashSecret) -ne $requestHash) {
    throw "Payment URL HMAC-SHA512 is invalid"
}
if ($initiated.paymentUrl -notmatch "vnp_TmnCode=$([regex]::Escape($TmnCode))") {
    throw "Payment URL contains a different vnp_TmnCode"
}

$callback = [ordered]@{
    vnp_Amount = [string]([long]$initiated.payment.amount * 100)
    vnp_BankCode = "NCB"
    vnp_PayDate = (Get-Date).ToString("yyyyMMddHHmmss")
    vnp_ResponseCode = $(if ($CallbackStatus -eq "SUCCESS") { "00" } else { "24" })
    vnp_TmnCode = $TmnCode
    vnp_TransactionNo = [string]([DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds())
    vnp_TransactionStatus = $(if ($CallbackStatus -eq "SUCCESS") { "00" } else { "02" })
    vnp_TxnRef = [string]$initiated.payment.txnRef
}
$callback['vnp_SecureHash'] = New-HmacSha512 (ConvertTo-VnpQuery $callback) $HashSecret
$signedQuery = ConvertTo-VnpQuery $callback -IncludeHash

$returnBefore = Invoke-Api GET "/payments/vnpay/return?$signedQuery"
if ($returnBefore.signatureValid -ne $true -or $returnBefore.status -ne "PENDING") {
    throw "Browser return validation failed or mutated the payment"
}

$invalid = [ordered]@{}
foreach ($entry in $callback.GetEnumerator()) { $invalid[$entry.Key] = $entry.Value }
$invalid['vnp_SecureHash'] = "invalid"
$invalidResult = Invoke-Api GET "/payments/vnpay/ipn?$(ConvertTo-VnpQuery $invalid -IncludeHash)"
if ($invalidResult.RspCode -ne "97") { throw "Invalid signature must return VNPAY RspCode 97" }

$pending = Invoke-Api GET "/payments/$($initiated.payment.id)" $null $parent.accessToken
if ($pending.status -ne "PENDING") { throw "Invalid IPN changed payment status" }

$wrongAmount = [ordered]@{}
foreach ($entry in $callback.GetEnumerator()) { $wrongAmount[$entry.Key] = $entry.Value }
$wrongAmount['vnp_Amount'] = [string]([long]$callback['vnp_Amount'] + 100)
$wrongAmount['vnp_SecureHash'] = New-HmacSha512 (ConvertTo-VnpQuery $wrongAmount) $HashSecret
$wrongAmountResult = Invoke-Api GET "/payments/vnpay/ipn?$(ConvertTo-VnpQuery $wrongAmount -IncludeHash)"
if ($wrongAmountResult.RspCode -ne "04") { throw "Signed amount mismatch must return VNPAY RspCode 04" }

$wrongMerchant = [ordered]@{}
foreach ($entry in $callback.GetEnumerator()) { $wrongMerchant[$entry.Key] = $entry.Value }
$wrongMerchant['vnp_TmnCode'] = "OTHERCOD"
$wrongMerchant['vnp_SecureHash'] = New-HmacSha512 (ConvertTo-VnpQuery $wrongMerchant) $HashSecret
$wrongMerchantResult = Invoke-Api GET "/payments/vnpay/ipn?$(ConvertTo-VnpQuery $wrongMerchant -IncludeHash)"
if ($wrongMerchantResult.RspCode -ne "99") { throw "Signed merchant mismatch must return VNPAY RspCode 99" }

$pending = Invoke-Api GET "/payments/$($initiated.payment.id)" $null $parent.accessToken
if ($pending.status -ne "PENDING") { throw "Rejected VNPAY IPN changed payment status" }

$processed = Invoke-Api GET "/payments/vnpay/ipn?$signedQuery"
if ($processed.RspCode -ne "00") { throw "Valid VNPAY IPN must return RspCode 00" }

for ($attempt = 1; $attempt -le $ReplayCount; $attempt++) {
    $replayed = Invoke-Api GET "/payments/vnpay/ipn?$signedQuery"
    if ($replayed.RspCode -ne "02") {
        throw "Replay $attempt must return VNPAY RspCode 02"
    }
}

$after = Invoke-Api GET "/invoices/$InvoiceId" $null $parent.accessToken
$payment = Invoke-Api GET "/payments/$($initiated.payment.id)" $null $parent.accessToken
$logs = @(Invoke-Api GET "/payments/$($initiated.payment.id)/gateway-transactions" $null $admin.accessToken)
$expectedCallbackCount = $ReplayCount + 4
if ($logs.Count -ne 1 -or $logs[0].callbackCount -ne $expectedCallbackCount) {
    throw "Expected callbackCount $expectedCallbackCount"
}

if ($CallbackStatus -eq "SUCCESS") {
    $expectedPaid = $beforePaid + [long]$initiated.payment.amount
    if ([long]$after.invoice.paidAmount -ne $expectedPaid) {
        throw "Invoice was not credited exactly once"
    }
} elseif ([long]$after.invoice.paidAmount -ne $beforePaid) {
    throw "Failed VNPAY IPN changed invoice amount"
}

$returnAfter = Invoke-Api GET "/payments/vnpay/return?$signedQuery"
[pscustomobject]@{
    invoiceId = $InvoiceId
    paymentId = $payment.id
    txnRef = $payment.txnRef
    paymentUrlHost = $paymentUri.Host
    requestSignature = "VALID"
    initialStatus = "PENDING"
    finalPaymentStatus = $payment.status
    finalInvoiceStatus = $after.invoice.status
    paidAmountBefore = $beforePaid
    paidAmountAfter = [long]$after.invoice.paidAmount
    invalidIpnRspCode = $invalidResult.RspCode
    invalidAmountRspCode = $wrongAmountResult.RspCode
    invalidMerchantRspCode = $wrongMerchantResult.RspCode
    validIpnRspCode = $processed.RspCode
    replayRspCode = "02 x $ReplayCount"
    callbackCount = $logs[0].callbackCount
    browserReturnBefore = $returnBefore.status
    browserReturnAfter = $returnAfter.status
} | Format-List
