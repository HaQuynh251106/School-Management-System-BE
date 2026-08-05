param(
    [string]$BaseUrl = "http://127.0.0.1:4000"
)

$ErrorActionPreference = "Stop"

function Invoke-Sse {
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
        Headers = $headers
        UseBasicParsing = $true
        ContentType = "application/json; charset=utf-8"
    }
    if ($null -ne $Body) {
        $params.Body = [Text.Encoding]::UTF8.GetBytes(
            ($Body | ConvertTo-Json -Depth 10 -Compress))
    }
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

function Login([string]$Username, [string]$Password) {
    return Invoke-Sse POST "/auth/login" @{
        username = $Username
        password = $Password
        deviceToken = "g2-smoke-$Username"
        platform = "TEST"
        deviceName = "G2 smoke"
    }
}

function As-Array([object]$Value) {
    if ($null -eq $Value) { return @() }
    $properties = @($Value.PSObject.Properties.Name)
    if (($properties -contains "value") -and ($properties -contains "Count")) {
        return @($Value.value)
    }
    return @($Value)
}

Write-Host "Academic G2 smoke against $BaseUrl"
$admin = Login "admin" "admin@123"
$teacher = Login "gv.toan" "teacher@123"
$student = Login "hs.minh" "student@123"
$parent = Login "ph.nguyen" "parent@123"

$gradeLevels = As-Array (Invoke-Sse GET "/grade-levels" $null $admin.accessToken)
if (($gradeLevels.code -join ",") -ne "K10,K11,K12") {
    throw "Expected exactly K10, K11 and K12"
}
Invoke-Sse GET "/grade-levels" $null $student.accessToken | Out-Null
Invoke-Sse GET "/grade-levels" $null $parent.accessToken | Out-Null
Write-Host "[OK] three fixed grade levels and read access"

$years = As-Array (Invoke-Sse GET "/academic-years" $null $admin.accessToken)
$year = $years | Where-Object { $_.status -eq "ACTIVE" } | Select-Object -First 1
if (-not $year) { $year = $years | Select-Object -First 1 }
if (-not $year) { throw "No academic year is available" }
$semesters = As-Array (Invoke-Sse GET "/semesters?academicYearId=$($year.id)" $null $admin.accessToken)
if (@($semesters).Count -ne 2) {
    throw "Expected two semesters in year $($year.id), got $(@($semesters).Count)"
}

$classes = As-Array (Invoke-Sse GET "/classes?academicYearId=$($year.id)" $null $admin.accessToken)
if ($classes.Count -lt 1) { throw "No class is available" }
$firstClass = $classes | Select-Object -First 1
Invoke-Sse GET "/academic/enrollments?academicYearId=$($year.id)&classId=$($firstClass.id)" $null $admin.accessToken | Out-Null
Invoke-Sse GET "/academic/enrollments/unassigned?academicYearId=$($year.id)" $null $student.accessToken @(403) | Out-Null
Write-Host "[OK] enrollment list and management permission"

$hk1 = $semesters | Where-Object { $_.sequence -eq 1 } | Select-Object -First 1
$hk2 = $semesters | Where-Object { $_.sequence -eq 2 } | Select-Object -First 1
$hk1Slots = As-Array (Invoke-Sse GET "/timetableSlots?classId=$($firstClass.id)&semesterId=$($hk1.id)" $null $admin.accessToken)
$hk2Slots = As-Array (Invoke-Sse GET "/timetableSlots?classId=$($firstClass.id)&semesterId=$($hk2.id)" $null $admin.accessToken)
if ($hk1Slots.Count -gt 0 -and $hk2Slots.Count -gt 0) {
    $hk1Signature = @($hk1Slots | ForEach-Object {
        "$($_.dayOfWeek)|$($_.periodNo)|$($_.subjectId)|$($_.teacherId)"
    } | Sort-Object) -join ";"
    $hk2Signature = @($hk2Slots | ForEach-Object {
        "$($_.dayOfWeek)|$($_.periodNo)|$($_.subjectId)|$($_.teacherId)"
    } | Sort-Object) -join ";"
    if ($hk1Signature -eq $hk2Signature) {
        throw "HK1 and HK2 timetables must not be identical"
    }
    if (@($hk1Slots + $hk2Slots | Where-Object {
        $_.periodNo -lt 1 -or $_.periodNo -gt 10
    }).Count -gt 0) {
        throw "Timetable contains a period outside 1..10"
    }
    Write-Host "[OK] HK1 and HK2 have distinct schedules within periods 1..10"
}

Invoke-Sse POST "/subjects" @{
    code = "G2_FORBIDDEN"
    name = "Forbidden"
    coefficient = 1
} $teacher.accessToken @(403) | Out-Null
Write-Host "[OK] teacher cannot mutate structure without delegated permission"

$subjects = @(As-Array (Invoke-Sse GET "/subjects" $null $admin.accessToken) | Where-Object { $_.active })
$rooms = @(As-Array (Invoke-Sse GET "/rooms" $null $admin.accessToken) | Where-Object { $_.active })
$teachers = As-Array (Invoke-Sse GET "/academic/teachers" $null $teacher.accessToken)
Invoke-Sse GET "/academic/teachers" $null $student.accessToken @(403) | Out-Null
Invoke-Sse GET "/academic/teachers" $null $parent.accessToken @(403) | Out-Null
if (-not $subjects.Count -or -not $rooms.Count -or -not $teachers.Count) {
    throw "A subject, room and active teacher are required (subjects=$($subjects.Count), rooms=$($rooms.Count), teachers=$($teachers.Count))"
}
Write-Host "[OK] teacher directory is private to admin and teacher"

$existingPlans = As-Array (Invoke-Sse GET "/academic/training-plans?academicYearId=$($year.id)" $null $admin.accessToken)
$grade = @("K10", "K11", "K12") |
    Where-Object {
        $candidate = $_
        -not ($existingPlans | Where-Object { $_.gradeLevel -eq $candidate })
    } |
    Select-Object -First 1
if (-not $grade) {
    Write-Host "[SKIP] plan mutation: all three grades already have user plans"
    exit 0
}
$plan = $null
$rows = @()
$exam = $null
try {
    $plan = Invoke-Sse POST "/academic/training-plans" @{
        academicYearId = $year.id
        gradeLevel = $grade
        name = "G2 smoke $grade"
        maxProgressGapDays = 2
    } $admin.accessToken

    Invoke-Sse POST "/academic/training-plans" @{
        academicYearId = $year.id
        gradeLevel = $grade
        name = "Duplicate"
        maxProgressGapDays = 2
    } $admin.accessToken @(409) | Out-Null

    foreach ($semester in $semesters) {
        $row = Invoke-Sse POST "/academic/training-plans/$($plan.id)/subjects" @{
            semesterId = $semester.id
            subjectId = $subjects[0].id
            weeklyPeriods = 2
            totalPeriods = 35
            startDate = $semester.startDate
            endDate = $semester.endDate
            examRequired = $false
            displayOrder = 1
        } $admin.accessToken
        $rows += $row

        Invoke-Sse POST "/academic/training-plans/$($plan.id)/subjects/$($row.id)/stages" @{
            code = "GD1"
            name = "Toàn bộ học kỳ"
            sequence = 1
            startDate = $semester.startDate
            endDate = $semester.endDate
            targetPeriods = 35
        } $admin.accessToken | Out-Null
        $chapter = Invoke-Sse POST "/academic/training-plans/$($plan.id)/subjects/$($row.id)/curriculum" @{
            itemType = "CHAPTER"
            code = "CH1"
            title = "Chương trình học kỳ"
            sequence = 1
            plannedPeriods = 0
        } $admin.accessToken
        $topic = Invoke-Sse POST "/academic/training-plans/$($plan.id)/subjects/$($row.id)/curriculum" @{
            parentId = $chapter.id
            itemType = "TOPIC"
            code = "CD1"
            title = "Nội dung chính"
            sequence = 1
            plannedPeriods = 0
        } $admin.accessToken
        Invoke-Sse POST "/academic/training-plans/$($plan.id)/subjects/$($row.id)/curriculum" @{
            parentId = $topic.id
            itemType = "LESSON"
            code = "BH1"
            title = "Chương trình môn học"
            sequence = 1
            plannedPeriods = 35
        } $admin.accessToken | Out-Null
        Invoke-Sse POST "/academic/training-plans/$($plan.id)/subjects/$($row.id)/special-weeks" @{
            weekType = "EXAM"
            weekNumber = 1
            name = "Tuần kiểm tra"
        } $admin.accessToken | Out-Null
        Invoke-Sse POST "/academic/training-plans/$($plan.id)/subjects/$($row.id)/special-weeks" @{
            weekType = "BUFFER"
            weekNumber = 2
            name = "Tuần dự phòng"
        } $admin.accessToken | Out-Null
    }

    $examDate = ([datetime]$semesters[0].endDate).AddDays(-7).ToString("yyyy-MM-dd")
    $exam = Invoke-Sse POST "/academic/training-plans/$($plan.id)/exams" @{
        semesterId = $semesters[0].id
        subjectId = $subjects[0].id
        name = "G2 smoke exam"
        examDate = $examDate
        startTime = "07:30"
        durationMinutes = 90
        roomId = $rooms[0].id
        proctorTeacherId = $teachers[0].id
        status = "PLANNED"
    } $admin.accessToken

    Invoke-Sse POST "/academic/training-plans/$($plan.id)/exams" @{
        semesterId = $semesters[0].id
        subjectId = $subjects[0].id
        name = "Overlapping exam"
        examDate = $examDate
        startTime = "08:00"
        durationMinutes = 60
        roomId = $rooms[0].id
        status = "PLANNED"
    } $admin.accessToken @(409) | Out-Null

    $readiness = Invoke-Sse GET "/academic/training-plans/$($plan.id)/readiness" $null $teacher.accessToken
    if (-not $readiness.ready) { throw "Plan should be ready: $($readiness.issues -join '; ')" }
    Invoke-Sse POST "/academic/training-plans/$($plan.id)/publish" $null $teacher.accessToken | Out-Null
    Invoke-Sse POST "/academic/training-plans" @{
        academicYearId = $year.id
        gradeLevel = "K10"
        name = "Student forbidden"
        maxProgressGapDays = 2
    } $student.accessToken @(403) | Out-Null
    Write-Host "[OK] plan, subjects, readiness, publish and RBAC"
    Write-Host "[OK] exam room conflict returns 409"
} finally {
    if ($plan) {
        try { Invoke-Sse POST "/academic/training-plans/$($plan.id)/reopen" $null $admin.accessToken | Out-Null } catch {}
        if ($exam) {
            try { Invoke-Sse DELETE "/academic/training-plans/$($plan.id)/exams/$($exam.id)" $null $admin.accessToken | Out-Null } catch {}
        }
        foreach ($row in $rows) {
            try { Invoke-Sse DELETE "/academic/training-plans/$($plan.id)/subjects/$($row.id)" $null $admin.accessToken | Out-Null } catch {}
        }
        try { Invoke-Sse DELETE "/academic/training-plans/$($plan.id)" $null $admin.accessToken | Out-Null } catch {}
    }
}

Write-Host "Academic G2 smoke completed successfully."
