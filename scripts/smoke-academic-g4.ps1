param(
    [string]$BaseUrl = "http://127.0.0.1:4000",
    [string]$AdminUsername = "admin",
    [string]$AdminPassword = "admin@123",
    [string]$TeacherUsername = "gv.toan",
    [string]$TeacherPassword = "teacher@123",
    [string]$GradeLevel = "K10",
    [int]$SolveSeconds = 30,
    [switch]$KeepDraft
)

$ErrorActionPreference = "Stop"

function Invoke-Api {
    param(
        [string]$Method,
        [string]$Path,
        [object]$Body,
        [string]$Token,
        [int[]]$Expected = @(200)
    )
    $params = @{
        UseBasicParsing = $true
        Method = $Method
        Uri = "$BaseUrl$Path"
        ContentType = "application/json; charset=utf-8"
    }
    if ($Token) { $params.Headers = @{ Authorization = "Bearer $Token" } }
    if ($null -ne $Body) {
        $json = $Body | ConvertTo-Json -Depth 12 -Compress
        $params.Body = [Text.Encoding]::UTF8.GetBytes($json)
    }
    try {
        $response = Invoke-WebRequest @params
        if ($Expected -notcontains [int]$response.StatusCode) {
            throw "Expected HTTP $($Expected -join '/') but received $($response.StatusCode)"
        }
        if ([string]::IsNullOrWhiteSpace($response.Content)) { return $null }
        return $response.Content | ConvertFrom-Json
    } catch {
        $status = if ($_.Exception.Response) { [int]$_.Exception.Response.StatusCode } else { 0 }
        if ($Expected -contains $status) { return $null }
        throw "${Method} ${Path} failed with HTTP ${status}: $($_.ErrorDetails.Message)"
    }
}

function Login([string]$Username, [string]$Password) {
    return Invoke-Api -Method POST -Path "/auth/login" -Body @{
        username = $Username; password = $Password
    } -Token "" -Expected @(200)
}

function New-SchedulePayload($YearId, $SemesterId, $Name, $LastPeriod, $MaxPerDay, $Seconds) {
    return @{
        academicYearId = $YearId
        semesterId = $SemesterId
        scopeGradeLevel = $GradeLevel
        name = $Name
        teachingDays = @("MON", "TUE", "WED", "THU", "FRI")
        firstPeriod = 1
        lastPeriod = $LastPeriod
        maxPeriodsPerDay = $MaxPerDay
        maxProgressGapDays = 2
        maxProgressGapPeriods = 2
        maxCurriculumGapLessons = 1
        solveSeconds = $Seconds
    }
}

Write-Host "SSE academic G4 smoke against $BaseUrl"
$admin = Login $AdminUsername $AdminPassword
$teacher = Login $TeacherUsername $TeacherPassword

$yearsResponse = Invoke-Api -Method GET -Path "/academic-years" -Body $null -Token $admin.accessToken
$years = @($yearsResponse | ForEach-Object { $_ })
$activeYear = $years | Where-Object { $_.status -eq "ACTIVE" } | Select-Object -First 1
if (-not $activeYear) { throw "No ACTIVE academic year" }
$semestersResponse = Invoke-Api -Method GET -Path "/semesters" -Body $null -Token $admin.accessToken
$semesters = @($semestersResponse | ForEach-Object { $_ })
$semesterYearIds = @($semesters | ForEach-Object { $_.academicYearId }) -join ","
Write-Host "[INFO] activeYear=$($activeYear.id); semesters=$($semesters.Count); semesterYears=$semesterYearIds"
$semester = $semesters | Where-Object { $_.academicYearId -eq $activeYear.id } |
    Sort-Object sequence | Select-Object -First 1
if (-not $semester) { throw "The active year has no semester" }

$roomsResponse = Invoke-Api -Method GET -Path "/rooms" -Body $null -Token $admin.accessToken
$rooms = @($roomsResponse | ForEach-Object { $_ })
$labCount = @($rooms | Where-Object { $_.active -and $_.roomType -eq "LAB" }).Count
$gymCount = @($rooms | Where-Object { $_.active -and $_.roomType -eq "GYM" }).Count
$computerCount = @($rooms | Where-Object { $_.active -and $_.roomType -eq "COMPUTER" }).Count
if ($labCount -lt 3 -or $gymCount -lt 2 -or $computerCount -lt 2) {
    throw "Reference data needs at least LAB=3, GYM=2 and COMPUTER=2; current LAB=$labCount GYM=$gymCount COMPUTER=$computerCount"
}
Write-Host "[OK] active year, semester and specialized rooms"

Invoke-Api -Method GET -Path "/timetable/schedules?semesterId=$($semester.id)" -Body $null -Token $teacher.accessToken -Expected @(200) | Out-Null
$forbidden = New-SchedulePayload $($activeYear.id) $($semester.id) "Forbidden smoke" 6 6 1
Invoke-Api -Method POST -Path "/timetable/schedules/generate" -Body $forbidden -Token $teacher.accessToken -Expected @(403) | Out-Null
Write-Host "[OK] teacher can read but cannot generate schedules"

$validId = $null
try {
    $invalidPayload = New-SchedulePayload $($activeYear.id) $($semester.id) "G4 smoke invalid" 5 5 5
    Invoke-Api -Method POST -Path "/timetable/schedules/generate" -Body $invalidPayload -Token $admin.accessToken -Expected @(400, 409) | Out-Null
    Write-Host "[OK] infeasible automatic schedule is rejected instead of saving conflicts"

    $validPayload = New-SchedulePayload $($activeYear.id) $($semester.id) "G4 smoke feasible" 10 10 $SolveSeconds
    $valid = Invoke-Api -Method POST -Path "/timetable/schedules/generate" -Body $validPayload -Token $admin.accessToken -Expected @(200)
    $validId = $valid.schedule.id
    if (-not $valid.validation.valid -or $valid.validation.errorCount -ne 0) {
        throw "Expected a feasible draft, got $($valid.validation.errorCount) mandatory conflicts and score $($valid.schedule.solverScore)"
    }
    if ($valid.validation.requiredPeriods -ne $valid.validation.scheduledPeriods) {
        throw "Weekly coverage is incomplete"
    }
    if ([string]::IsNullOrWhiteSpace($valid.schedule.sourcePlanSnapshot) -or
        $valid.schedule.sourcePlanSummary -notmatch $GradeLevel) {
        throw "Schedule did not persist the G3 source plan version"
    }
    Write-Host "[OK] G3 source snapshot: $($valid.schedule.sourcePlanSummary)"
    Write-Host "[OK] feasible automatic draft: $($valid.validation.scheduledPeriods) periods, score $($valid.schedule.solverScore)"

    $slotsResponse = Invoke-Api -Method GET -Path "/timetable/schedules/$validId/slots" -Body $null -Token $admin.accessToken
    $slots = @($slotsResponse | ForEach-Object { $_ })
    $roomConflicts = $slots | Group-Object { "$($_.roomId)|$($_.dayOfWeek)|$($_.periodNo)" } | Where-Object Count -gt 1
    $teacherConflicts = $slots | Group-Object { "$($_.teacherId)|$($_.dayOfWeek)|$($_.periodNo)" } | Where-Object Count -gt 1
    $teacherOverload = $slots | Group-Object { "$($_.teacherId)|$($_.dayOfWeek)" } | Where-Object Count -gt 5
    $teacherNoRestDay = $slots | Group-Object teacherId | Where-Object {
        @($_.Group | Select-Object -ExpandProperty dayOfWeek -Unique).Count -gt 5
    }
    if ($roomConflicts -or $teacherConflicts -or $teacherOverload -or $teacherNoRestDay) {
        throw "Generated schedule violates room or teacher constraints"
    }
    Write-Host "[OK] no room/teacher conflicts, max 5 periods/day and one rest day in the six-day week"
    $move = $null
    foreach ($slot in $slots) {
        if ($slot.subjectId -in @("sj-math", "sj-physics", "sj-chemistry")) { continue }
        foreach ($day in @("MON", "TUE", "WED", "THU", "FRI")) {
            $teacherDayCount = @($slots | Where-Object {
                $_.teacherId -eq $slot.teacherId -and $_.dayOfWeek -eq $day -and $_.id -ne $slot.id
            }).Count
            if ($teacherDayCount -lt 1 -or $teacherDayCount -ge 5) { continue }
            foreach ($period in @(1, 2, 3, 4, 5, 6, 7, 8, 9, 10)) {
                $blocked = $slots | Where-Object {
                    $_.dayOfWeek -eq $day -and $_.periodNo -eq $period -and
                    ($_.classId -eq $slot.classId -or $_.teacherId -eq $slot.teacherId -or $_.roomId -eq $slot.roomId)
                }
                if (-not $blocked) {
                    $moved = Invoke-Api -Method PUT -Path "/timetable/schedules/$validId/slots/$($slot.id)" -Body @{
                        dayOfWeek = $day; periodNo = $period; roomId = $slot.roomId
                    } -Token $admin.accessToken -Expected @(200, 409)
                    if ($moved) {
                        $move = @{ slot = $slot; day = $day; period = $period }
                        break
                    }
                }
            }
            if ($move) { break }
        }
        if ($move) { break }
    }
    if (-not $move) { throw "No safe empty cell found for drag/drop smoke" }
    $originalDay = $move.slot.dayOfWeek
    $originalPeriod = $move.slot.periodNo
    $movedValidation = Invoke-Api -Method GET -Path "/timetable/schedules/$validId/validation" -Body $null -Token $admin.accessToken -Expected @(200)
    if (-not $movedValidation.valid) { throw "A safe manual move created conflicts" }
    Invoke-Api -Method PUT -Path "/timetable/schedules/$validId/slots/$($move.slot.id)" -Body @{
        dayOfWeek = $originalDay; periodNo = $originalPeriod; roomId = $move.slot.roomId
    } -Token $admin.accessToken -Expected @(200) | Out-Null
    Write-Host "[OK] manual move and automatic revalidation"

    $comparison = Invoke-Api -Method GET -Path "/academic/progress/comparison?academicYearId=$($activeYear.id)&semesterId=$($semester.id)&gradeLevel=$GradeLevel&subjectId=sj-math" -Body $null -Token $admin.accessToken -Expected @(200)
    if (@($comparison.classes).Count -eq 0) { throw "Progress comparison returned no classes" }
    if (-not $comparison.sourcePlanVersion) { throw "Progress is not bound to the published timetable source plan" }
    Write-Host "[OK] progress thresholds: $($comparison.allowedDayGap) days, $($comparison.allowedPeriodGap) periods, $($comparison.allowedLessonGap) lessons"
} finally {
    if (-not $KeepDraft) {
        if ($validId) { Invoke-Api -Method DELETE -Path "/timetable/schedules/$validId" -Body $null -Token $admin.accessToken -Expected @(200) | Out-Null }
    }
}

if ($KeepDraft) {
    Write-Host "SSE academic G4 smoke completed; drafts kept for FE review."
} else {
    Write-Host "SSE academic G4 smoke completed; test drafts removed."
}
