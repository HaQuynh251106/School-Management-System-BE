param(
    [Parameter(Mandatory = $true)]
    [string]$BackupDirectory,
    [Parameter(Mandatory = $true)]
    [ValidateSet("RESTORE")]
    [string]$ConfirmRestore,
    [string]$DbHost = "127.0.0.1",
    [int]$DbPort = 5432,
    [string]$Database = "sse_db",
    [string]$DbUser = "postgres",
    [string]$DbPassword = "postgres",
    [string]$PgRestorePath = "pg_restore.exe",
    [switch]$RestoreMinio,
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

$PgRestorePath = Resolve-PostgresTool $PgRestorePath "pg_restore"
$root = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot ".."))
$allowedBackupRoot = [System.IO.Path]::GetFullPath((Join-Path $root ".backups"))
$backup = [System.IO.Path]::GetFullPath($BackupDirectory)
if (-not $backup.StartsWith($allowedBackupRoot, [System.StringComparison]::OrdinalIgnoreCase)) {
    throw "BackupDirectory must be inside $allowedBackupRoot"
}
if (-not (Test-Path -LiteralPath $backup -PathType Container)) {
    throw "Backup directory not found: $backup"
}
$manifestPath = Join-Path $backup "manifest.json"
if (-not (Test-Path -LiteralPath $manifestPath -PathType Leaf)) {
    throw "Backup manifest not found: $manifestPath"
}
$manifest = Get-Content -LiteralPath $manifestPath -Raw | ConvertFrom-Json
$dumpFile = Join-Path $backup $manifest.databaseDump
if (-not (Test-Path -LiteralPath $dumpFile -PathType Leaf)) {
    throw "Database dump not found: $dumpFile"
}
if ($manifest.databaseDumpSha256) {
    $actualHash = (Get-FileHash -LiteralPath $dumpFile -Algorithm SHA256).Hash
    if ($actualHash -ne $manifest.databaseDumpSha256) {
        throw "Database dump checksum mismatch."
    }
}

$oldPassword = $env:PGPASSWORD
try {
    $env:PGPASSWORD = $DbPassword
    & $PgRestorePath --host $DbHost --port $DbPort --username $DbUser `
        --dbname $Database --clean --if-exists --no-owner --no-privileges $dumpFile
    if ($LASTEXITCODE -ne 0) {
        throw "pg_restore failed with exit code $LASTEXITCODE"
    }
} finally {
    $env:PGPASSWORD = $oldPassword
}

if ($RestoreMinio) {
    $source = Join-Path $backup "minio-data"
    if (-not (Test-Path -LiteralPath $source -PathType Container)) {
        throw "MinIO backup not found: $source"
    }
    $destination = [System.IO.Path]::GetFullPath((Join-Path $root $MinioDataPath))
    if (-not $destination.StartsWith($root, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "MinIO destination must stay inside $root"
    }
    New-Item -ItemType Directory -Path $destination -Force | Out-Null
    Get-ChildItem -LiteralPath $destination -Force | Remove-Item -Recurse -Force
    Copy-Item -Path (Join-Path $source "*") -Destination $destination -Recurse -Force
}

Write-Host "[OK] Restore completed from: $backup"
