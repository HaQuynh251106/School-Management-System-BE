param(
    [string]$DbHost = "127.0.0.1",
    [int]$DbPort = 5432,
    [string]$Database = "sse_db",
    [string]$DbUser = "postgres",
    [string]$DbPassword = "postgres",
    [string]$PsqlPath = "psql.exe"
)

$ErrorActionPreference = "Stop"

function Resolve-PostgresTool {
    param([string]$RequestedPath, [string]$ToolName)
    $command = Get-Command $RequestedPath -ErrorAction SilentlyContinue
    if ($command) { return $command.Source }

    $postgresRoot = Join-Path $env:ProgramFiles "PostgreSQL"
    if (Test-Path -LiteralPath $postgresRoot) {
        $match = Get-ChildItem -LiteralPath $postgresRoot -Directory |
            Sort-Object Name -Descending |
            ForEach-Object {
                Join-Path $_.FullName "bin\$ToolName.exe"
                Join-Path $_.FullName "pgAdmin 4\runtime\$ToolName.exe"
            } |
            Where-Object { Test-Path -LiteralPath $_ -PathType Leaf } |
            Select-Object -First 1
        if ($match) { return $match }
    }
    throw "Cannot find $ToolName. Pass an explicit tool path."
}

$PsqlPath = Resolve-PostgresTool $PsqlPath "psql"
$sqlPath = Join-Path $PSScriptRoot "database\quality-audit.sql"
if (-not (Test-Path -LiteralPath $sqlPath -PathType Leaf)) {
    throw "Audit SQL not found: $sqlPath"
}

$oldPassword = $env:PGPASSWORD
try {
    $env:PGPASSWORD = $DbPassword
    $raw = & $PsqlPath --host $DbHost --port $DbPort --username $DbUser `
        --dbname $Database --tuples-only --no-align --field-separator "|" `
        --set ON_ERROR_STOP=1 --file $sqlPath 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw ($raw -join [Environment]::NewLine)
    }
} finally {
    $env:PGPASSWORD = $oldPassword
}

$issueLines = $raw | Where-Object { $_ -match '^[a-z_]+[|][0-9]+$' }
$issues = foreach ($line in $issueLines) {
    $parts = $line -split '[|]'
    [pscustomobject]@{ Issue = $parts[0]; Affected = [long]$parts[1] }
}
$failed = @($issues | Where-Object { $_.Affected -gt 0 })
$issues | Format-Table -AutoSize

if ($failed.Count -gt 0) {
    throw "Database quality audit failed with $($failed.Count) issue type(s)."
}

Write-Host "[OK] Database quality audit passed for $Database."
