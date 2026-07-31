param(
    [Parameter(Mandatory = $true)][string]$VerifiedBackupFile,
    [string]$EnvironmentFile = ".env.local",
    [string]$DatabaseName
)

$ErrorActionPreference = "Stop"
$root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$environmentPath = Join-Path $root $EnvironmentFile
if (-not (Test-Path -LiteralPath $environmentPath)) { throw "Environment file not found: $environmentPath" }
Get-Content -LiteralPath $environmentPath | ForEach-Object {
    $line = $_.Trim()
    if (-not $line -or $line.StartsWith("#")) { return }
    $parts = $line.Split("=", 2)
    if ($parts.Count -eq 2) { [Environment]::SetEnvironmentVariable($parts[0].Trim(), $parts[1].Trim(), "Process") }
}

if (-not (Test-Path -LiteralPath $VerifiedBackupFile)) { throw "Verified backup not found." }
$VerifiedBackupFile = (Resolve-Path -LiteralPath $VerifiedBackupFile).Path
$checksumPath = "$VerifiedBackupFile.sha256"
if (-not (Test-Path -LiteralPath $checksumPath)) { throw "Backup checksum not found." }
$expected = ((Get-Content -LiteralPath $checksumPath -Raw).Trim() -split '\s+')[0].ToLowerInvariant()
$actual = (Get-FileHash -LiteralPath $VerifiedBackupFile -Algorithm SHA256).Hash.ToLowerInvariant()
if ($expected -ne $actual) { throw "Backup checksum mismatch." }

if ($env:SSE_DB_URL -notmatch '^jdbc:postgresql://([^:/]+):(\d+)/([^?]+)') { throw "Invalid SSE_DB_URL." }
$hostName = $Matches[1]
$port = $Matches[2]
$database = if ($DatabaseName) { $DatabaseName } else { $Matches[3] }
$url = "jdbc:postgresql://${hostName}:${port}/${database}"

$mavenCandidates = @(@(
    (Get-Command mvn.cmd -ErrorAction SilentlyContinue | Select-Object -ExpandProperty Source -ErrorAction SilentlyContinue),
    (Get-ChildItem "$env:USERPROFILE\.m2\wrapper\dists" -Recurse -Filter mvn.cmd -ErrorAction SilentlyContinue | Sort-Object FullName -Descending | Select-Object -First 1 -ExpandProperty FullName)
) | Where-Object { $_ -and (Test-Path -LiteralPath $_) })
if (-not $mavenCandidates) { throw "Maven executable not found." }
$maven = [string]$mavenCandidates[0]
$common = @(
    "-Dflyway.url=$url",
    "-Dflyway.user=$($env:SSE_DB_USER)",
    "-Dflyway.password=$($env:SSE_DB_PASSWORD)",
    '-Dflyway.locations=filesystem:services/app/src/main/resources/db/migration'
)
$psqlCandidates = @(
    (Get-Command psql.exe -ErrorAction SilentlyContinue | Select-Object -ExpandProperty Source -ErrorAction SilentlyContinue),
    "$env:ProgramFiles\PostgreSQL\17\bin\psql.exe",
    "$env:USERPROFILE\.codex\runtime\postgresql-17-full\pgsql\bin\psql.exe"
) | Where-Object { $_ -and (Test-Path -LiteralPath $_) }
if (-not $psqlCandidates) { throw "PostgreSQL psql executable not found." }
$psql = [string]$psqlCandidates[0]

Push-Location $root
try {
    & $maven -N -q flyway:repair @common
    if ($LASTEXITCODE -ne 0) { throw "Flyway repair failed." }
    & $maven -N -q flyway:migrate @common
    if ($LASTEXITCODE -ne 0) { throw "Flyway migrate failed." }
    & $maven -N -q flyway:validate @common
    if ($LASTEXITCODE -ne 0) { throw "Flyway validate failed." }
    # The Maven repair runs from SQL filesystem locations, so it cannot see the
    # compiled Java migration V28 and may append a misleading DELETE tombstone.
    # Preserve the already-successful JDBC migration for Spring Boot validation.
    $env:PGPASSWORD = $env:SSE_DB_PASSWORD
    $cleanupJavaTombstones = @"
DELETE FROM flyway_schema_history deleted
WHERE deleted.type = 'DELETE'
  AND EXISTS (
    SELECT 1 FROM flyway_schema_history applied
    WHERE applied.version = deleted.version
      AND applied.success = TRUE
      AND applied.type = 'JDBC'
  );
"@
    & $psql -w -h $hostName -p $port -U $env:SSE_DB_USER -d $database -v ON_ERROR_STOP=1 -c $cleanupJavaTombstones | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "Could not reconcile Java migration history." }
    [pscustomobject]@{ Database = $database; Backup = $VerifiedBackupFile; Repaired = $true; Migrated = $true; Validated = $true }
} finally {
    Pop-Location
}
