param(
    [string]$BaseUrl = "http://127.0.0.1:4000"
)

$ErrorActionPreference = "Stop"

function Invoke-Json {
    param(
        [ValidateSet("GET", "POST", "PUT", "DELETE")]
        [string]$Method,
        [string]$Path,
        [object]$Body = $null,
        [string]$Token = $null,
        [int[]]$Expected = @(200)
    )

    $headers = @{}
    if ($Token) { $headers["Authorization"] = "Bearer $Token" }
    $params = @{
        Method = $Method
        Uri = "$BaseUrl$Path"
        ContentType = "application/json"
        UseBasicParsing = $true
    }
    if ($headers.Count -gt 0) { $params.Headers = $headers }
    if ($null -ne $Body) { $params.Body = ($Body | ConvertTo-Json -Depth 10) }

    try {
        $response = Invoke-WebRequest @params
        if ($Expected -notcontains [int]$response.StatusCode) {
            throw "Expected HTTP $($Expected -join '/') for $Method $Path, got $($response.StatusCode)"
        }
        if ([string]::IsNullOrWhiteSpace($response.Content)) { return $null }
        return $response.Content | ConvertFrom-Json
    } catch {
        $status = $_.Exception.Response.StatusCode.value__
        if ($status -and ($Expected -contains [int]$status)) { return $null }
        throw
    }
}

function Login {
    param([string]$Username, [string]$Password)
    $body = @{ username = $Username; password = $Password }
    $res = Invoke-Json POST "/auth/login" $body
    if (-not $res.accessToken -or -not $res.refreshToken) {
        throw "Login did not return both tokens for $Username"
    }
    return $res
}

function As-Array {
    param([object]$Value)
    if ($null -eq $Value) { return @() }
    $props = @($Value.PSObject.Properties.Name)
    if (($props -contains "value") -and ($props -contains "Count")) {
        return @($Value.value)
    }
    return @($Value)
}

function Wait-NotificationCount {
    param(
        [string]$Token,
        [int]$MinCount,
        [int]$TimeoutSeconds = 25
    )
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    do {
        $items = As-Array (Invoke-Json GET "/notifications" $null $Token)
        if ($items.Count -ge $MinCount) { return $items }
        Start-Sleep -Milliseconds 500
    } while ((Get-Date) -lt $deadline)
    throw "Expected at least $MinCount notifications after async worker processing"
}

Write-Host "SSE smoke against $BaseUrl"

$admin = Login "admin" "admin@123"
$teacher1 = Login "gv.hoa" "teacher@123"
$teacher2 = Login "gv.minh" "teacher@123"
$student = Login "hs.an" "student@123"
$parent = Login "ph.pham" "parent@123"
Write-Host "[OK] login for 4 roles"

$refreshed = Invoke-Json POST "/auth/refresh" @{ refreshToken = $admin.refreshToken }
if (-not $refreshed.accessToken -or -not $refreshed.refreshToken) { throw "Refresh did not rotate tokens" }
Invoke-Json POST "/auth/refresh" @{ refreshToken = $admin.refreshToken } $null @(401) | Out-Null
Write-Host "[OK] refresh token rotation rejects old token"

$users = Invoke-Json GET "/users" $null $refreshed.accessToken
if ($users.Count -lt 4) { throw "Expected seeded users" }
Write-Host "[OK] admin/teacher can list users"

Invoke-Json POST "/academic/high-school-defaults/ensure" $null $refreshed.accessToken | Out-Null
$teacherAssignments = As-Array (Invoke-Json GET "/me/teacher-class-subjects" $null $teacher1.accessToken)
if ($teacherAssignments.Count -lt 1) { throw "Expected teacher assignments for teacher1" }
$oldSmokeAssignments = As-Array (Invoke-Json GET "/teacher-class-subjects?classId=c-12a10&subjectId=sj-math&semesterId=sm-2025-1" $null $refreshed.accessToken)
foreach ($a in $oldSmokeAssignments) {
    if ($a.id) { Invoke-Json DELETE "/teacher-class-subjects/$($a.id)" $null $refreshed.accessToken | Out-Null }
}
$smokeAssignment = Invoke-Json POST "/teacher-class-subjects" @{
    teacherId = "u-teacher-2"
    classId = "c-12a10"
    subjectId = "sj-math"
    semesterId = "sm-2025-1"
} $refreshed.accessToken
Invoke-Json POST "/teacher-class-subjects" @{
    teacherId = "u-teacher-1"
    classId = "c-12a10"
    subjectId = "sj-math"
    semesterId = "sm-2025-1"
} $refreshed.accessToken @(409) | Out-Null
$teacher2Assignments = As-Array (Invoke-Json GET "/me/teacher-class-subjects" $null $teacher2.accessToken)
if (-not (@($teacher2Assignments | Where-Object { $_.id -eq $smokeAssignment.id }).Count)) {
    throw "Teacher2 cannot see smoke assignment"
}
Invoke-Json DELETE "/teacher-class-subjects/$($smokeAssignment.id)" $null $refreshed.accessToken | Out-Null
Write-Host "[OK] teacher-class-subject assignments"

try {
    Invoke-Json POST "/users/u-teacher-2/lock" $null $refreshed.accessToken | Out-Null
    Invoke-Json POST "/auth/login" @{ username = "gv.minh"; password = "teacher@123" } $null @(403) | Out-Null
    Write-Host "[OK] locked user cannot login"
} finally {
    Invoke-Json POST "/users/u-teacher-2/unlock" $null $refreshed.accessToken | Out-Null
}

$children = Invoke-Json GET "/me/children" $null $parent.accessToken
if ($children.Count -lt 1) { throw "Parent children list is empty" }
Invoke-Json GET "/grades?studentId=u-admin-1" $null $parent.accessToken @(403) | Out-Null
Write-Host "[OK] parent child access control"

$firstChild = (As-Array $children | Select-Object -First 1)
$studentSlots = As-Array (Invoke-Json GET "/students/u-student-1/timetable" $null $student.accessToken)
if ($studentSlots.Count -lt 1) { throw "Expected student timetable slots" }
$childSlots = As-Array (Invoke-Json GET "/students/$($firstChild.id)/timetable" $null $parent.accessToken)
if ($childSlots.Count -lt 1) { throw "Expected child timetable slots for parent" }
Invoke-Json GET "/students/u-admin-1/timetable" $null $parent.accessToken @(403) | Out-Null
Invoke-Json GET "/timetableSlots?classId=c-10a1" $null $parent.accessToken @(403) | Out-Null
Write-Host "[OK] student/parent timetable access control"

$conflictSlot = @{
    classId = "c-10a1"
    subjectId = "sj-math"
    teacherId = "u-teacher-1"
    roomCode = "P201"
    dayOfWeek = "MON"
    periodNo = 1
    startTime = "07:00"
    endTime = "07:45"
    semesterId = "sm-2025-1"
}
Invoke-Json POST "/timetableSlots" $conflictSlot $refreshed.accessToken @(409) | Out-Null
Write-Host "[OK] timetable conflict returns 409"

$badGrade = @{
    subjectId = "sj-math"
    semesterId = "sm-2025-1"
    category = "FINAL"
    reason = "smoke unauthorized"
    entries = @(@{ studentId = "u-student-1"; score = 9.1; note = "should be forbidden" })
}
Invoke-Json POST "/grades/bulk" $badGrade $teacher2.accessToken @(403) | Out-Null
Write-Host "[OK] teacher cannot grade unassigned class/subject"

$goodGrade = @{
    subjectId = "sj-math"
    semesterId = "sm-2025-1"
    category = "FINAL"
    reason = "smoke authorized"
    entries = @(@{ studentId = "u-student-1"; score = 8.8; note = "smoke" })
}
Invoke-Json POST "/grades/bulk" $goodGrade $teacher1.accessToken | Out-Null
Write-Host "[OK] assigned teacher can upsert grade"

$parentNotificationsBeforeAttendance = (As-Array (Invoke-Json GET "/notifications" $null $parent.accessToken)).Count
$attendance = @{
    slotId = "tt-1"
    date = (Get-Date -Format "yyyy-MM-dd")
    marks = @(@{ studentId = "u-student-1"; status = "ABSENT_UNEXCUSED"; note = "smoke" })
}
Invoke-Json POST "/attendance/bulk" $attendance $teacher1.accessToken | Out-Null
Write-Host "[OK] attendance bulk mark"
Wait-NotificationCount $parent.accessToken ($parentNotificationsBeforeAttendance + 1) | Out-Null
Write-Host "[OK] async notification delivered attendance event"

$invoices = Invoke-Json GET "/invoices" $null $parent.accessToken
$pending = @($invoices | Where-Object { $_.status -ne "PAID" }) | Select-Object -First 1
if ($pending) {
    Invoke-Json POST "/payments" @{ invoiceId = $pending.id; method = "VNPAY" } $parent.accessToken | Out-Null
    Write-Host "[OK] sandbox payment"
} else {
    Write-Host "[SKIP] sandbox payment: no unpaid seeded invoice"
}

$notis = As-Array (Invoke-Json GET "/notifications" $null $parent.accessToken)
if ($notis.Count -lt 1) { throw "Expected parent notifications after smoke events" }
Write-Host "[OK] notification inbox has events"

Write-Host "SSE smoke completed successfully."
