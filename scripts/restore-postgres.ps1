param(
    [Parameter(Mandatory = $true)][string]$BackupFile,
    [string]$EnvironmentFile = ".env.local",
    [string]$TargetDatabase,
    [string]$DockerContainer,
    [switch]$ReplaceDatabase,
    [switch]$ConfirmRestore,
    [switch]$SkipChecksum
)

$ErrorActionPreference = "Stop"
$root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path

function Import-Environment([string]$path) {
    if (-not (Test-Path -LiteralPath $path)) { throw "Environment file not found: $path" }
    Get-Content -LiteralPath $path | ForEach-Object {
        $line = $_.Trim()
        if (-not $line -or $line.StartsWith("#")) { return }
        $parts = $line.Split("=", 2)
        if ($parts.Count -eq 2) { [Environment]::SetEnvironmentVariable($parts[0].Trim(), $parts[1].Trim(), "Process") }
    }
}

function Find-PostgresTool([string]$name) {
    $candidates = @(@(
        (Get-Command $name -ErrorAction SilentlyContinue | Select-Object -ExpandProperty Source -ErrorAction SilentlyContinue),
        "$env:ProgramFiles\PostgreSQL\17\bin\$name",
        "$env:ProgramFiles\PostgreSQL\16\bin\$name"
    ) | Where-Object { $_ -and (Test-Path -LiteralPath $_) })
    if (-not $candidates) { throw "PostgreSQL tool not found: $name" }
    return [string]$candidates[0]
}

Import-Environment (Join-Path $root $EnvironmentFile)
if ($env:SSE_DB_URL -notmatch '^jdbc:postgresql://([^:/]+):(\d+)/([^?]+)') { throw "Invalid SSE_DB_URL." }
$databaseHost = $Matches[1]
$databasePort = $Matches[2]
$sourceDatabase = $Matches[3]
$target = if ($TargetDatabase) { $TargetDatabase } else { "${sourceDatabase}_restore_verify" }
if ($target -in @('postgres', 'template0', 'template1')) { throw "Refusing to restore into a PostgreSQL system database." }
if (-not (Test-Path -LiteralPath $BackupFile)) { throw "Backup not found: $BackupFile" }
$BackupFile = (Resolve-Path -LiteralPath $BackupFile).Path

if (-not $SkipChecksum) {
    $checksumPath = "$BackupFile.sha256"
    if (-not (Test-Path -LiteralPath $checksumPath)) { throw "Checksum file not found: $checksumPath" }
    $expected = ((Get-Content -LiteralPath $checksumPath -Raw).Trim() -split '\s+')[0].ToLowerInvariant()
    $actual = (Get-FileHash -LiteralPath $BackupFile -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($expected -ne $actual) { throw "SHA-256 checksum mismatch. Backup may be corrupted." }
}

if ($target -eq $sourceDatabase -and (-not $ReplaceDatabase -or -not $ConfirmRestore)) {
    throw "Restoring over the primary database requires -ReplaceDatabase and -ConfirmRestore. Stop Backend first."
}
if ($ReplaceDatabase -and -not $ConfirmRestore) { throw "-ReplaceDatabase requires -ConfirmRestore." }

$psql = Find-PostgresTool "psql.exe"
$createdb = Find-PostgresTool "createdb.exe"
$dropdb = Find-PostgresTool "dropdb.exe"
$pgRestore = Find-PostgresTool "pg_restore.exe"
$env:PGPASSWORD = $env:SSE_DB_PASSWORD

$exists = [string](& $psql -w -h $databaseHost -p $databasePort -U $env:SSE_DB_USER -d postgres -tAc "SELECT 1 FROM pg_database WHERE datname='$target'")
if ($exists -and $exists.Trim() -eq '1') {
    if (-not $ReplaceDatabase) { throw "Database $target already exists. Use another name or confirm replacement." }
    & $psql -w -h $databaseHost -p $databasePort -U $env:SSE_DB_USER -d postgres -c "SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname='$target' AND pid <> pg_backend_pid();" | Out-Null
    & $dropdb -w -h $databaseHost -p $databasePort -U $env:SSE_DB_USER $target
    if ($LASTEXITCODE -ne 0) { throw "Could not drop target database $target." }
}

& $createdb -w -h $databaseHost -p $databasePort -U $env:SSE_DB_USER $target
if ($LASTEXITCODE -ne 0) { throw "Could not create target database $target." }
try {
    if ($DockerContainer) {
        $docker = Get-Command docker.exe -ErrorAction Stop | Select-Object -ExpandProperty Source
        $remoteFile = "/tmp/$([IO.Path]::GetFileName($BackupFile))"
        & $docker cp $BackupFile "${DockerContainer}:$remoteFile"
        if ($LASTEXITCODE -ne 0) { throw "Could not copy backup into PostgreSQL container." }
        & $docker exec -e "PGPASSWORD=$($env:SSE_DB_PASSWORD)" $DockerContainer pg_restore -U $env:SSE_DB_USER -d $target `
            --exit-on-error --no-owner --no-privileges $remoteFile
        $restoreExit = $LASTEXITCODE
        & $docker exec $DockerContainer rm -f $remoteFile | Out-Null
    } else {
        & $pgRestore -w -h $databaseHost -p $databasePort -U $env:SSE_DB_USER -d $target `
            --exit-on-error --no-owner --no-privileges $BackupFile
        $restoreExit = $LASTEXITCODE
    }
    if ($restoreExit -ne 0) { throw "PostgreSQL restore failed." }
    $tableCount = [int](& $psql -w -h $databaseHost -p $databasePort -U $env:SSE_DB_USER -d $target -tAc "SELECT count(*) FROM pg_tables WHERE schemaname='public' AND tablename <> 'flyway_schema_history'")
    $failedMigrations = [int](& $psql -w -h $databaseHost -p $databasePort -U $env:SSE_DB_USER -d $target -tAc "SELECT count(*) FROM flyway_schema_history WHERE success = false")
    if ($tableCount -lt 10 -or $failedMigrations -ne 0) { throw "Restored database failed integrity checks." }
    [pscustomobject]@{ Database = $target; Tables = $tableCount; FailedMigrations = $failedMigrations; Verified = $true }
} catch {
    if ($target -ne $sourceDatabase) { & $dropdb -w -h $databaseHost -p $databasePort -U $env:SSE_DB_USER --if-exists $target | Out-Null }
    throw
}
