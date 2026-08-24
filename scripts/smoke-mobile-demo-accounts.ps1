param(
    [string]$BaseUrl = "https://sse-school-api.calmocean-02e7d173.southeastasia.azurecontainerapps.io"
)

$ErrorActionPreference = "Stop"
$ProgressPreference = "SilentlyContinue"

function Invoke-Api {
    param(
        [ValidateSet("GET", "POST")][string]$Method,
        [string]$Path,
        [object]$Body = $null,
        [string]$Token = $null,
        [int[]]$ExpectedStatus = @(200)
    )
    $headers = @{}
    if ($Token) { $headers.Authorization = "Bearer $Token" }
    $request = @{
        Method = $Method
        Uri = "$BaseUrl$Path"
        Headers = $headers
        ContentType = "application/json; charset=utf-8"
        UseBasicParsing = $true
    }
    if ($null -ne $Body) { $request.Body = $Body | ConvertTo-Json -Depth 10 }
    try {
        $response = Invoke-WebRequest @request
        if ($ExpectedStatus -notcontains [int]$response.StatusCode) {
            throw "HTTP $($response.StatusCode) cho $Method $Path"
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

function Login {
    param([string]$Username, [string]$Password, [string]$Role)
    $result = Invoke-Api POST "/auth/login" @{
        username = $Username
        password = $Password
        platform = "MOBILE_DEMO_SMOKE"
        deviceName = "smoke-mobile-demo-accounts.ps1"
    }
    if (-not $result.accessToken) { throw "$Username không trả access token" }
    if ($result.user.role -ne $Role) {
        throw "$Username trả role '$($result.user.role)', cần '$Role'"
    }
    Write-Host "  [PASS] Login $Username ($Role)" -ForegroundColor Green
    return $result
}

function Assert-Count {
    param([object]$Value, [int]$Minimum, [string]$Label)
    $count = 0
    if ($null -ne $Value) {
        $collectionProperty = @("content", "items", "data") |
            Where-Object { $Value.PSObject.Properties.Name -contains $_ } |
            Select-Object -First 1
        if ($collectionProperty) {
            $count = @($Value.$collectionProperty).Count
        } else {
            $count = @($Value).Count
        }
    }
    if ($count -lt $Minimum) { throw "$Label cần >= $Minimum, thực tế $count" }
    Write-Host "  [PASS] $Label ($count)" -ForegroundColor Green
}

function Assert-Object {
    param([object]$Value, [string]$Label)
    if ($null -eq $Value) { throw "$Label không có dữ liệu" }
    Write-Host "  [PASS] $Label" -ForegroundColor Green
}

Write-Host "SSE Mobile representative-account smoke against $BaseUrl" -ForegroundColor Cyan

$admins = @(
    Login "demo.admin.01" "Admin@123" "ADMIN"
    Login "demo.admin.02" "Admin@123" "ADMIN"
)
foreach ($admin in $admins) {
    Assert-Object (Invoke-Api GET "/dashboard" $null $admin.accessToken) `
        "Admin $($admin.user.username) có dashboard dữ liệu thật"
}

$teachers = @(
    Login "demo.gv.001" "Teacher@123" "TEACHER"
    Login "demo.gv.002" "Teacher@123" "TEACHER"
    Login "demo.gv.003" "Teacher@123" "TEACHER"
)
foreach ($teacher in $teachers) {
    $name = $teacher.user.username
    Assert-Count (Invoke-Api GET "/me/teaching-assignments?semesterId=fd-sem-2027-1" $null $teacher.accessToken) 1 "$name · phân công"
    Assert-Count (Invoke-Api GET "/me/timetable?semesterId=fd-sem-2027-1" $null $teacher.accessToken) 1 "$name · lịch dạy"
    Assert-Count (Invoke-Api GET "/me/assignments" $null $teacher.accessToken) 1 "$name · bài tập"
    Assert-Count (Invoke-Api GET "/exam-periods/me/schedule" $null $teacher.accessToken) 1 "$name · lịch coi thi"
    Assert-Count (Invoke-Api GET "/notifications" $null $teacher.accessToken) 1 "$name · thông báo"
    Assert-Count (Invoke-Api GET "/chat/threads" $null $teacher.accessToken) 1 "$name · hội thoại"
}

$studentCases = @(
    @{ Username="demo.hs.001"; Id="fd-student-001" },
    @{ Username="demo.hs.021"; Id="fd-student-021" },
    @{ Username="demo.hs.041"; Id="fd-student-041" }
)
foreach ($case in $studentCases) {
    $student = Login $case.Username "Student@123" "STUDENT"
    $token = $student.accessToken
    $name = $case.Username
    Assert-Count (Invoke-Api GET "/me/timetable?semesterId=fd-sem-2027-1" $null $token) 1 "$name · TKB"
    Assert-Count (Invoke-Api GET "/grades?semesterId=fd-sem-2027-1" $null $token) 1 "$name · điểm"
    Assert-Count (Invoke-Api GET "/attendance" $null $token) 1 "$name · điểm danh"
    Assert-Count (Invoke-Api GET "/attendance/excuse-requests" $null $token) 1 "$name · giải trình chuyên cần"
    Assert-Count (Invoke-Api GET "/me/assignments" $null $token) 1 "$name · bài tập"
    Assert-Count (Invoke-Api GET "/me/submissions" $null $token) 1 "$name · bài nộp"
    Assert-Count (Invoke-Api GET "/exam-periods/me/schedule" $null $token) 1 "$name · lịch thi"
    Assert-Object (Invoke-Api GET "/academic/training-plans/published/me" $null $token) "$name · kế hoạch giáo dục"
    Assert-Count (Invoke-Api GET "/notifications" $null $token) 1 "$name · thông báo"
    Assert-Count (Invoke-Api GET "/chat/threads" $null $token) 1 "$name · hội thoại"
    Assert-Count (Invoke-Api GET "/clubs" $null $token) 2 "$name · danh sách CLB"
    Assert-Count (Invoke-Api GET "/me/club-registrations" $null $token) 1 "$name · CLB đã đăng ký"
}

$parentCases = @(
    @{ Username="demo.ph.001"; StudentId="fd-student-001"; Children=2 },
    @{ Username="demo.ph.013"; StudentId="fd-student-025"; Children=1 },
    @{ Username="demo.ph.033"; StudentId="fd-student-045"; Children=1 }
)
foreach ($case in $parentCases) {
    $parent = Login $case.Username "Parent@123" "PARENT"
    $token = $parent.accessToken
    $name = $case.Username
    $studentId = $case.StudentId
    $children = @(Invoke-Api GET "/me/children" $null $token)
    if ($children.Count -ne $case.Children) {
        throw "$name cần $($case.Children) con, thực tế $($children.Count)"
    }
    if (-not ($children | Where-Object { $_.id -eq $studentId })) {
        throw "$name không liên kết đúng $studentId"
    }
    Write-Host "  [PASS] $name · liên kết con đúng ($($children.Count))" -ForegroundColor Green
    Assert-Count (Invoke-Api GET "/students/$studentId/timetable?semesterId=fd-sem-2027-1" $null $token) 1 "$name · TKB con"
    Assert-Count (Invoke-Api GET "/students/$studentId/grades?semesterId=fd-sem-2027-1" $null $token) 1 "$name · điểm con"
    Assert-Count (Invoke-Api GET "/students/$studentId/attendance" $null $token) 1 "$name · điểm danh con"
    Assert-Count (Invoke-Api GET "/attendance/excuse-requests?studentId=$studentId" $null $token) 1 "$name · giải trình chuyên cần con"
    Assert-Count (Invoke-Api GET "/me/children/$studentId/assignments" $null $token) 1 "$name · bài tập con"
    Assert-Count (Invoke-Api GET "/me/children/$studentId/submissions" $null $token) 1 "$name · bài nộp con"
    Assert-Count (Invoke-Api GET "/exam-periods/students/$studentId/schedule" $null $token) 1 "$name · lịch thi con"
    Assert-Object (Invoke-Api GET "/academic/training-plans/published/me?studentId=$studentId" $null $token) "$name · kế hoạch giáo dục con"
    Assert-Count (Invoke-Api GET "/students/$studentId/invoices" $null $token) 1 "$name · hóa đơn con"
    Assert-Count (Invoke-Api GET "/notifications" $null $token) 1 "$name · thông báo"
    Assert-Count (Invoke-Api GET "/chat/threads" $null $token) 1 "$name · hội thoại"
    Assert-Count (Invoke-Api GET "/me/club-registrations?studentId=$studentId" $null $token) 1 "$name · CLB của con"

    $foreignStudent = if ($studentId -eq "fd-student-021") { "fd-student-041" } else { "fd-student-021" }
    Invoke-Api GET "/students/$foreignStudent/grades" $null $token @(403) | Out-Null
    Write-Host "  [PASS] $name không xem được điểm học sinh ngoài quan hệ" -ForegroundColor Green
}

Write-Host "[PASS] Toàn bộ tài khoản đại diện đã đủ dữ liệu Mobile và đúng phạm vi quyền." -ForegroundColor Cyan
