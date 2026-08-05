param(
    [string]$DbHost = "127.0.0.1",
    [int]$DbPort = 5432,
    [string]$Database = "sse_db",
    [string]$DbUser = "postgres",
    [string]$DbPassword = "postgres",
    [string]$PgDumpPath = "pg_dump.exe",
    [string]$OutputRoot = ".\.backups",
    [string]$MinioDataPath = ".\infrastructure\docker\minio\data"
)

$ErrorActionPreference = "Stop"

function Resolve-PostgresTool {
    param([string]$RequestedPath, [string]$ToolName)
    $command = Get-Command $RequestedPath -ErrorAction SilentlyContinue
    if ($command) { return $command.Source }

    $postgresRoot = Join-Path $env:ProgramFiles "PostgreSQL"
    if (Test-Path -LiteralPath $postgresRoot) {
        $candidates = Get-ChildItem -LiteralPath $postgresRoot -Directory |
            Sort-Object Name -Descending |
            ForEach-Object {
                Join-Path $_.FullName "bin\$ToolName.exe"
                Join-Path $_.FullName "pgAdmin 4\runtime\$ToolName.exe"
            }
        $match = $candidates | Where-Object {
            Test-Path -LiteralPath $_ -PathType Leaf
        } | Select-Object -First 1
        if ($match) { return $match }
    }
    throw "Cannot find $ToolName. Install PostgreSQL client tools or pass an explicit path."
}

$PgDumpPath = Resolve-PostgresTool $PgDumpPath "pg_dump"
$root = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot ".."))
$outputBase = [System.IO.Path]::GetFullPath((Join-Path $root $OutputRoot))
if (-not $outputBase.StartsWith($root, [System.StringComparison]::OrdinalIgnoreCase)) {
    throw "OutputRoot must stay inside the backend workspace: $root"
}

$stamp = Get-Date -Format "yyyyMMdd-HHmmss"
$backupDir = Join-Path $outputBase $stamp
New-Item -ItemType Directory -Path $backupDir -Force | Out-Null

$oldPassword = $env:PGPASSWORD
try {
    $env:PGPASSWORD = $DbPassword
    $dumpFile = Join-Path $backupDir "$Database.dump"
    & $PgDumpPath --host $DbHost --port $DbPort --username $DbUser `
        --format custom --no-owner --no-privileges --file $dumpFile $Database
    if ($LASTEXITCODE -ne 0) {
        throw "pg_dump failed with exit code $LASTEXITCODE"
    }
    & $PgDumpPath --version | Out-Null
} finally {
    $env:PGPASSWORD = $oldPassword
}

$minioSource = [System.IO.Path]::GetFullPath((Join-Path $root $MinioDataPath))
if (Test-Path -LiteralPath $minioSource) {
    Copy-Item -LiteralPath $minioSource `
        -Destination (Join-Path $backupDir "minio-data") -Recurse -Force
}

$manifest = [ordered]@{
    createdAt = (Get-Date).ToString("o")
    database = $Database
    databaseHost = $DbHost
    databasePort = $DbPort
    databaseDump = "$Database.dump"
    databaseDumpBytes = (Get-Item -LiteralPath $dumpFile).Length
    databaseDumpSha256 = (Get-FileHash -LiteralPath $dumpFile -Algorithm SHA256).Hash
    minioIncluded = (Test-Path -LiteralPath (Join-Path $backupDir "minio-data"))
    minioFileCount = @(
        Get-ChildItem -LiteralPath (Join-Path $backupDir "minio-data") -File -Recurse -ErrorAction SilentlyContinue
    ).Count
}
$manifest | ConvertTo-Json | Set-Content `
    -LiteralPath (Join-Path $backupDir "manifest.json") -Encoding UTF8

Write-Host "[OK] Backup created: $backupDir"
