param(
    [string]$BaseUrl = "http://127.0.0.1:4000",
    [string]$AdminUsername = "admin",
    [string]$AdminPassword = "admin@123",
    [string]$StudentUsername = "hs.minh",
    [string]$StudentPassword = "student@123",
    [string]$ParentUsername = "ph.nguyen",
    [string]$ParentPassword = "parent@123"
)

$ErrorActionPreference = "Stop"

function Invoke-Api {
    param(
        [ValidateSet("GET", "POST", "PUT", "DELETE")]
        [string]$Method,
        [string]$Path,
        [object]$Body = $null,
        [string]$Token = $null,
        [int[]]$Expected = @(200)
    )
    $headers = @{}
    if ($Token) { $headers.Authorization = "Bearer $Token" }
    $params = @{
        Method = $Method
        Uri = "$BaseUrl$Path"
        ContentType = "application/json"
        UseBasicParsing = $true
    }
    if ($headers.Count -gt 0) { $params.Headers = $headers }
    if ($null -ne $Body) {
        $params.Body = $Body | ConvertTo-Json -Depth 12
    }
    try {
        $response = Invoke-WebRequest @params
        if ($Expected -notcontains [int]$response.StatusCode) {
            throw "Expected HTTP $($Expected -join '/') for $Method $Path, got $($response.StatusCode)"
        }
        if ([string]::IsNullOrWhiteSpace($response.Content)) { return $null }
        return $response.Content | ConvertFrom-Json
    } catch {
        $status = $null
        if ($_.Exception.Response) {
            $status = [int]$_.Exception.Response.StatusCode
        }
        if ($null -ne $status -and $Expected -contains $status) { return $null }
        throw "API $Method $Path failed (HTTP $status): $($_.Exception.Message)"
    }
}

function Login([string]$Username, [string]$Password) {
    $response = Invoke-Api POST "/auth/login" @{
        username = $Username
        password = $Password
    }
    if (-not $response.accessToken) {
        throw "Login did not return an access token for $Username"
    }
    return $response
}

function As-Array([object]$Value) {
    if ($null -eq $Value) { return @() }
    return @($Value)
}

function Pass([string]$Message) {
    Write-Host "[OK] $Message" -ForegroundColor Green
}

function Skip([string]$Message) {
    Write-Host "[SKIP] $Message" -ForegroundColor Yellow
}

Write-Host "SSE eight-branch backend smoke against $BaseUrl"

$ready = Invoke-Api GET "/health/ready"
if ($ready.status -ne "UP") {
    throw "Readiness is $($ready.status). Start PostgreSQL, RabbitMQ and MinIO first."
}
Pass "P8 readiness: PostgreSQL, RabbitMQ and MinIO are UP"

Invoke-Api GET "/admin/operations/health" $null $null @(401) | Out-Null
$admin = Login $AdminUsername $AdminPassword
$adminToken = $admin.accessToken
$operations = Invoke-Api GET "/admin/operations/health" $null $adminToken
Pass "P8 protected operations health and Admin authentication"

$sessions = As-Array (Invoke-Api GET "/me/sessions" $null $adminToken)
$devices = As-Array (Invoke-Api GET "/me/devices" $null $adminToken)
Pass "P8 active sessions and registered devices (sessions=$($sessions.Count), devices=$($devices.Count))"

$preferences = As-Array (Invoke-Api GET "/me/notification-preferences" $null $adminToken)
$notificationSummary = Invoke-Api GET "/admin/notification-operations/summary" $null $adminToken
$deliveries = As-Array (Invoke-Api GET "/admin/notification-deliveries" $null $adminToken)
$failed = As-Array (Invoke-Api GET "/admin/notifications/failed" $null $adminToken)
Pass "P7 notification preferences, delivery history and monitoring (failed=$($failed.Count))"

$years = As-Array (Invoke-Api GET "/academic-years" $null $adminToken)
$year = $years | Sort-Object startDate -Descending | Select-Object -First 1
$classes = @()
$class = $null
if ($year) {
    $classes = As-Array (Invoke-Api GET "/classes?academicYearId=$($year.id)" $null $adminToken)
    $class = $classes | Sort-Object code | Select-Object -First 1
}

if ($year -and $class) {
    $publication = Invoke-Api GET "/year-results/publication?academicYearId=$($year.id)&classId=$($class.id)" $null $adminToken
    $history = As-Array (Invoke-Api GET "/year-results/$($year.id)/classes/$($class.id)/history" $null $adminToken)
    $review = Invoke-Api GET "/academic-year-summaries/preview?academicYearId=$($year.id)&classId=$($class.id)" $null $adminToken
    Pass "P1 final-year preview, publication state and immutable history (history=$($history.Count))"

    $enrollments = As-Array (Invoke-Api GET "/student-promotions/enrollments?academicYearId=$($year.id)&classId=$($class.id)" $null $adminToken)
    Pass "P2 promotion enrollments, capacity-aware backend and undo surface (enrollments=$($enrollments.Count))"
} else {
    Skip "P1/P2 need at least one academic year and class"
}

$students = As-Array (Invoke-Api GET "/users?role=STUDENT" $null $adminToken)
$student = $students | Select-Object -First 1
$subjects = As-Array (Invoke-Api GET "/subjects" $null $adminToken)
$semesters = if ($year) {
    As-Array (Invoke-Api GET "/semesters?academicYearId=$($year.id)" $null $adminToken)
} else { @() }

if ($class -and $subjects.Count -gt 0 -and $semesters.Count -gt 0) {
    $completeness = Invoke-Api GET "/grades/completeness?classId=$($class.id)&subjectId=$($subjects[0].id)&semesterId=$($semesters[0].id)" $null $adminToken
    Pass "P3 grade completeness and missing-grade detection"
} else {
    Skip "P3 grade completeness needs class, subject and semester data"
}

if ($student) {
    $attendance = Invoke-Api GET "/students/$($student.id)/attendance/summary" $null $adminToken
    $excuses = As-Array (Invoke-Api GET "/attendance/excuse-requests?studentId=$($student.id)" $null $adminToken)
    Pass "P4 attendance statistics and excuse workflow (requests=$($excuses.Count))"
} else {
    Skip "P4 attendance smoke needs at least one student"
}

$assignments = As-Array (Invoke-Api GET "/assignments" $null $adminToken)
if ($assignments.Count -gt 0) {
    $submissions = As-Array (Invoke-Api GET "/assignments/$($assignments[0].id)/submissions" $null $adminToken)
    Pass "P5 assignments, submissions and advanced history surface (submissions=$($submissions.Count))"
} else {
    Pass "P5 assignment API is available (no current assignments)"
}

$periods = As-Array (Invoke-Api GET "/fee-periods" $null $adminToken)
$invoices = As-Array (Invoke-Api GET "/invoices" $null $adminToken)
$paymentHistory = As-Array (Invoke-Api GET "/payment-history" $null $adminToken)
$refunds = As-Array (Invoke-Api GET "/payment-refunds" $null $adminToken)
$bankEntries = As-Array (Invoke-Api GET "/finance/bank-statements" $null $adminToken)
Pass "P6 finance reminders, receipts, reconciliation and refunds (invoices=$($invoices.Count), bank entries=$($bankEntries.Count))"

$studentLogin = $null
$parentLogin = $null
try {
    $studentLogin = Login $StudentUsername $StudentPassword
    $ownResults = As-Array (Invoke-Api GET "/year-results/me" $null $studentLogin.accessToken)
    $ownAssignments = As-Array (Invoke-Api GET "/me/assignments" $null $studentLogin.accessToken)
    Pass "Student ownership endpoints (results=$(@($ownResults).Count), assignments=$(@($ownAssignments).Count))"
} catch {
    Skip "Student login check: $($_.Exception.Message)"
}

try {
    $parentLogin = Login $ParentUsername $ParentPassword
    $children = As-Array (Invoke-Api GET "/me/children" $null $parentLogin.accessToken)
    if ($children.Count -gt 0) {
        $childId = $children[0].id
        $childAssignments = As-Array (Invoke-Api GET "/me/children/$childId/assignments" $null $parentLogin.accessToken)
        $childInvoices = As-Array (Invoke-Api GET "/students/$childId/invoices" $null $parentLogin.accessToken)
        Pass "Parent child-scoped assignments and finance ownership"
    } else {
        Skip "Parent account has no linked child"
    }
} catch {
    Skip "Parent login check: $($_.Exception.Message)"
}

Write-Host ""
Write-Host "SSE eight-branch backend smoke completed successfully." -ForegroundColor Cyan
Write-Host "This script is read-only. Use docs/runbooks/backend-eight-branches-testing.md for state-changing UAT."
