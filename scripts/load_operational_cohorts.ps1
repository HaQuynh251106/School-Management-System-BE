param(
    [string]$EnvFile = '.env.local'
)

$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
$envPath = Join-Path $repo $EnvFile
$sqlPath = Join-Path $PSScriptRoot 'seed_operational_cohorts_2023_2029.sql'

if (-not (Test-Path -LiteralPath $envPath)) {
    throw "Environment file not found: $envPath"
}
if (-not (Test-Path -LiteralPath $sqlPath)) {
    throw "SQL file not found: $sqlPath"
}

$settings = @{}
Get-Content -LiteralPath $envPath -Encoding UTF8 | ForEach-Object {
    if ($_ -match '^([^#=]+)=(.*)$') {
        $settings[$matches[1].Trim()] = $matches[2].Trim()
    }
}

$dbUser = $settings['SSE_DB_USER']
if ([string]::IsNullOrWhiteSpace($dbUser)) {
    throw 'SSE_DB_USER is not configured in the environment file.'
}

Push-Location $repo
try {
    Write-Host 'Starting PostgreSQL...' -ForegroundColor Cyan
    docker compose --env-file $EnvFile up -d postgres

    Write-Host 'Copying the operational dataset into PostgreSQL...' -ForegroundColor Cyan
    docker compose --env-file $EnvFile cp $sqlPath postgres:/tmp/seed_operational_cohorts.sql

    Write-Host 'Loading operational data. This can take a few minutes...' -ForegroundColor Cyan
    docker compose --env-file $EnvFile exec -T postgres `
        psql -v ON_ERROR_STOP=1 -U $dbUser -d sse_db `
        -f /tmp/seed_operational_cohorts.sql

    if ($LASTEXITCODE -ne 0) {
        throw "Dataset load failed with exit code $LASTEXITCODE. The transaction was rolled back."
    }

    Write-Host 'Dataset load and validation completed.' -ForegroundColor Green
}
finally {
    Pop-Location
}
