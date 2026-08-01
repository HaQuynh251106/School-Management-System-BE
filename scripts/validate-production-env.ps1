param(
    [Parameter(Mandatory = $true)]
    [string]$EnvFile
)

$required = @(
    'SSE_DB_PASSWORD',
    'SSE_JWT_SECRET',
    'SSE_CORS_ALLOWED_ORIGINS',
    'SSE_PASSWORD_RESET_URL',
    'SSE_VIETQR_BANK_ID',
    'SSE_VIETQR_ACCOUNT_NO',
    'SSE_VIETQR_ACCOUNT_NAME'
)

if (-not (Test-Path -LiteralPath $EnvFile)) {
    throw "Không tìm thấy file môi trường: $EnvFile"
}

$values = @{}
Get-Content -LiteralPath $EnvFile -Encoding UTF8 | ForEach-Object {
    $line = $_.Trim()
    if (-not $line -or $line.StartsWith('#') -or -not $line.Contains('=')) { return }
    $key, $value = $line.Split('=', 2)
    $values[$key.Trim()] = $value.Trim()
}

$missing = @($required | Where-Object {
    -not $values.ContainsKey($_) -or
    [string]::IsNullOrWhiteSpace($values[$_]) -or
    $values[$_] -match 'replace-with|example\.com'
})
if ($missing.Count -gt 0) {
    throw "Thiếu hoặc chưa thay giá trị production: $($missing -join ', ')"
}
if ($values['SSE_JWT_SECRET'].Length -lt 32) {
    throw 'SSE_JWT_SECRET phải có ít nhất 32 ký tự.'
}
if ($values['SSE_PAYMENT_MODE'] -ne 'vietqr') {
    throw 'SSE_PAYMENT_MODE phải là vietqr.'
}
if ($values['SSE_VIETQR_ACCOUNT_NO'] -notmatch '^\d{6,19}$') {
    throw 'SSE_VIETQR_ACCOUNT_NO phải gồm 6 đến 19 chữ số.'
}
if ($values['SSE_CORS_ALLOWED_ORIGINS'] -notmatch '^https://') {
    throw 'Production CORS phải chứa URL HTTPS.'
}

Write-Output 'Production environment validation passed.'
