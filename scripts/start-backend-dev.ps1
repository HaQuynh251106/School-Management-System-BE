param(
    [switch]$StopExisting,
    [switch]$SkipBuild,
    [string]$Maven = "C:\Users\thaid\.cache\sse-tools\apache-maven-3.9.16\bin\mvn.cmd",
    [string]$Java = "C:\Program Files\Eclipse Adoptium\jdk-17.0.19.10-hotspot\bin\java.exe"
)

$ErrorActionPreference = "Stop"
$repoRoot = Split-Path -Parent $PSScriptRoot

function Stop-BackendOnPort4000 {
    $listeners = @(Get-NetTCPConnection -LocalPort 4000 -State Listen -ErrorAction SilentlyContinue)
    if ($listeners.Count -eq 0) { return }

    $processIds = @($listeners | Select-Object -ExpandProperty OwningProcess -Unique)
    if (-not $StopExisting) {
        throw "Port 4000 is already in use by PID $($processIds -join ', '). Stop that backend first or rerun with -StopExisting."
    }

    foreach ($processId in $processIds) {
        $process = Get-CimInstance Win32_Process -Filter "ProcessId = $processId"
        if ($process.Name -notin @('java.exe', 'javaw.exe') -or $process.CommandLine -notmatch 'sse-app\.jar') {
            throw "Refusing to stop PID $processId because it is not the SSE backend."
        }
        Stop-Process -Id $processId -Force
        Write-Host "Stopped previous SSE backend (PID $processId)."
    }
}

Set-Location $repoRoot
Stop-BackendOnPort4000

docker compose -f docker-compose.dev.yml up -d minio minio-init rabbitmq
if ($LASTEXITCODE -ne 0) { throw "Docker infrastructure could not be started." }

$env:SSE_DB_URL = "jdbc:postgresql://localhost:5432/sse_db"
$env:SSE_DB_USER = "postgres"
$env:SSE_DB_PASSWORD = "postgres"
$env:SSE_EVENTS_RABBITMQ_ENABLED = "true"
$env:SSE_EVENTS_LOCAL_LISTENER_ENABLED = "false"
$env:SSE_NOTIFICATION_WORKER_ENABLED = "true"

if (-not $SkipBuild) {
    & $Maven -pl services/app -am clean package -DskipTests
    if ($LASTEXITCODE -ne 0) { throw "Backend build failed; the application was not started." }
}

& $Java -jar .\services\app\target\sse-app.jar
exit $LASTEXITCODE
