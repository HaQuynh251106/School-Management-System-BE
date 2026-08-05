param(
    [string]$BaseUrl = "http://127.0.0.1:4000",
    [string]$AdminUsername = "admin",
    [string]$AdminPassword = "admin@123",
    [ValidateSet("MIDTERM", "FINAL")]
    [string]$ExamType = "FINAL",
    [ValidateSet("HK1", "HK2")]
    [string]$SemesterCode = "HK2"
)

$ErrorActionPreference = "Stop"

function Invoke-Json([string]$Method, [string]$Path, [object]$Body = $null, [string]$Token = $null) {
    $headers = @{}
    if ($Token) { $headers.Authorization = "Bearer $Token" }
    $params = @{ Method = $Method; Uri = "$BaseUrl$Path"; Headers = $headers; ContentType = "application/json"; UseBasicParsing = $true }
    if ($null -ne $Body) { $params.Body = $Body | ConvertTo-Json -Depth 12 }
    try {
        $response = Invoke-WebRequest @params
        if ([string]::IsNullOrWhiteSpace($response.Content)) { return $null }
        return $response.Content | ConvertFrom-Json
    } catch {
        $response = $_.Exception.Response
        if ($response) {
            $reader = [System.IO.StreamReader]::new($response.GetResponseStream())
            try { throw "$Method $Path failed ($([int]$response.StatusCode)): $($reader.ReadToEnd())" }
            finally { $reader.Dispose() }
        }
        throw
    }
}

function Assert-True([bool]$Condition, [string]$Message) {
    if (-not $Condition) { throw $Message }
}

function New-Period([string]$Grade, [object]$Readiness, [string]$Stamp, [string]$Token, [object]$Year, [object]$Semester) {
    return Invoke-Json POST "/exam-periods" @{
        code = "G5-GLOBAL-$Grade-$Stamp"
        name = "Nghiem thu xung dot toan cuc $Grade"
        academicYearId = $Year.id
        semesterId = $Semester.id
        examType = $ExamType
        gradeLevels = @($Grade)
        allowSubjectTeacherProctor = $false
        startDate = $Readiness.suggestedStartDate
        endDate = $Readiness.suggestedEndDate
    } $Token
}

function Get-Draft([string]$PeriodId, [string]$Token) {
    return @((Invoke-Json GET "/exam-periods/$PeriodId/versions" $null $Token)) |
        Where-Object status -eq "DRAFT" | Select-Object -First 1
}

function Generate([object]$Period, [object]$Readiness, [string]$Token) {
    $draft = Get-Draft $Period.id $Token
    return Invoke-Json POST "/exam-periods/$($Period.id)/versions/$($draft.id)/generate" @{
        examDates = @($Readiness.suggestedExamDates)
        startTimes = @("07:30", "13:30")
    } $Token
}

Write-Host "SSE global $ExamType exam resource conflict smoke for $SemesterCode against $BaseUrl"
$login = Invoke-Json POST "/auth/login" @{ username = $AdminUsername; password = $AdminPassword }
$token = $login.accessToken
$year = @((Invoke-Json GET "/academic-years" $null $token)) | Where-Object status -eq "ACTIVE" | Select-Object -First 1
$semesterSequence = if ($SemesterCode -eq "HK1") { 1 } else { 2 }
$semester = @((Invoke-Json GET "/semesters" $null $token)) |
    Where-Object { $_.academicYearId -eq $year.id -and $_.sequence -eq $semesterSequence } |
    Select-Object -First 1
Write-Host "Using year=$($year.id), semester=$($semester.id)"
$allGrades = Invoke-Json GET "/exam-periods/assessment-sources?academicYearId=$($year.id)&semesterId=$($semester.id)&examType=$ExamType&gradeLevels=K10&gradeLevels=K11&gradeLevels=K12" $null $token
Assert-True ($allGrades.ready -and $allGrades.sourceCount -eq 36) "Whole-school G3 sources are not ready"
$k11Sources = @($allGrades.sources | Where-Object gradeLevel -eq "K11")
Assert-True ($k11Sources.Count -eq 12) "K11 does not expose all 12 G3 assessment sources"
Assert-True (@($k11Sources | Where-Object planVersion -ne 2).Count -eq 0) `
    "K11 assessment readiness is not sourced from plan v2"
$wholeSchoolPeriod = $null
try {
    $wholeSchoolPeriod = Invoke-Json POST "/exam-periods" @{
        code = "G5-$ExamType-$SemesterCode-$(Get-Date -Format 'MMddHHmmss')"
        name = "Nghiem thu $ExamType $SemesterCode ba khoi"
        academicYearId = $year.id
        semesterId = $semester.id
        examType = $ExamType
        gradeLevels = @("K10", "K11", "K12")
        allowSubjectTeacherProctor = $false
        startDate = $allGrades.suggestedStartDate
        endDate = $allGrades.suggestedEndDate
    } $token
    $wholeSchoolDetail = Generate $wholeSchoolPeriod $allGrades $token
    Assert-True $wholeSchoolDetail.validation.valid "Whole-school schedule contains blocking conflicts"
    Assert-True ($wholeSchoolDetail.validation.sessionCount -eq 36) "Whole-school schedule did not generate all 36 subject-grade sessions"
    $k11Sessions = @($wholeSchoolDetail.sessions | Where-Object gradeLevel -eq "K11")
    Assert-True ($k11Sessions.Count -eq 12) "Generated schedule is missing K11 sessions"
    Assert-True (@($k11Sessions | Where-Object sourcePlanVersion -ne 2).Count -eq 0) `
        "Generated K11 sessions do not trace to plan v2"

    $slotResources = @{}
    foreach ($session in $wholeSchoolDetail.sessions) {
        $slotKey = "$($session.examDate)|$($session.startTime)"
        if (-not $slotResources.ContainsKey($slotKey)) {
            $slotResources[$slotKey] = @{ Rooms = @{}; Teachers = @{} }
        }
        foreach ($room in $session.rooms) {
            Assert-True (-not $slotResources[$slotKey].Rooms.ContainsKey($room.roomId)) "Room $($room.roomId) is duplicated in $slotKey"
            $slotResources[$slotKey].Rooms[$room.roomId] = $true
            foreach ($teacherId in @($room.primaryProctorId, $room.backupProctorId)) {
                if (-not [string]::IsNullOrWhiteSpace($teacherId)) {
                    Assert-True (-not $slotResources[$slotKey].Teachers.ContainsKey($teacherId)) "Teacher $teacherId is duplicated in $slotKey"
                    $slotResources[$slotKey].Teachers[$teacherId] = $true
                }
            }
        }
    }
    Write-Host "[OK] generated all 36 sessions without duplicated rooms or proctors in any slot"
    Write-Host "[OK] all 12 K11 sessions trace to published education plan v2"
} finally {
    if ($wholeSchoolPeriod) { Invoke-Json DELETE "/exam-periods/$($wholeSchoolPeriod.id)" $null $token | Out-Null }
}

Write-Host "SSE whole-school $ExamType $SemesterCode exam scheduling smoke completed and cleaned its test data."
