param(
    [string]$DbHost = "127.0.0.1",
    [int]$DbPort = 5432,
    [string]$Database = "sse_db",
    [string]$DbUser = "postgres",
    [string]$DbPassword = "postgres",
    [string]$PsqlPath = "psql.exe",
    [string]$MavenPath = "mvn.cmd",
    [string]$JavaPath = "java.exe",
    [string]$Confirm,
    [switch]$AllowAnyDatabase,
    [switch]$SkipMinio,
    [switch]$SkipApiSmoke,
    [int]$SmokePort = 4010
)

$ErrorActionPreference = "Stop"
$ProgressPreference = "SilentlyContinue"

$RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$AppJar = Join-Path $RepoRoot "services\app\target\sse-app.jar"
$LegacyPreflightSql = Join-Path $RepoRoot "services\app\src\main\resources\db\seed\full-demo-legacy-preflight.sql"
$ResetSql = Join-Path $RepoRoot "services\app\src\main\resources\db\seed\full-demo-reset.sql"
$VerifySql = Join-Path $RepoRoot "services\app\src\main\resources\db\seed\full-demo-verify.sql"
$ComposeFile = Join-Path $RepoRoot "docker-compose.dev.yml"
$ExpectedConfirmation = "RESET $Database"
$SmokeBaseUrl = "http://127.0.0.1:$SmokePort"
$SmokeLog = Join-Path $RepoRoot "services\app\target\full-demo-smoke.log"
$SmokeErrorLog = Join-Path $RepoRoot "services\app\target\full-demo-smoke-error.log"

function Resolve-Tool {
    param([string]$RequestedPath, [string]$ToolName)
    $command = Get-Command $RequestedPath -ErrorAction SilentlyContinue
    if ($command) { return $command.Source }

    # Cho phép cùng script chạy được cả Windows và shell CI/macOS.
    $portableName = $RequestedPath -replace '\.(exe|cmd)$', ''
    $command = Get-Command $portableName -ErrorAction SilentlyContinue
    if ($command) { return $command.Source }

    if ($ToolName -eq "psql" -and $env:ProgramFiles) {
        $postgresRoot = Join-Path $env:ProgramFiles "PostgreSQL"
        if (Test-Path -LiteralPath $postgresRoot) {
            $match = Get-ChildItem -LiteralPath $postgresRoot -Directory |
                Sort-Object Name -Descending |
                ForEach-Object {
                    Join-Path $_.FullName "bin\psql.exe"
                    Join-Path $_.FullName "pgAdmin 4\runtime\psql.exe"
                } |
                Where-Object { Test-Path -LiteralPath $_ -PathType Leaf } |
                Select-Object -First 1
            if ($match) { return $match }
        }
    }

    if ($ToolName -eq "maven") {
        $wrapper = Join-Path $RepoRoot "mvnw.cmd"
        if (Test-Path -LiteralPath $wrapper) { return $wrapper }
        $unixWrapper = Join-Path $RepoRoot "mvnw"
        if (Test-Path -LiteralPath $unixWrapper) { return $unixWrapper }
    }
    throw "Không tìm thấy $ToolName. Hãy truyền đường dẫn bằng tham số tương ứng."
}

function Invoke-Psql {
    param([string[]]$Arguments)
    & $script:Psql @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "psql thất bại với mã thoát $LASTEXITCODE"
    }
}

function Get-PsqlScalar {
    param([string]$Sql)
    $value = & $script:Psql "--host" $DbHost "--port" "$DbPort" "--username" $DbUser `
        "--dbname" $Database "--set" "ON_ERROR_STOP=1" "--tuples-only" "--no-align" `
        "--command" $Sql
    if ($LASTEXITCODE -ne 0) { throw "psql scalar thất bại với mã thoát $LASTEXITCODE" }
    return (($value | Out-String).Trim())
}

function Test-HttpReady {
    param([string]$Url)
    try {
        $response = Invoke-WebRequest -Uri $Url -UseBasicParsing -TimeoutSec 3
        return ([int]$response.StatusCode -ge 200 -and [int]$response.StatusCode -lt 500)
    } catch {
        return $false
    }
}

function Wait-HttpReady {
    param([string]$Url, [int]$TimeoutSeconds = 90, [System.Diagnostics.Process]$Process)
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    do {
        if ($Process -and $Process.HasExited) {
            $tail = if (Test-Path $SmokeErrorLog) {
                (Get-Content -LiteralPath $SmokeErrorLog -Tail 40) -join [Environment]::NewLine
            } else { "Không có error log." }
            throw "Backend smoke đã dừng trước khi sẵn sàng.`n$tail"
        }
        if (Test-HttpReady $Url) { return }
        Start-Sleep -Milliseconds 750
    } while ((Get-Date) -lt $deadline)
    throw "Backend không sẵn sàng tại $Url sau $TimeoutSeconds giây."
}

function Invoke-Api {
    param(
        [ValidateSet("GET", "POST", "PUT", "DELETE")][string]$Method,
        [string]$Path,
        [object]$Body = $null,
        [string]$Token = $null,
        [int[]]$ExpectedStatus = @(200)
    )
    $headers = @{}
    if ($Token) { $headers.Authorization = "Bearer $Token" }
    $request = @{
        Method = $Method
        Uri = "$SmokeBaseUrl$Path"
        UseBasicParsing = $true
        ContentType = "application/json; charset=utf-8"
        Headers = $headers
    }
    if ($null -ne $Body) { $request.Body = $Body | ConvertTo-Json -Depth 12 }
    try {
        $response = Invoke-WebRequest @request
        $status = [int]$response.StatusCode
        if ($ExpectedStatus -notcontains $status) {
            throw "HTTP $status cho $Method $Path; mong đợi $($ExpectedStatus -join '/')"
        }
        if ([string]::IsNullOrWhiteSpace($response.Content)) { return $null }
        return $response.Content | ConvertFrom-Json
    } catch {
        $status = $null
        if ($_.Exception.Response -and $_.Exception.Response.StatusCode) {
            try { $status = [int]$_.Exception.Response.StatusCode } catch {
                try { $status = [int]$_.Exception.Response.StatusCode.value__ } catch { }
            }
        }
        if ($null -ne $status -and $ExpectedStatus -contains $status) { return $null }
        throw
    }
}

function Login-Demo {
    param([string]$Login, [string]$Password, [string]$ExpectedRole)
    $response = Invoke-Api POST "/auth/login" @{
        username = $Login
        password = $Password
        platform = "FULL_DEMO_SMOKE"
        deviceName = "reset-and-seed-full-demo.ps1"
    }
    if (-not $response.accessToken) { throw "Login $Login không trả accessToken" }
    if ($response.user.role -ne $ExpectedRole) {
        throw "Login $Login trả role '$($response.user.role)', mong đợi '$ExpectedRole'"
    }
    return $response
}

function As-Array {
    param([object]$Value)
    if ($null -eq $Value) { return @() }
    return @($Value)
}

function Assert-MinCount {
    param([object]$Value, [int]$Minimum, [string]$Label)
    $items = @(As-Array $Value)
    if ($items.Count -lt $Minimum) {
        throw "${Label}: cần ít nhất $Minimum bản ghi, thực tế $($items.Count)"
    }
    Write-Host "  [PASS] $Label ($($items.Count))" -ForegroundColor Green
}

function Assert-True {
    param([bool]$Condition, [string]$Label)
    if (-not $Condition) { throw "${Label}: điều kiện nghiệp vụ chưa đạt" }
    Write-Host "  [PASS] $Label" -ForegroundColor Green
}

function Run-ApiSmoke {
    Write-Host "`n[SMOKE] Khởi động backend tạm tại $SmokeBaseUrl" -ForegroundColor Cyan
    Remove-Item -LiteralPath $SmokeLog, $SmokeErrorLog -Force -ErrorAction SilentlyContinue
    $arguments = @("-jar", ('"' + $AppJar + '"'), "--server.port=$SmokePort")
    $process = Start-Process -FilePath $script:Java -ArgumentList $arguments -PassThru `
        -RedirectStandardOutput $SmokeLog -RedirectStandardError $SmokeErrorLog
    try {
        Wait-HttpReady "$SmokeBaseUrl/v3/api-docs" 120 $process

        # Mỗi kiểu định danh đăng nhập được kiểm tra đúng theo nghiệp vụ hiện có.
        $admin = Login-Demo "demo.admin.01" "Admin@123" "ADMIN"
        $teacher = Login-Demo "demo.gv.001@sse.local" "Teacher@123" "TEACHER"
        $student = Login-Demo "HS270001" "Student@123" "STUDENT"
        $parent = Login-Demo "0933000001" "Parent@123" "PARENT"
        Write-Host "  [PASS] Login 4 role bằng username/email/mã học sinh/số điện thoại" -ForegroundColor Green

        $dashboard = Invoke-Api GET "/dashboard" $null $admin.accessToken
        Assert-MinCount $dashboard.metrics 1 "Dashboard Admin có chỉ số thật"

        Assert-MinCount (Invoke-Api GET "/academicYears" $null $admin.accessToken) 2 `
            "Có năm nguồn tổng kết và năm học đang vận hành"
        Assert-MinCount (Invoke-Api GET "/semesters?academicYearId=fd-ay-2027" $null $admin.accessToken) 2 `
            "Năm 2027-2028 có đủ hai học kỳ"
        Assert-MinCount (Invoke-Api GET "/classes?academicYearId=fd-ay-2027" $null $admin.accessToken) 30 `
            "Có 30 lớp đã chuẩn bị; 6 lớp hoạt động dùng để phân công và xếp lịch"
        Assert-MinCount (Invoke-Api GET "/subjects" $null $admin.accessToken) 12 `
            "Danh mục môn học và hoạt động cố định"
        Assert-MinCount (Invoke-Api GET "/rooms" $null $admin.accessToken) 44 `
            "Phòng cố định, phòng bộ môn và nhà thể chất"

        Assert-MinCount (Invoke-Api GET "/academic/education-planning/programs" $null $admin.accessToken) 1 `
            "Chương trình giáo dục đang áp dụng"
        foreach ($grade in @("K10", "K11", "K12")) {
            Assert-MinCount (Invoke-Api GET `
                "/academic/education-planning/programs/fd-program-2027/subjects?gradeLevel=$grade" `
                $null $admin.accessToken) 12 "Thời lượng môn $grade khớp chương trình"
            Assert-MinCount (Invoke-Api GET `
                "/academic/education-planning/combinations?academicYearId=fd-ay-2027&gradeLevel=$grade" `
                $null $admin.accessToken) 1 "Tổ hợp môn $grade đã gán được cho lớp"
        }
        Assert-MinCount (Invoke-Api GET `
            "/academic/training-plans?academicYearId=fd-ay-2027" $null $admin.accessToken) 9 `
            "Có lịch sử, bản công bố và bản nháp kế hoạch giáo dục"

        foreach ($planId in @(
            "fd-plan-k10-v2", "fd-plan-k11-v2", "fd-plan-k12-v2",
            "fd-plan-k10-v3", "fd-plan-k11-v3", "fd-plan-k12-v3"
        )) {
            $validation = Invoke-Api GET "/academic/training-plans/$planId/validation" `
                $null $admin.accessToken
            Assert-True ($validation.valid -and $validation.errorCount -eq 0) `
                "Kế hoạch $planId không còn lỗi bắt buộc"
        }

        foreach ($grade in @("K10", "K11", "K12")) {
            $readiness = Invoke-Api GET `
                "/timetable/schedules/generation-readiness?academicYearId=fd-ay-2027&semesterId=fd-sem-2027-1&scopeGradeLevel=$grade" `
                $null $admin.accessToken
            Assert-True $readiness.ready "Auto-plan $grade đã đủ điều kiện đầu vào"

            $scheduleId = "fd-schedule-$($grade.ToLowerInvariant())-hk1"
            $scheduleValidation = Invoke-Api GET `
                "/timetable/schedules/$scheduleId/validation" $null $admin.accessToken
            Assert-True ($scheduleValidation.valid -and $scheduleValidation.errorCount -eq 0) `
                "TKB đã phát hành $grade vượt qua validator lớp/GV/phòng/tải học"
        }

        # Chạy thật bộ giải cho cả ba khối, rồi xóa các bản nháp kiểm tra để
        # database sau smoke vẫn sạch và script chạy lặp không sinh rác.
        foreach ($grade in @("K10", "K11", "K12")) {
            $autoScheduleId = "fd-smoke-auto-$($grade.ToLowerInvariant())-$([Guid]::NewGuid().ToString('N'))"
            try {
                $generated = Invoke-Api POST "/timetable/schedules/generate" @{
                    id = $autoScheduleId
                    academicYearId = "fd-ay-2027"
                    semesterId = "fd-sem-2027-1"
                    scopeGradeLevel = $grade
                    name = "Smoke auto-plan $grade"
                    teachingDays = @("MON", "TUE", "WED", "THU", "FRI")
                    # Bao phủ cả ca sáng (K12) và ca chiều (K10/K11); solver
                    # tự lọc đúng 5 tiết chính của từng lớp.
                    firstPeriod = 1
                    lastPeriod = 10
                    maxPeriodsPerDay = 5
                    maxProgressGapDays = 2
                    maxProgressGapPeriods = 2
                    maxCurriculumGapLessons = 1
                    solveSeconds = 30
                } $admin.accessToken
                Assert-True ($generated.validation.valid -and $generated.validation.errorCount -eq 0 `
                    -and $generated.schedule.hardViolationCount -eq 0) `
                    "Bộ giải tự động tạo được TKB $grade từ dữ liệu seed"
            } finally {
                if ($autoScheduleId) {
                    Invoke-Api DELETE "/timetable/schedules/$autoScheduleId" $null `
                        $admin.accessToken @(200,204,404) | Out-Null
                }
            }
        }

        $yearReview = Invoke-Api GET `
            "/academic-year-summaries/preview?academicYearId=fd-ay-2026&classId=fd-class-2026-11a1" `
            $null $admin.accessToken
        Assert-True ($yearReview.canFinalize -and $yearReview.metrics.totalStudents -eq 1) `
            "Lớp nguồn 2026-2027 sẵn sàng chốt, công bố và chuyển lớp"

        $teacherAssignments = @(Invoke-Api GET "/me/teaching-assignments" $null $teacher.accessToken)
        Assert-MinCount $teacherAssignments 1 "Giáo viên có phân công"
        $teacherPlans = @(Invoke-Api GET "/academic/training-plans" $null $teacher.accessToken)
        Assert-True (@($teacherPlans | Where-Object { $_.status -eq "PUBLISHED" }).Count -ge 3) `
            "Giáo viên thấy kế hoạch giáo dục đã công bố"
        $allowedTeacherSubjects = @("fd-sub-math", "sj-flag", "sj-homeroom")
        $foreignScope = @($teacherAssignments | Where-Object {
            $_.teacherId -ne "fd-teacher-001" -or
            $allowedTeacherSubjects -notcontains $_.subjectId
        })
        if ($foreignScope.Count -gt 0) { throw "Giáo viên thấy phân công ngoài chuyên môn/phạm vi" }
        Write-Host "  [PASS] Phạm vi giáo viên chỉ gồm Toán và hai hoạt động GVCN được phân công" -ForegroundColor Green
        Assert-MinCount (Invoke-Api GET "/assignments" $null $teacher.accessToken) 2 `
            "Giáo viên thấy bài tập nháp và đã phát hành của chính mình"
        Assert-MinCount (Invoke-Api GET "/assignments/fd-assignment-published/submissions" $null $teacher.accessToken) 3 `
            "Giáo viên có bài nộp đúng hạn, muộn và đã chấm"
        Assert-MinCount (Invoke-Api GET "/attendance?classId=fd-class-10a1" $null $teacher.accessToken) 1 `
            "Giáo viên có dữ liệu điểm danh lớp phụ trách"
        Assert-MinCount (Invoke-Api GET "/grades?classId=fd-class-10a1&subjectId=fd-sub-math&semesterId=fd-sem-2027-1" $null $teacher.accessToken) 1 `
            "Giáo viên có sổ điểm đúng lớp và môn được phân công"
        Assert-MinCount (Invoke-Api GET "/grades/fd-grade-001-math-final/change-logs" $null $teacher.accessToken) 1 `
            "Lịch sử sửa điểm có lý do"

        Assert-MinCount (Invoke-Api GET "/me/timetable?semesterId=fd-sem-2027-1" $null $student.accessToken) 1 "TKB học sinh"
        Assert-MinCount (Invoke-Api GET "/grades?semesterId=fd-sem-2027-1" $null $student.accessToken) 1 "Điểm học sinh"
        Assert-MinCount (Invoke-Api GET "/attendance" $null $student.accessToken) 1 "Điểm danh học sinh"
        $studentAssignments = @(Invoke-Api GET "/me/assignments" $null $student.accessToken)
        Assert-MinCount $studentAssignments 1 "Bài tập đã phát hành cho học sinh"
        if (@($studentAssignments | Where-Object { $_.status -eq "DRAFT" }).Count -gt 0) {
            throw "Học sinh nhìn thấy bài tập nháp"
        }
        Assert-MinCount (Invoke-Api GET "/academic/training-plans/published/me" $null $student.accessToken) 1 "Kế hoạch giáo dục đã công bố cho học sinh"

        $children = @(Invoke-Api GET "/me/children" $null $parent.accessToken)
        if ($children.Count -ne 2) { throw "Phụ huynh đại diện phải có đúng 2 con, thực tế $($children.Count)" }
        Write-Host "  [PASS] Phụ huynh đại diện có 2 con" -ForegroundColor Green
        Assert-MinCount (Invoke-Api GET "/students/fd-student-001/timetable?semesterId=fd-sem-2027-1" $null $parent.accessToken) 1 "TKB con của phụ huynh"
        Assert-MinCount (Invoke-Api GET "/students/fd-student-001/grades?semesterId=fd-sem-2027-1" $null $parent.accessToken) 1 "Điểm con của phụ huynh"
        Assert-MinCount (Invoke-Api GET "/students/fd-student-001/attendance" $null $parent.accessToken) 1 "Điểm danh con của phụ huynh"
        Assert-MinCount (Invoke-Api GET "/me/children/fd-student-001/assignments" $null $parent.accessToken) 1 "Bài tập con của phụ huynh"
        $parentPlan = Invoke-Api GET `
            "/academic/training-plans/published/me?studentId=fd-student-001" `
            $null $parent.accessToken
        Assert-True ($parentPlan.plan.status -eq "PUBLISHED" -and @($parentPlan.subjects).Count -gt 0) `
            "Phụ huynh thấy kế hoạch giáo dục đã công bố của đúng con"
        Assert-MinCount (Invoke-Api GET "/invoices?studentId=fd-student-001" $null $parent.accessToken) 1 "Hóa đơn riêng theo con"
        Assert-MinCount (Invoke-Api GET "/payment-history?studentId=fd-student-002" $null $parent.accessToken) 1 `
            "Phụ huynh xem được lịch sử thanh toán của con"
        if ($script:UseMinio) {
            $receipt = Invoke-Api GET "/payments/fd-payment-partial/receipt" $null $parent.accessToken
            Assert-True ($receipt.receipt.status -eq "ISSUED") "Phụ huynh tải được biên nhận đã phát hành"
        } else {
            Write-Host "  [SKIP] Tải PDF biên nhận vì MinIO được chủ động bỏ qua" -ForegroundColor Yellow
        }
        Invoke-Api GET "/students/fd-student-025/grades" $null $parent.accessToken @(403) | Out-Null
        Invoke-Api GET "/invoices?studentId=fd-student-025" $null $parent.accessToken @(403) | Out-Null
        Write-Host "  [PASS] Parent không đọc được điểm/hóa đơn của học sinh không liên kết" -ForegroundColor Green

        Assert-MinCount (Invoke-Api GET "/notifications" $null $admin.accessToken) 1 "Thông báo Admin"
        Assert-MinCount (Invoke-Api GET "/notifications" $null $teacher.accessToken) 1 "Thông báo giáo viên"
        Assert-MinCount (Invoke-Api GET "/notifications" $null $student.accessToken) 1 "Thông báo học sinh"
        Assert-MinCount (Invoke-Api GET "/notifications" $null $parent.accessToken) 1 "Thông báo phụ huynh"
        Assert-MinCount (Invoke-Api GET "/chat/messages?withUserId=fd-teacher-001" $null $parent.accessToken) 1 "Chat phụ huynh - GVCN"
        Assert-MinCount (Invoke-Api GET "/exam-periods/me/schedule" $null $student.accessToken) 1 "Lịch thi học sinh"
        Assert-MinCount (Invoke-Api GET "/finance/reconciliations" $null $admin.accessToken) 1 `
            "Admin có phiên đối soát cân bằng để kiểm tra"

        Write-Host "[PASS] API smoke Full Demo hoàn tất." -ForegroundColor Green
    } finally {
        if ($process -and -not $process.HasExited) {
            Stop-Process -Id $process.Id -Force -ErrorAction SilentlyContinue
            $process.WaitForExit()
        }
    }
}

if (-not $AllowAnyDatabase -and $Database -ne "sse_db") {
    throw "Script mặc định chỉ cho phép database 'sse_db'. Dùng -AllowAnyDatabase chỉ khi kiểm thử trên database tạm."
}

$script:Psql = Resolve-Tool $PsqlPath "psql"
$script:Maven = Resolve-Tool $MavenPath "maven"
$script:Java = Resolve-Tool $JavaPath "java"

$oldPgPassword = $env:PGPASSWORD
$oldEnvironment = @{}
$managedEnvironment = @(
    "SSE_DB_URL", "SSE_DB_USER", "SSE_DB_PASSWORD", "SSE_SEED_ENABLED",
    "SSE_SEED_DATASET", "SSE_SEED_FILES", "SSE_SEED_EXIT_AFTER_RUN",
    "SSE_EVENTS_RABBITMQ_ENABLED", "SSE_EVENTS_LOCAL_LISTENER_ENABLED",
    "SSE_NOTIFICATION_WORKER_ENABLED", "SSE_AUDIT_MONGO_ENABLED"
)
foreach ($name in $managedEnvironment) {
    $oldEnvironment[$name] = [Environment]::GetEnvironmentVariable($name, "Process")
}

try {
    $env:PGPASSWORD = $DbPassword
    Write-Host "[CHECK] Kết nối PostgreSQL $DbHost`:$DbPort/$Database ..." -ForegroundColor Cyan
    Invoke-Psql @("--host", $DbHost, "--port", "$DbPort", "--username", $DbUser,
        "--dbname", $Database, "--set", "ON_ERROR_STOP=1", "--tuples-only", "--command",
        "SELECT current_database() || ' @ ' || inet_server_addr() || ':' || inet_server_port();")

    Write-Host "[BUILD] Đóng gói backend và tài nguyên migration/seed ..." -ForegroundColor Cyan
    Push-Location $RepoRoot
    try {
        & $script:Maven "-pl" "services/app" "-am" "-DskipTests" "package"
        if ($LASTEXITCODE -ne 0) { throw "Maven package thất bại với mã $LASTEXITCODE" }
    } finally {
        Pop-Location
    }
    if (-not (Test-Path -LiteralPath $AppJar -PathType Leaf)) {
        throw "Không tìm thấy JAR sau build: $AppJar"
    }

    if ([string]::IsNullOrWhiteSpace($Confirm)) {
        Write-Host "`nCẢNH BÁO: thao tác sẽ xóa dữ liệu nghiệp vụ/demo trong '$Database'." -ForegroundColor Yellow
        Write-Host "Schema, lịch sử Flyway, role/quyền hệ thống và Admin chính không phải demo được giữ lại." -ForegroundColor Yellow
        $Confirm = Read-Host "Nhập chính xác '$ExpectedConfirmation' để tiếp tục"
    }
    if ($Confirm -cne $ExpectedConfirmation) {
        throw "Đã hủy: chuỗi xác nhận không khớp '$ExpectedConfirmation'."
    }

    $legacySchemaCollision = Get-PsqlScalar @"
SELECT CASE WHEN
    coalesce((SELECT max(version::integer) FROM public.flyway_schema_history
              WHERE success = true AND version ~ '^[0-9]+$'), 0) < 6
    AND to_regclass('public.academic_training_plans') IS NOT NULL
THEN 't' ELSE 'f' END;
"@
    if ($legacySchemaCollision -eq "t") {
        throw "Database có schema Hibernate cũ nhưng Flyway chưa tới V6. Script dừng để không drop schema hoặc giả đánh dấu migration. Hãy dùng database sạch do Flyway quản lý, rồi chạy lại script."
    }

    Write-Host "[MIGRATION] Kiểm tra schema cũ trước khi Flyway chạy ..." -ForegroundColor Cyan
    Invoke-Psql @("--host", $DbHost, "--port", "$DbPort", "--username", $DbUser,
        "--dbname", $Database, "--set", "ON_ERROR_STOP=1", "--file", $LegacyPreflightSql)
    if ((Get-PsqlScalar "SELECT to_regclass('public.users') IS NOT NULL;") -eq "t") {
        Write-Host "[RESET] Xóa dữ liệu nghiệp vụ cũ trước khi thêm ràng buộc Flyway ..." -ForegroundColor Cyan
        Invoke-Psql @("--host", $DbHost, "--port", "$DbPort", "--username", $DbUser,
            "--dbname", $Database, "--set", "ON_ERROR_STOP=1", "--file", $ResetSql)
    }

    $useMinio = $false
    if (-not $SkipMinio) {
        $useMinio = Test-HttpReady "http://127.0.0.1:9000/minio/health/live"
        if (-not $useMinio) {
            $docker = Get-Command "docker" -ErrorAction SilentlyContinue
            if ($docker -and (Test-Path -LiteralPath $ComposeFile)) {
                Write-Host "[MINIO] Khởi động MinIO local để seed file thật ..." -ForegroundColor Cyan
                Push-Location $RepoRoot
                try {
                    & $docker.Source "compose" "-f" $ComposeFile "up" "-d" "minio" "minio-init"
                } finally { Pop-Location }
                for ($i = 0; $i -lt 40 -and -not $useMinio; $i++) {
                    Start-Sleep -Milliseconds 750
                    $useMinio = Test-HttpReady "http://127.0.0.1:9000/minio/health/live"
                }
            }
        }
    }
    if ($useMinio) {
        Write-Host "[MINIO] Sẵn sàng; sẽ seed file đề bài, bài nộp, ảnh đối soát và PDF biên nhận." -ForegroundColor Green
    } else {
        Write-Warning "MinIO không sẵn sàng: bỏ qua binary/file metadata; các module còn lại vẫn được seed."
    }
    $script:UseMinio = $useMinio

    $env:SSE_DB_URL = "jdbc:postgresql://$DbHost`:$DbPort/$Database"
    $env:SSE_DB_USER = $DbUser
    $env:SSE_DB_PASSWORD = $DbPassword
    $env:SSE_SEED_ENABLED = "true"
    $env:SSE_SEED_DATASET = "full-demo"
    $env:SSE_SEED_FILES = if ($useMinio) { "true" } else { "false" }
    $env:SSE_SEED_EXIT_AFTER_RUN = "true"
    $env:SSE_EVENTS_RABBITMQ_ENABLED = "false"
    $env:SSE_EVENTS_LOCAL_LISTENER_ENABLED = "false"
    $env:SSE_NOTIFICATION_WORKER_ENABLED = "false"
    $env:SSE_AUDIT_MONGO_ENABLED = "false"

    Write-Host "[SEED] Flyway migrate -> reset dữ liệu nghiệp vụ/demo -> tạo Full Demo ..." -ForegroundColor Cyan
    & $script:Java "-jar" $AppJar "--spring.main.web-application-type=none"
    if ($LASTEXITCODE -ne 0) { throw "Quá trình migrate/seed thất bại với mã $LASTEXITCODE" }

    Write-Host "[VERIFY] Kiểm tra số lượng, FK nghiệp vụ và xung đột lịch ..." -ForegroundColor Cyan
    Invoke-Psql @("--host", $DbHost, "--port", "$DbPort", "--username", $DbUser,
        "--dbname", $Database, "--set", "ON_ERROR_STOP=1", "--file", $VerifySql)

    # API smoke không được chạy trong seed mode, tránh reset lần thứ hai.
    $env:SSE_SEED_ENABLED = "false"
    $env:SSE_SEED_EXIT_AFTER_RUN = "false"
    $env:SSE_SEED_FILES = "false"
    if (-not $SkipApiSmoke) { Run-ApiSmoke }

    Write-Host "`n================ TÀI KHOẢN DEMO ĐẠI DIỆN ================" -ForegroundColor Cyan
    @(
        [pscustomobject]@{ VaiTro="Admin"; HoTen="Quản trị Demo 01"; Username="demo.admin.01"; Email="demo.admin.01@sse.local"; SoDienThoai="0901000001"; MatKhau="Admin@123"; GhiChu="Admin chính của Full Demo" },
        [pscustomobject]@{ VaiTro="Admin"; HoTen="Quản trị Demo 02"; Username="demo.admin.02"; Email="demo.admin.02@sse.local"; SoDienThoai="0901000002"; MatKhau="Admin@123"; GhiChu="Đối soát/tài chính trong Admin" },
        [pscustomobject]@{ VaiTro="Giáo viên"; HoTen="Giáo viên Toán 1"; Username="demo.gv.001"; Email="demo.gv.001@sse.local"; SoDienThoai="0911000001"; MatKhau="Teacher@123"; GhiChu="Toán, GVCN 10A1" },
        [pscustomobject]@{ VaiTro="Giáo viên"; HoTen="Giáo viên Ngữ văn 1"; Username="demo.gv.002"; Email="demo.gv.002@sse.local"; SoDienThoai="0911000002"; MatKhau="Teacher@123"; GhiChu="Ngữ văn, GVCN 10A2" },
        [pscustomobject]@{ VaiTro="Giáo viên"; HoTen="Giáo viên Tiếng Anh 1"; Username="demo.gv.003"; Email="demo.gv.003@sse.local"; SoDienThoai="0911000003"; MatKhau="Teacher@123"; GhiChu="Tiếng Anh, GVCN 11A1" },
        [pscustomobject]@{ VaiTro="Học sinh"; HoTen="Học sinh Demo 001"; Username="demo.hs.001 / HS270001"; Email="demo.hs.001@sse.local"; SoDienThoai="0922000001"; MatKhau="Student@123"; GhiChu="Khối 10 · 10A1" },
        [pscustomobject]@{ VaiTro="Học sinh"; HoTen="Học sinh Demo 021"; Username="demo.hs.021 / HS270021"; Email="demo.hs.021@sse.local"; SoDienThoai="0922000021"; MatKhau="Student@123"; GhiChu="Khối 11 · 11A1" },
        [pscustomobject]@{ VaiTro="Học sinh"; HoTen="Học sinh Demo 041"; Username="demo.hs.041 / HS270041"; Email="demo.hs.041@sse.local"; SoDienThoai="0922000041"; MatKhau="Student@123"; GhiChu="Khối 12 · 12A1" },
        [pscustomobject]@{ VaiTro="Phụ huynh"; HoTen="Phụ huynh Demo 001"; Username="demo.ph.001"; Email="demo.ph.001@sse.local"; SoDienThoai="0933000001"; MatKhau="Parent@123"; GhiChu="Có 2 con: HS270001, HS270002" },
        [pscustomobject]@{ VaiTro="Phụ huynh"; HoTen="Phụ huynh Demo 013"; Username="demo.ph.013"; Email="demo.ph.013@sse.local"; SoDienThoai="0933000013"; MatKhau="Parent@123"; GhiChu="Con HS270025 · 11A1" },
        [pscustomobject]@{ VaiTro="Phụ huynh"; HoTen="Phụ huynh Demo 033"; Username="demo.ph.033"; Email="demo.ph.033@sse.local"; SoDienThoai="0933000033"; MatKhau="Parent@123"; GhiChu="Con HS270045 · 12A1" }
    ) | Format-Table -AutoSize -Wrap

    Write-Host "`n[OK] Full Demo đã sẵn sàng trong PostgreSQL '$Database'." -ForegroundColor Green
    Write-Host "Chạy lại an toàn/idempotent bằng lệnh:" -ForegroundColor Cyan
    Write-Host "powershell.exe -NoProfile -ExecutionPolicy Bypass ``"
    Write-Host "  -File .\scripts\reset-and-seed-full-demo.ps1"
    Write-Host "Để chạy không tương tác: thêm -Confirm `"RESET $Database`"."
} finally {
    $env:PGPASSWORD = $oldPgPassword
    foreach ($name in $managedEnvironment) {
        [Environment]::SetEnvironmentVariable($name, $oldEnvironment[$name], "Process")
    }
}
