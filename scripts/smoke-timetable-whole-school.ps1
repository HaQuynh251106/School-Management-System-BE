param(
    [string]$BaseUrl = "http://127.0.0.1:4000",
    [string]$AdminUsername = "admin",
    [string]$AdminPassword = "admin@123",
    [string]$AcademicYearId = "",
    [ValidateSet("ALL", "HK1", "HK2")]
    [string]$SemesterCode = "ALL",
    [int]$SolveSeconds = 60,
    [switch]$VerifyExistingOnly,
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
        $detail = $_.ErrorDetails.Message
        if ([string]::IsNullOrWhiteSpace($detail) -and $_.Exception.Response) {
            try {
                $reader = [IO.StreamReader]::new($_.Exception.Response.GetResponseStream())
                $detail = $reader.ReadToEnd()
            } catch { $detail = $_.Exception.Message }
        }
        throw "${Method} ${Path} failed with HTTP ${status}: $detail"
    }
}

function Assert-Equal($Actual, $Expected, [string]$Message) {
    if ($Actual -ne $Expected) {
        throw "$Message (expected=$Expected, actual=$Actual)"
    }
}

function Get-LongestHeavyRun($Items) {
    $heavyIds = @("sj-math", "sj-phys", "sj-chem")
    $periods = @($Items | Where-Object { $heavyIds -contains $_.subjectId } |
        Sort-Object periodNo | Select-Object -ExpandProperty periodNo)
    $longest = 0
    $current = 0
    $previous = $null
    foreach ($period in $periods) {
        if ($null -ne $previous -and $period -eq ($previous + 1)) {
            $current++
        } else {
            $current = 1
        }
        if ($current -gt $longest) { $longest = $current }
        $previous = $period
    }
    return $longest
}

function Verify-WholeSchoolSchedule {
    param($Schedule, $Token, $Classes, $Rooms, $Assignments)

    $validation = Invoke-Api -Method GET `
        -Path "/timetable/schedules/$($Schedule.id)/validation" `
        -Body $null -Token $Token
    if (-not $validation.valid -or $validation.errorCount -ne 0) {
        throw "Schedule $($Schedule.id) has $($validation.errorCount) mandatory errors"
    }

    $slotsResponse = Invoke-Api -Method GET `
        -Path "/timetable/schedules/$($Schedule.id)/slots" `
        -Body $null -Token $Token
    $slots = @($slotsResponse | ForEach-Object { $_ })
    if ([string]::IsNullOrWhiteSpace($Schedule.sourcePlanSnapshot)) {
        throw "Whole-school schedule has no G3 source plan snapshot"
    }
    $sourcePlans = $Schedule.sourcePlanSnapshot | ConvertFrom-Json
    Assert-Equal $sourcePlans.Count 3 "A whole-school schedule must snapshot three source plans"
    $expectedTeaching = 0
    foreach ($assignment in $Assignments) {
        $class = $Classes | Where-Object id -eq $assignment.classId | Select-Object -First 1
        $source = $sourcePlans | Where-Object gradeLevel -eq $class.gradeLevel | Select-Object -First 1
        $subject = $source.subjects | Where-Object subjectId -eq $assignment.subjectId | Select-Object -First 1
        if ($null -eq $subject) { throw "$($class.code) has an assignment outside source plan: $($assignment.subjectId)" }
        $expectedTeaching += [int]$subject.weeklyPeriods
    }
    $expectedTotal = $expectedTeaching + ($Classes.Count * 2)
    Assert-Equal $slots.Count $expectedTotal "Whole-school schedule period count is incorrect"
    Assert-Equal $validation.requiredPeriods $expectedTotal "Required period total is incorrect"
    Assert-Equal $validation.scheduledPeriods $expectedTotal "Scheduled period total is incorrect"

    $classById = @{}
    foreach ($class in $Classes) { $classById[$class.id] = $class }
    $roomById = @{}
    foreach ($room in $Rooms) { $roomById[$room.id] = $room }

    $gradeCounts = @{ K10 = 0; K11 = 0; K12 = 0 }
    foreach ($slot in $slots) {
        $class = $classById[$slot.classId]
        if ($null -eq $class) { throw "Unknown class $($slot.classId) in schedule" }
        $gradeCounts[$class.gradeLevel]++
    }
    foreach ($grade in @("K10", "K11", "K12")) {
        $gradeClassIds = @($Classes | Where-Object gradeLevel -eq $grade |
            Select-Object -ExpandProperty id)
        $expectedGrade = 0
        foreach ($assignment in @($Assignments | Where-Object { $gradeClassIds -contains $_.classId })) {
            $subject = ($sourcePlans | Where-Object gradeLevel -eq $grade).subjects |
                Where-Object subjectId -eq $assignment.subjectId | Select-Object -First 1
            $expectedGrade += [int]$subject.weeklyPeriods
        }
        $expectedGrade += ($gradeClassIds.Count * 2)
        Assert-Equal $gradeCounts[$grade] $expectedGrade `
            "$grade scheduled period count is incorrect"
    }

    $classCoverage = @($slots | Group-Object classId)
    Assert-Equal $classCoverage.Count 30 "All 30 classes must be scheduled"
    $badClassCoverage = @($classCoverage | Where-Object {
        $classId = $_.Name
        $class = $classById[$classId]
        $source = $sourcePlans | Where-Object gradeLevel -eq $class.gradeLevel | Select-Object -First 1
        $expected = 2
        foreach ($assignment in @($Assignments | Where-Object classId -eq $classId)) {
            $subject = $source.subjects | Where-Object subjectId -eq $assignment.subjectId | Select-Object -First 1
            $expected += [int]$subject.weeklyPeriods
        }
        $_.Count -ne $expected
    })
    Assert-Equal $badClassCoverage.Count 0 `
        "One or more classes do not contain the required number of periods"

    $classConflicts = @($slots | Group-Object classId, dayOfWeek, periodNo |
        Where-Object Count -gt 1)
    $teacherConflicts = @($slots | Group-Object teacherId, dayOfWeek, periodNo |
        Where-Object Count -gt 1)
    $roomConflicts = @($slots | Group-Object roomId, dayOfWeek, periodNo |
        Where-Object Count -gt 1)
    Assert-Equal $classConflicts.Count 0 "Class conflicts found"
    Assert-Equal $teacherConflicts.Count 0 "Teacher conflicts found"
    Assert-Equal $roomConflicts.Count 0 "Room conflicts found"

    $teacherOverload = @($slots | Group-Object teacherId, dayOfWeek |
        Where-Object Count -gt 5)
    Assert-Equal $teacherOverload.Count 0 "A teacher exceeds five periods per day"
    $teachersWithoutRest = @($slots | Group-Object teacherId | Where-Object {
        @($_.Group.dayOfWeek | Sort-Object -Unique).Count -gt 5
    })
    Assert-Equal $teachersWithoutRest.Count 0 "A teacher has no weekday off"

    $roomRuleErrors = @($slots | Where-Object {
        $class = $classById[$_.classId]
        $room = $roomById[$_.roomId]
        $null -eq $room -or
        ($_.requiredRoomType -eq "GENERAL" -and $_.roomId -ne $class.homeRoomId) -or
        ($_.requiredRoomType -ne "GENERAL" -and $room.roomType -ne $_.requiredRoomType)
    })
    Assert-Equal $roomRuleErrors.Count 0 "Home-room or specialized-room rule was violated"

    $maxHeavyRun = 0
    foreach ($group in @($slots | Group-Object classId, dayOfWeek)) {
        $run = Get-LongestHeavyRun $group.Group
        if ($run -gt $maxHeavyRun) { $maxHeavyRun = $run }
    }
    if ($maxHeavyRun -gt 3) {
        throw "Heavy subjects are consecutive for $maxHeavyRun periods"
    }

    $maxTeacherPeriods = ($slots | Group-Object teacherId, dayOfWeek |
        Measure-Object Count -Maximum).Maximum
    $activityCount = @($slots | Where-Object source -eq "FIXED_ACTIVITY").Count
    $blockCount = @($slots | Where-Object source -eq "AUTO_BLOCK").Count
    Assert-Equal $activityCount 60 "Thirty flag and thirty homeroom periods are required"
    if (($blockCount % 3) -ne 0) {
        throw "Three-period block count is not divisible by three (actual=$blockCount)"
    }
    $badBlocks = @($slots | Where-Object source -eq "AUTO_BLOCK" |
        Group-Object classId, subjectId, dayOfWeek | Where-Object {
            $_.Count -ne 3 -or
            ((($_.Group.periodNo | Measure-Object -Maximum).Maximum -
              ($_.Group.periodNo | Measure-Object -Minimum).Minimum + 1) -ne 3)
        })
    Assert-Equal $badBlocks.Count 0 `
        "Every G3-driven block subject must contain three consecutive periods"

    $badRegularDays = @($slots | Where-Object source -eq "AUTO" |
        Group-Object classId, dayOfWeek | Where-Object {
            $_.Count -lt 2 -or $_.Count -gt 4 -or
            ((($_.Group.periodNo | Measure-Object -Maximum).Maximum -
              ($_.Group.periodNo | Measure-Object -Minimum).Minimum + 1) -ne $_.Count)
        })
    Assert-Equal $badRegularDays.Count 0 `
        "Main sessions must contain two to four consecutive periods"

    Write-Host "[OK] $($Schedule.name): $expectedTotal/$expectedTotal periods across 30 classes"
    Write-Host "[OK] no class, teacher or room conflicts; max teacher load/day=$maxTeacherPeriods"
    Write-Host "[OK] room types, home rooms, teacher rest days and heavy-subject runs"
    Write-Host "[OK] 60 fixed activities, $([int]($blockCount / 3)) G3-driven blocks and compact main sessions"
    Write-Host "[INFO] score=$($Schedule.solverScore); soft warnings=$($validation.warningCount)"
}

Write-Host "SSE whole-school timetable smoke against $BaseUrl"
$login = Invoke-Api -Method POST -Path "/auth/login" -Body @{
    username = $AdminUsername
    password = $AdminPassword
} -Token ""
$token = $login.accessToken

$yearsResponse = Invoke-Api -Method GET -Path "/academic-years" -Body $null -Token $token
$years = @($yearsResponse | ForEach-Object { $_ })
$activeYears = @($years | Where-Object { $_.status -eq "ACTIVE" })
Assert-Equal $activeYears.Count 1 "Exactly one academic year must be active"
$year = if ([string]::IsNullOrWhiteSpace($AcademicYearId)) {
    $activeYears[0]
} else {
    $years | Where-Object { $_.id -eq $AcademicYearId } | Select-Object -First 1
}
if ($null -eq $year) { throw "Academic year was not found" }

$semestersResponse = Invoke-Api -Method GET `
    -Path "/semesters?academicYearId=$($year.id)" -Body $null -Token $token
$semesters = @($semestersResponse | ForEach-Object { $_ } | Sort-Object { $_.sequence })
Assert-Equal $semesters.Count 2 "The academic year must contain exactly two semesters"
if ($SemesterCode -ne "ALL") {
    $semesters = @($semesters | Where-Object { $_.code -eq $SemesterCode })
    Assert-Equal $semesters.Count 1 "Requested semester was not found"
}

$classesResponse = Invoke-Api -Method GET `
    -Path "/classes?academicYearId=$($year.id)" -Body $null -Token $token
$classes = @($classesResponse | ForEach-Object { $_ })
Assert-Equal $classes.Count 30 "The academic year must contain 30 classes"
foreach ($grade in @("K10", "K11", "K12")) {
    Assert-Equal @($classes | Where-Object { $_.gradeLevel -eq $grade }).Count 10 `
        "$grade must contain ten classes"
}
$classesWithoutHomeRoom = @($classes | Where-Object {
    [string]::IsNullOrWhiteSpace($_.homeRoomId)
})
Assert-Equal $classesWithoutHomeRoom.Count 0 "Every class must have a home room"

$roomsResponse = Invoke-Api -Method GET -Path "/rooms" -Body $null -Token $token
$rooms = @($roomsResponse | ForEach-Object { $_ } | Where-Object { $_.active })
foreach ($type in @("GENERAL", "LAB", "COMPUTER", "GYM")) {
    if (@($rooms | Where-Object { $_.roomType -eq $type }).Count -eq 0) {
        throw "No active $type room is available"
    }
}
Write-Host "[OK] one active year, two semesters, 30 classes and required room types"

$createdIds = [Collections.Generic.List[string]]::new()
try {
    foreach ($semester in $semesters) {
        $assignmentsResponse = Invoke-Api -Method GET `
            -Path "/teacher-class-subjects?semesterId=$($semester.id)" `
            -Body $null -Token $token
        $assignments = @($assignmentsResponse | ForEach-Object { $_ } |
            Where-Object { $_.status -eq "ACTIVE" })
        Assert-Equal $assignments.Count 360 "$($semester.code) must have 360 active assignments"
        foreach ($grade in @("K10", "K11", "K12")) {
            $ids = @($classes | Where-Object { $_.gradeLevel -eq $grade } |
                Select-Object -ExpandProperty id)
            $gradeAssignments = @($assignments | Where-Object { $ids -contains $_.classId })
            Assert-Equal $gradeAssignments.Count 120 "$($semester.code) $grade must have 120 assignments"
        }
        Write-Host "[OK] $($semester.code) assignments identify teachers for 360 class-subject pairs"

        $readiness = Invoke-Api -Method GET `
            -Path "/timetable/schedules/generation-readiness?academicYearId=$($year.id)&semesterId=$($semester.id)" `
            -Body $null -Token $token
        if (-not $readiness.ready) {
            $details = @($readiness.issues | ForEach-Object message) -join "; "
            throw "$($semester.code) is not ready for whole-school generation: $details"
        }
        Assert-Equal $readiness.classCount 30 `
            "$($semester.code) readiness must cover all 30 classes"
        Write-Host "[OK] $($semester.code) readiness: $($readiness.requiredPeriods) periods from $($readiness.sourcePlanSummary)"

        if ($VerifyExistingOnly) {
            $schedulesResponse = Invoke-Api -Method GET `
                -Path "/timetable/schedules?semesterId=$($semester.id)" `
                -Body $null -Token $token
            $schedule = @($schedulesResponse | ForEach-Object { $_ } | Where-Object {
                    $null -eq $_.scopeGradeLevel -or
                    [string]::IsNullOrWhiteSpace($_.scopeGradeLevel)
                } | Sort-Object createdAt -Descending)[0]
            if ($null -eq $schedule) {
                throw "No whole-school schedule exists for $($semester.code)"
            }
        } else {
            $payload = @{
                academicYearId = $year.id
                semesterId = $semester.id
                scopeGradeLevel = $null
                name = "Smoke whole school $($semester.code)"
                teachingDays = @("MON", "TUE", "WED", "THU", "FRI", "SAT")
                firstPeriod = 1
                lastPeriod = 10
                maxPeriodsPerDay = 8
                maxProgressGapDays = 2
                maxProgressGapPeriods = 2
                maxCurriculumGapLessons = 1
                solveSeconds = [Math]::Max(60, $SolveSeconds)
            }
            Write-Host "[INFO] generating all three grades for $($semester.code); this takes about three minutes"
            $result = Invoke-Api -Method POST -Path "/timetable/schedules/generate" `
                -Body $payload -Token $token
            $schedule = $result.schedule
            $createdIds.Add($schedule.id)
        }
        Verify-WholeSchoolSchedule $schedule $token $classes $rooms $assignments
    }
} finally {
    if (-not $KeepDraft) {
        foreach ($id in $createdIds) {
            Invoke-Api -Method DELETE -Path "/timetable/schedules/$id" `
                -Body $null -Token $token | Out-Null
        }
    }
}

if ($VerifyExistingOnly -or $KeepDraft) {
    Write-Host "SSE whole-school timetable smoke completed; schedules were kept."
} else {
    Write-Host "SSE whole-school timetable smoke completed; smoke drafts were removed."
}
