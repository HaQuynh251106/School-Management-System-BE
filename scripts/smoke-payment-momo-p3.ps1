param(
    [Parameter(Mandatory = $true)]
    [string]$InvoiceId,
    [Parameter(Mandatory = $true)]
    [string]$PartnerCode,
    [Parameter(Mandatory = $true)]
    [string]$AccessKey,
    [Parameter(Mandatory = $true)]
    [string]$SecretKey,
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

function Invoke-MomoIpn([System.Collections.IDictionary]$Payload) {
    $response = Invoke-WebRequest -Method POST -Uri "$BaseUrl/payments/momo/ipn" `
        -ContentType "application/json" -UseBasicParsing `
        -Body ($Payload | ConvertTo-Json -Depth 10)
    if ([int]$response.StatusCode -ne 204) {
        throw "MoMo IPN must return HTTP 204, received $($response.StatusCode)"
    }
}

function Login([string]$Username, [string]$Password) {
    return Invoke-Api POST "/auth/login" @{ username = $Username; password = $Password }
}

function New-HmacSha256([string]$Data, [string]$Secret) {
    $hmac = [System.Security.Cryptography.HMACSHA256]::new(
        [System.Text.Encoding]::UTF8.GetBytes($Secret))
    try {
        $hash = $hmac.ComputeHash([System.Text.Encoding]::UTF8.GetBytes($Data))
        return ([System.BitConverter]::ToString($hash)).Replace("-", "").ToLowerInvariant()
    } finally {
        $hmac.Dispose()
    }
}

function New-CreateSignatureData($Request) {
    return "accessKey=$AccessKey&amount=$($Request.amount)&extraData=$($Request.extraData)" +
        "&ipnUrl=$($Request.ipnUrl)&orderId=$($Request.orderId)&orderInfo=$($Request.orderInfo)" +
        "&partnerCode=$($Request.partnerCode)&redirectUrl=$($Request.redirectUrl)" +
        "&requestId=$($Request.requestId)&requestType=$($Request.requestType)"
}

function New-CallbackSignatureData([System.Collections.IDictionary]$Payload) {
    return "accessKey=$AccessKey&amount=$($Payload['amount'])&extraData=$($Payload['extraData'])" +
        "&message=$($Payload['message'])&orderId=$($Payload['orderId'])&orderInfo=$($Payload['orderInfo'])" +
        "&orderType=$($Payload['orderType'])&partnerCode=$($Payload['partnerCode'])" +
        "&payType=$($Payload['payType'])&requestId=$($Payload['requestId'])" +
        "&responseTime=$($Payload['responseTime'])&resultCode=$($Payload['resultCode'])" +
        "&transId=$($Payload['transId'])"
}

function ConvertTo-Query([System.Collections.IDictionary]$Payload) {
    return (($Payload.GetEnumerator() | Sort-Object Key | ForEach-Object {
        [System.Net.WebUtility]::UrlEncode([string]$_.Key) + "=" +
            [System.Net.WebUtility]::UrlEncode([string]$_.Value)
    }) -join "&")
}

function Copy-Payload([System.Collections.IDictionary]$Payload) {
    $copy = [ordered]@{}
    foreach ($entry in $Payload.GetEnumerator()) { $copy[$entry.Key] = $entry.Value }
    return $copy
}

$parent = Login $ParentUsername $ParentPassword
$admin = Login $AdminUsername $AdminPassword
$before = Invoke-Api GET "/invoices/$InvoiceId" $null $parent.accessToken
$beforePaid = [long]$before.invoice.paidAmount

$initiated = Invoke-Api POST "/payments" @{ invoiceId = $InvoiceId; method = "MOMO" } $parent.accessToken
if ($initiated.payment.status -ne "PENDING") { throw "New MoMo payment must be PENDING" }
if ([long]$initiated.invoice.paidAmount -ne $beforePaid) { throw "Payment initiation changed invoice amount" }
if ($initiated.paymentUrl -notmatch '^https://([a-z0-9-]+\.)*momo\.vn/') {
    throw "Real MoMo adapter is not enabled or create API did not return a MoMo payUrl."
}

$logs = @(Invoke-Api GET "/payments/$($initiated.payment.id)/gateway-transactions" $null $admin.accessToken)
if ($logs.Count -ne 1) { throw "Expected one initial MoMo gateway transaction" }
$createRequest = $logs[0].requestPayload | ConvertFrom-Json
$expectedCreateSignature = New-HmacSha256 (New-CreateSignatureData $createRequest) $SecretKey
if ($createRequest.signature -ne $expectedCreateSignature) { throw "MoMo create request signature is invalid" }
if ($createRequest.partnerCode -ne $PartnerCode) { throw "MoMo create request uses another partnerCode" }

$callback = [ordered]@{
    partnerCode = $PartnerCode
    orderId = [string]$initiated.payment.txnRef
    requestId = [string]$initiated.payment.txnRef
    amount = [string]$initiated.payment.amount
    orderInfo = [string]$createRequest.orderInfo
    orderType = "momo_wallet"
    transId = [string]([DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds())
    resultCode = $(if ($CallbackStatus -eq "SUCCESS") { "0" } else { "1006" })
    message = $(if ($CallbackStatus -eq "SUCCESS") { "Successful." } else { "Transaction failed." })
    payType = "qr"
    responseTime = [string]([DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds())
    extraData = ""
}
$callback['signature'] = New-HmacSha256 (New-CallbackSignatureData $callback) $SecretKey

$returnBefore = Invoke-Api GET "/payments/momo/return?$(ConvertTo-Query $callback)"
if ($returnBefore.signatureValid -ne $true -or $returnBefore.status -ne "PENDING") {
    throw "MoMo browser return validation failed or mutated the payment"
}

$invalid = Copy-Payload $callback
$invalid['signature'] = "invalid"
Invoke-MomoIpn $invalid

$wrongAmount = Copy-Payload $callback
$wrongAmount['amount'] = [string]([long]$callback['amount'] + 1)
$wrongAmount['signature'] = New-HmacSha256 (New-CallbackSignatureData $wrongAmount) $SecretKey
Invoke-MomoIpn $wrongAmount

$wrongMerchant = Copy-Payload $callback
$wrongMerchant['partnerCode'] = "OTHERPARTNER"
$wrongMerchant['signature'] = New-HmacSha256 (New-CallbackSignatureData $wrongMerchant) $SecretKey
Invoke-MomoIpn $wrongMerchant

$pending = Invoke-Api GET "/payments/$($initiated.payment.id)" $null $parent.accessToken
if ($pending.status -ne "PENDING") { throw "Rejected MoMo IPN changed payment status" }

Invoke-MomoIpn $callback
for ($attempt = 1; $attempt -le $ReplayCount; $attempt++) { Invoke-MomoIpn $callback }

$after = Invoke-Api GET "/invoices/$InvoiceId" $null $parent.accessToken
$payment = Invoke-Api GET "/payments/$($initiated.payment.id)" $null $parent.accessToken
$logs = @(Invoke-Api GET "/payments/$($initiated.payment.id)/gateway-transactions" $null $admin.accessToken)
$expectedCallbackCount = $ReplayCount + 4
if ($logs.Count -ne 1 -or $logs[0].callbackCount -ne $expectedCallbackCount) {
    throw "Expected callbackCount $expectedCallbackCount"
}

if ($CallbackStatus -eq "SUCCESS") {
    $expectedPaid = $beforePaid + [long]$initiated.payment.amount
    if ([long]$after.invoice.paidAmount -ne $expectedPaid) { throw "Invoice was not credited exactly once" }
} elseif ([long]$after.invoice.paidAmount -ne $beforePaid) {
    throw "Failed MoMo IPN changed invoice amount"
}

$returnAfter = Invoke-Api GET "/payments/momo/return?$(ConvertTo-Query $callback)"
[pscustomobject]@{
    invoiceId = $InvoiceId
    paymentId = $payment.id
    txnRef = $payment.txnRef
    paymentUrlHost = ([Uri]$initiated.paymentUrl).Host
    requestSignature = "VALID"
    initialStatus = "PENDING"
    finalPaymentStatus = $payment.status
    finalInvoiceStatus = $after.invoice.status
    paidAmountBefore = $beforePaid
    paidAmountAfter = [long]$after.invoice.paidAmount
    rejectedIpnCount = 3
    replayCount = $ReplayCount
    callbackCount = $logs[0].callbackCount
    browserReturnBefore = $returnBefore.status
    browserReturnAfter = $returnAfter.status
} | Format-List
