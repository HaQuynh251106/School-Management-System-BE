param(
    [string]$EnvironmentFile = ".env.local"
)

$ErrorActionPreference = "Stop"
$root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$environmentPath = Join-Path $root $EnvironmentFile

if (-not (Test-Path -LiteralPath $environmentPath)) {
    throw "Khong tim thay $environmentPath. Hay sao chep .env.local.example thanh .env.local."
}

Get-Content -LiteralPath $environmentPath | ForEach-Object {
    $line = $_.Trim()
    if (-not $line -or $line.StartsWith("#")) { return }
    $parts = $line.Split("=", 2)
    if ($parts.Count -eq 2) {
        [Environment]::SetEnvironmentVariable($parts[0].Trim(), $parts[1].Trim(), "Process")
    }
}

foreach ($required in "SSE_DB_URL", "SSE_DB_USER", "SSE_DB_PASSWORD", "SSE_JWT_SECRET", "SSE_CORS_ALLOWED_ORIGINS") {
    if (-not [Environment]::GetEnvironmentVariable($required, "Process")) {
        throw "Bien $required chua duoc cau hinh trong $EnvironmentFile."
    }
}

if ($env:SSE_DB_URL -notmatch '^jdbc:postgresql://([^:/]+):(\d+)/([^?]+)') {
    throw "SSE_DB_URL phai co dang jdbc:postgresql://host:port/database."
}

$databaseHost = $Matches[1]
$databasePort = [int]$Matches[2]
$databaseName = $Matches[3]
$projectPostgresData = Join-Path $root "data\postgres"
$installedPostgresData = Join-Path $env:ProgramFiles "PostgreSQL\17\data"
$postgresData = if (Test-Path -LiteralPath (Join-Path $installedPostgresData "PG_VERSION")) {
    $installedPostgresData
} else {
    $projectPostgresData
}
$postgresLog = Join-Path $root "data\postgresql.log"

$postgresCandidates = @(
    (Get-Command pg_ctl.exe -ErrorAction SilentlyContinue | Select-Object -ExpandProperty Source -ErrorAction SilentlyContinue),
    "$env:ProgramFiles\PostgreSQL\17\bin\pg_ctl.exe",
    "$env:USERPROFILE\.codex\runtime\postgresql-17-full\pgsql\bin\pg_ctl.exe"
) | Where-Object { $_ -and (Test-Path -LiteralPath $_) }

if (-not $postgresCandidates) {
    throw "Khong tim thay PostgreSQL 17. Hay cai PostgreSQL roi chay lai script."
}

$pgBin = Split-Path -Parent $postgresCandidates[0]
$initdb = Join-Path $pgBin "initdb.exe"
$pgCtl = Join-Path $pgBin "pg_ctl.exe"
$psql = Join-Path $pgBin "psql.exe"
$createdb = Join-Path $pgBin "createdb.exe"

if (-not (Test-Path -LiteralPath (Join-Path $postgresData "PG_VERSION"))) {
    New-Item -ItemType Directory -Path (Split-Path $postgresData -Parent) -Force | Out-Null
    $passwordFile = [System.IO.Path]::GetTempFileName()
    try {
        [System.IO.File]::WriteAllText($passwordFile, $env:SSE_DB_PASSWORD + [Environment]::NewLine)
        & $initdb -D $postgresData -U $env:SSE_DB_USER --pwfile=$passwordFile --auth-host=scram-sha-256 --auth-local=scram-sha-256 --encoding=UTF8 --locale=C
        if ($LASTEXITCODE -ne 0) { throw "Khong the khoi tao PostgreSQL." }
    } finally {
        Remove-Item -LiteralPath $passwordFile -Force -ErrorAction SilentlyContinue
    }
}

$listener = Get-NetTCPConnection -LocalPort $databasePort -State Listen -ErrorAction SilentlyContinue | Select-Object -First 1
if (-not $listener) {
    & $pgCtl -D $postgresData -l $postgresLog -o "-p $databasePort -h $databaseHost" start
    if ($LASTEXITCODE -ne 0) { throw "Khong the khoi dong PostgreSQL tai cong $databasePort." }
}

$env:PGPASSWORD = $env:SSE_DB_PASSWORD
$databaseExists = & $psql -w -h $databaseHost -p $databasePort -U $env:SSE_DB_USER -d postgres -tAc "SELECT 1 FROM pg_database WHERE datname='$databaseName'"
if ($LASTEXITCODE -ne 0) { throw "Khong the xac thuc voi PostgreSQL." }
if ($databaseExists.Trim() -ne "1") {
    & $createdb -w -h $databaseHost -p $databasePort -U $env:SSE_DB_USER $databaseName
    if ($LASTEXITCODE -ne 0) { throw "Khong the tao database $databaseName." }
}

$jar = Join-Path $root "services\app\target\sse-app.jar"
if (-not (Test-Path -LiteralPath $jar)) {
    throw "Chua co $jar. Hay dong goi Backend truoc khi chay."
}

Write-Host "PostgreSQL: $databaseHost`:$databasePort/$databaseName"
Write-Host "Backend: http://127.0.0.1:4000"
& java.exe -jar $jar --spring.profiles.active=local
