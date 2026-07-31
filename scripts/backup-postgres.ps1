param(
    [string]$EnvironmentFile = ".env.local",
    [string]$OutputDirectory = "backups",
    [int]$RetentionDays = 30,
    [string]$DatabaseUrl,
    [string]$DockerContainer,
    [switch]$SkipVerification
)

$ErrorActionPreference = "Stop"
$root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path

function Import-Environment([string]$path) {
    if (-not (Test-Path -LiteralPath $path)) { throw "Không tìm thấy file môi trường: $path" }
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
    if (-not $candidates) { throw "Không tìm thấy $name. Hãy cài PostgreSQL client tools." }
    return [string]$candidates[0]
}

Import-Environment (Join-Path $root $EnvironmentFile)
$url = if ($DatabaseUrl) { $DatabaseUrl } else { $env:SSE_DB_URL }
if ($url -notmatch '^jdbc:postgresql://([^:/]+):(\d+)/([^?]+)') { throw "SSE_DB_URL không hợp lệ." }
$databaseHost = $Matches[1]
$databasePort = $Matches[2]
$databaseName = $Matches[3]
if (-not $env:SSE_DB_USER -or -not $env:SSE_DB_PASSWORD) { throw "Thiếu SSE_DB_USER hoặc SSE_DB_PASSWORD." }

$outputRoot = if ([IO.Path]::IsPathRooted($OutputDirectory)) { $OutputDirectory } else { Join-Path $root $OutputDirectory }
[IO.Directory]::CreateDirectory($outputRoot) | Out-Null
$outputRoot = (Resolve-Path -LiteralPath $outputRoot).Path
$stamp = Get-Date -Format "yyyyMMdd-HHmmss"
$backupFile = Join-Path $outputRoot "$databaseName-$stamp.dump"
$checksumFile = "$backupFile.sha256"
$metadataFile = "$backupFile.json"
$env:PGPASSWORD = $env:SSE_DB_PASSWORD

$pgDump = Find-PostgresTool "pg_dump.exe"
$pgRestore = Find-PostgresTool "pg_restore.exe"
if ($DockerContainer) {
    $docker = Get-Command docker.exe -ErrorAction Stop | Select-Object -ExpandProperty Source
    $remoteFile = "/tmp/$([IO.Path]::GetFileName($backupFile))"
    & $docker exec -e "PGPASSWORD=$($env:SSE_DB_PASSWORD)" $DockerContainer pg_dump -U $env:SSE_DB_USER -d $databaseName `
        --format=custom --compress=9 --no-owner --no-privileges --file=$remoteFile
    $dumpExit = $LASTEXITCODE
    if ($dumpExit -eq 0) { & $docker cp "${DockerContainer}:$remoteFile" $backupFile; $dumpExit = $LASTEXITCODE }
    & $docker exec $DockerContainer rm -f $remoteFile | Out-Null
    $backupExit = $dumpExit
} else {
    & $pgDump -h $databaseHost -p $databasePort -U $env:SSE_DB_USER -d $databaseName `
        --format=custom --compress=9 --no-owner --no-privileges --file=$backupFile
    $backupExit = $LASTEXITCODE
}
if ($backupExit -ne 0 -or -not (Test-Path -LiteralPath $backupFile)) { throw "PostgreSQL backup failed." }

if (-not $SkipVerification) {
    $objects = & $pgRestore --list $backupFile
    if ($LASTEXITCODE -ne 0 -or ($objects | Measure-Object).Count -lt 10) { throw "Backup không vượt qua bước kiểm tra cấu trúc." }
}

$hash = (Get-FileHash -LiteralPath $backupFile -Algorithm SHA256).Hash.ToLowerInvariant()
[IO.File]::WriteAllText($checksumFile, "$hash  $([IO.Path]::GetFileName($backupFile))`n", [Text.UTF8Encoding]::new($false))
$metadata = [ordered]@{
    database = $databaseName
    host = $databaseHost
    port = [int]$databasePort
    createdAt = (Get-Date).ToUniversalTime().ToString("o")
    format = "PostgreSQL custom"
    sha256 = $hash
    verified = -not $SkipVerification
    sizeBytes = (Get-Item -LiteralPath $backupFile).Length
    dockerContainer = $DockerContainer
}
[IO.File]::WriteAllText($metadataFile, ($metadata | ConvertTo-Json -Depth 3), [Text.UTF8Encoding]::new($false))

if ($RetentionDays -gt 0) {
    $cutoff = (Get-Date).AddDays(-$RetentionDays)
    Get-ChildItem -LiteralPath $outputRoot -File | Where-Object {
        $_.LastWriteTime -lt $cutoff -and $_.Name -match '\.(dump|sha256|json)$'
    } | ForEach-Object {
        if ($_.FullName.StartsWith($outputRoot, [StringComparison]::OrdinalIgnoreCase)) {
            Remove-Item -LiteralPath $_.FullName -Force
        }
    }
}

[pscustomobject]@{ BackupFile = $backupFile; ChecksumFile = $checksumFile; MetadataFile = $metadataFile; Sha256 = $hash; Verified = -not $SkipVerification }
