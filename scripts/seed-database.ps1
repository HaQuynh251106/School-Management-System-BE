param(
    [ValidateSet("demo", "scenario")]
    [string]$Dataset = "scenario",
    [ValidateSet("SEED")]
    [string]$Confirm,
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
$seedRoot = Join-Path $PSScriptRoot "..\services\app\src\main\resources\db\seed"
$scripts = @("demo.sql")
if ($Dataset -eq "scenario") { $scripts += "scenario.sql" }

$oldPassword = $env:PGPASSWORD
try {
    $env:PGPASSWORD = $DbPassword
    foreach ($script in $scripts) {
        $path = Join-Path $seedRoot $script
        Write-Host "[RUN] $script"
        & $PsqlPath --host $DbHost --port $DbPort --username $DbUser `
            --dbname $Database --set ON_ERROR_STOP=1 --file $path
        if ($LASTEXITCODE -ne 0) {
            throw "Seed script failed: $script"
        }
    }
} finally {
    $env:PGPASSWORD = $oldPassword
}

Write-Host "[OK] Dataset '$Dataset' applied to $Database."
