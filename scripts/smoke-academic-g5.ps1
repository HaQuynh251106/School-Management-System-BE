param(
    [string]$BaseUrl = "http://127.0.0.1:4000",
    [string]$AdminUsername = "admin",
    [string]$AdminPassword = "admin@123",
    [string]$TeacherPassword = "teacher@123",
    [string]$StudentPassword = "student@123",
    [string]$ParentPassword = "parent@123"
)

$ErrorActionPreference = "Stop"

function Invoke-Json {
    param(
        [ValidateSet("GET", "POST", "PUT", "DELETE")][string]$Method,
        [string]$Path,
        [object]$Body = $null,
        [string]$Token = $null
    )
    $headers = @{}
    if ($Token) { $headers.Authorization = "Bearer $Token" }
    $params = @{
        Method = $Method
        Uri = "$BaseUrl$Path"
        Headers = $headers
        ContentType = "application/json"
        UseBasicParsing = $true
    }
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

function Login([string]$Username, [string]$Password) {
    return Invoke-Json POST "/auth/login" @{ username = $Username; password = $Password }
}

function Assert-True([bool]$Condition, [string]$Message) {
    if (-not $Condition) { throw $Message }
}

function As-Array([object]$Value) {
    if ($null -eq $Value) { return @() }
    return @($Value)
}

Write-Host "SSE G5 exam schedule smoke against $BaseUrl"

$admin = Login $AdminUsername $AdminPassword
$token = $admin.accessToken
$years = @(As-Array (Invoke-Json GET "/academic-years" $null $token))
$year = $years | Where-Object status -eq "ACTIVE" | Select-Object -First 1
if (-not $year) { $year = $years | Select-Object -First 1 }
Assert-True ($null -ne $year) "No academic year exists"

$semesters = @(As-Array (Invoke-Json GET "/semesters" $null $token))
$semester = $semesters | Where-Object academicYearId -eq $year.id | Sort-Object sequence | Select-Object -First 1
Assert-True ($null -ne $semester) "The selected academic year has no semester"

$examType = "FINAL"
$sourceGrades = @()
$sourceRows = @()
$sourceReadiness = $null
$existingPeriods = @(As-Array (Invoke-Json GET "/exam-periods?academicYearId=$($year.id)" $null $token))
foreach ($grade in @("K10", "K11", "K12")) {
    $readiness = Invoke-Json GET "/exam-periods/assessment-sources?academicYearId=$($year.id)&semesterId=$($semester.id)&examType=$examType&gradeLevels=$grade" $null $token
    $hasPublishedOverlap = @($existingPeriods | Where-Object {
        $_.publishedVersionId -and $_.status -ne "CANCELLED" -and
        $grade -in @(As-Array $_.gradeLevels) -and
        $_.startDate -le $readiness.suggestedEndDate -and $_.endDate -ge $readiness.suggestedStartDate
    }).Count -gt 0
    if ($readiness.ready -and $readiness.sourceCount -gt 0 -and -not $hasPublishedOverlap) {
        # One fully configured grade is enough for the smoke path. Multi-grade
        # source resolution is covered by the service tests and the same API.
        $sourceGrades = @($grade)
        $sourceRows = @(As-Array $readiness.sources)
        $sourceReadiness = $readiness
        break
    }
}
Assert-True ($sourceGrades.Count -gt 0) "No published G3 FINAL assessment plan is available for G5"
$sourceSubjectCount = @($sourceRows | Select-Object -ExpandProperty subjectId -Unique).Count
Write-Host "[OK] loaded $($sourceRows.Count) canonical assessment sources from published G3 plans"

$requiredDays = [Math]::Ceiling($sourceSubjectCount / 2)
$examDates = @(As-Array $sourceReadiness.suggestedExamDates)
Assert-True ($examDates.Count -ge $requiredDays) "Could not calculate enough exam dates"

$shortPeriodRejected = $false
try {
    Invoke-Json POST "/exam-periods" @{
        code = "G5-SHORT-$(Get-Date -Format 'MMddHHmmss')"
        name = "Khoang thi qua ngan"
        academicYearId = $year.id
        semesterId = $semester.id
        examType = "FINAL"
        gradeLevels = $sourceGrades
        allowSubjectTeacherProctor = $false
        startDate = $examDates[0]
        endDate = $examDates[0]
    } $token | Out-Null
} catch {
    if ($_.Exception.Message -match "\(400\)") {
        $shortPeriodRejected = $true
    } else { throw }
}
Assert-True $shortPeriodRejected "A period with too few exam days was accepted"
Write-Host "[OK] too-short period is rejected before it can be created"

$stamp = Get-Date -Format "MMddHHmmss"
$period = Invoke-Json POST "/exam-periods" @{
    code = "G5-$stamp"
    name = "Nghiem thu lich thi $stamp"
    academicYearId = $year.id
    semesterId = $semester.id
    examType = "FINAL"
    gradeLevels = $sourceGrades
    allowSubjectTeacherProctor = $false
    startDate = $examDates[0]
    endDate = $examDates[-1]
} $token
Write-Host "[OK] created exam period $($period.code)"

$versions = @(As-Array (Invoke-Json GET "/exam-periods/$($period.id)/versions" $null $token))
$version1 = $versions | Select-Object -First 1
$teachers = @(As-Array (Invoke-Json GET "/users?role=TEACHER" $null $token) | Where-Object status -eq "ACTIVE")
$awayTeacher = $teachers | Select-Object -First 1
$away = Invoke-Json POST "/exam-periods/$($period.id)/teacher-unavailability" @{
    teacherId = $awayTeacher.id
    unavailableDate = $examDates[0]
    endDate = $examDates[0]
    startTime = $null
    endTime = $null
    reason = "Nghi ca ngay de nghiem thu bo xep"
} $token
$detail1 = Invoke-Json POST "/exam-periods/$($period.id)/versions/$($version1.id)/generate" @{
    examDates = $examDates
    startTimes = @("07:30", "13:30")
} $token
Assert-True $detail1.validation.valid "Auto-generated schedule contains blocking errors"
Assert-True ($detail1.validation.sessionCount -eq $sourceRows.Count) "Generated session count differs from G3 source count"
Assert-True ($detail1.validation.roomCount -gt 0) "No exam room was generated"
Assert-True ($detail1.validation.studentCount -gt 0) "No student was assigned"
foreach ($source in $sourceRows) {
    $session = $detail1.sessions | Where-Object sourceAssessmentPlanId -eq $source.assessmentPlanId | Select-Object -First 1
    Assert-True ($null -ne $session) "A G3 assessment source was not scheduled"
    Assert-True ($session.durationMinutes -eq $source.durationMinutes) "G5 changed the duration defined in G3"
}
$assignedTeachersOnAwayDate = @($detail1.sessions | Where-Object examDate -eq $examDates[0] |
    ForEach-Object { $_.rooms } | ForEach-Object { $_.primaryProctorId; $_.backupProctorId })
Assert-True ($awayTeacher.id -notin $assignedTeachersOnAwayDate) "Unavailable teacher was assigned on the blocked day"
$assignedTeachersOnOtherDates = @($detail1.sessions | Where-Object examDate -ne $examDates[0] |
    ForEach-Object { $_.rooms } | ForEach-Object { $_.primaryProctorId; $_.backupProctorId })
Assert-True ($awayTeacher.id -in $assignedTeachersOnOtherDates) "One-day leave incorrectly blocked the teacher for the whole exam period"
Write-Host "[OK] auto generated $($detail1.validation.sessionCount) sessions, $($detail1.validation.roomCount) rooms, $($detail1.validation.studentCount) student seats"
Write-Host "[OK] G3 durations and teacher unavailability are respected"

$validation1 = Invoke-Json GET "/exam-periods/$($period.id)/versions/$($version1.id)/validate" $null $token
Assert-True $validation1.valid "Generated version did not pass the explicit validation step"
$published1 = Invoke-Json POST "/exam-periods/$($period.id)/versions/$($version1.id)/publish" $null $token
Assert-True ($published1.period.status -eq "PUBLISHED") "Period was not published"
Write-Host "[OK] validated and published version 1"

$version2 = Invoke-Json POST "/exam-periods/$($period.id)/versions" @{ reason = "Kiem tra dieu chinh thu cong" } $token
$cloned = Invoke-Json GET "/exam-periods/$($period.id)/versions/$($version2.id)" $null $token
Assert-True ($cloned.validation.sessionCount -eq $detail1.validation.sessionCount) "Version cloning lost exam sessions"
Assert-True ($cloned.validation.studentCount -eq $detail1.validation.studentCount) "Version cloning lost student assignments"
Assert-True $cloned.versionDiff.comparisonAvailable "Version 2 does not expose a comparison with version 1"
Assert-True (-not $cloned.versionDiff.hasChanges) "Freshly cloned version 2 must be unchanged"
Assert-True (@($cloned.validation.issues | Where-Object code -eq "NO_VERSION_CHANGES").Count -eq 1) "Unchanged version is not blocked"
$unchangedRejected = $false
try {
    Invoke-Json POST "/exam-periods/$($period.id)/versions/$($version2.id)/publish" $null $token | Out-Null
} catch {
    if ($_.Exception.Message -match "\(409\)") { $unchangedRejected = $true }
    else { throw }
}
Assert-True $unchangedRejected "An unchanged version 2 was published"
Write-Host "[OK] unchanged version 2 is compared with version 1 and cannot be published"

$firstSession = $cloned.sessions | Select-Object -First 1
$updated = Invoke-Json PUT "/exam-periods/$($period.id)/versions/$($version2.id)/sessions/$($firstSession.id)" @{
    sourceAssessmentPlanId = $firstSession.sourceAssessmentPlanId
    examDate = $firstSession.examDate
    startTime = $firstSession.startTime
    scheduleDeviationReason = $firstSession.scheduleDeviationReason
    notes = "Da kiem tra dieu chinh thu cong"
} $token
Assert-True ($updated.notes -eq "Da kiem tra dieu chinh thu cong") "Manual session update was not stored"
$changedDetail = Invoke-Json GET "/exam-periods/$($period.id)/versions/$($version2.id)" $null $token
Assert-True $changedDetail.versionDiff.hasChanges "Version comparison did not detect the manual change"
Assert-True ($changedDetail.versionDiff.changedSessions -ge 1) "Changed session counter was not updated"
$validation2 = Invoke-Json GET "/exam-periods/$($period.id)/versions/$($version2.id)/validate" $null $token
Assert-True $validation2.valid "Adjusted version contains blocking errors"
$published2 = Invoke-Json POST "/exam-periods/$($period.id)/versions/$($version2.id)/publish" $null $token
Assert-True ($published2.version.versionNo -eq 2) "Version 2 was not published"
$history = @(As-Array (Invoke-Json GET "/exam-periods/$($period.id)/versions" $null $token))
Assert-True (@($history | Where-Object status -eq "ARCHIVED").Count -eq 1) "Old published version was not archived"
Write-Host "[OK] cloned, manually adjusted and republished version 2; version 1 archived"

$parents = @(As-Array (Invoke-Json GET "/users?role=PARENT" $null $token))
$parentLogin = $null
$children = @()
$selectedChild = $null
foreach ($candidate in $parents) {
    try {
        $login = Login $candidate.username $ParentPassword
        $candidateChildren = @(As-Array (Invoke-Json GET "/me/children" $null $login.accessToken))
        $matchingChild = $candidateChildren | Where-Object {
            $_.className -and ("K" + $_.className.Substring(0, 2)) -in $sourceGrades
        } | Select-Object -First 1
        if ($matchingChild) {
            $parentLogin = $login
            $children = $candidateChildren
            $selectedChild = $matchingChild
            break
        }
    } catch { }
}
Assert-True ($null -ne $parentLogin) "Could not find a parent account with children"
$child = $selectedChild
$childGrade = "K" + $child.className.Substring(0, 2)
$expectedChildExams = @($sourceRows | Where-Object gradeLevel -eq $childGrade).Count
$parentView = @(As-Array (Invoke-Json GET "/exam-periods/students/$($child.id)/schedule" $null $parentLogin.accessToken))
Assert-True (@($parentView | Where-Object periodId -eq $period.id).Count -eq $expectedChildExams) "Parent does not see every published exam for the selected child"

$students = @(As-Array (Invoke-Json GET "/users?role=STUDENT" $null $token))
$studentAccount = $students | Where-Object id -eq $child.id | Select-Object -First 1
$studentLogin = Login $studentAccount.username $StudentPassword
$studentView = @(As-Array (Invoke-Json GET "/exam-periods/me/schedule" $null $studentLogin.accessToken))
Assert-True (@($studentView | Where-Object periodId -eq $period.id).Count -eq $expectedChildExams) "Student does not see every published exam"

$proctorId = $published2.sessions[0].rooms[0].primaryProctorId
$proctor = $teachers | Where-Object id -eq $proctorId | Select-Object -First 1
$teacherLogin = Login $proctor.username $TeacherPassword
$teacherView = @(As-Array (Invoke-Json GET "/exam-periods/me/schedule" $null $teacherLogin.accessToken))
Assert-True (@($teacherView | Where-Object periodId -eq $period.id).Count -gt 0) "Assigned proctor cannot see the invigilation schedule"
Write-Host "[OK] student, parent and assigned teacher can read only their published schedule"

Start-Sleep -Seconds 2
$studentExamNotifications = @(As-Array (Invoke-Json GET "/notifications" $null $studentLogin.accessToken) |
    Where-Object { $_.type -eq "EXAM" -and $_.refId -eq $period.id })
$parentExamNotifications = @(As-Array (Invoke-Json GET "/notifications" $null $parentLogin.accessToken) |
    Where-Object { $_.type -eq "EXAM" -and $_.refId -eq $period.id })
Assert-True ($studentExamNotifications.Count -gt 0) "Student did not receive the exam notification"
Assert-True ($parentExamNotifications.Count -gt 0) "Parent did not receive the exam notification"

$recalled = Invoke-Json POST "/exam-periods/$($period.id)/recall" @{ reason = "Nghiem thu thu hoi ve nhap" } $token
Assert-True ($recalled.period.status -eq "DRAFT") "Recalled period did not return to DRAFT"
Assert-True ($null -eq $recalled.period.publishedVersionId) "Recalled period still exposes a published version"
Assert-True ($recalled.version.status -eq "DRAFT") "Recall did not create or reuse a draft version"
$hiddenAfterRecall = @(As-Array (Invoke-Json GET "/exam-periods/me/schedule" $null $studentLogin.accessToken) |
    Where-Object periodId -eq $period.id)
Assert-True ($hiddenAfterRecall.Count -eq 0) "Recalled schedule is still visible to students"
$recalledSession = $recalled.sessions | Select-Object -First 1
Invoke-Json PUT "/exam-periods/$($period.id)/versions/$($recalled.version.id)/sessions/$($recalledSession.id)" @{
    sourceAssessmentPlanId = $recalledSession.sourceAssessmentPlanId
    examDate = $recalledSession.examDate
    startTime = $recalledSession.startTime
    scheduleDeviationReason = $recalledSession.scheduleDeviationReason
    notes = "Da kiem tra sau khi thu hoi"
} $token | Out-Null
$recallValidation = Invoke-Json GET "/exam-periods/$($period.id)/versions/$($recalled.version.id)/validate" $null $token
Assert-True $recallValidation.valid "Recalled draft did not pass validation"
$republished = Invoke-Json POST "/exam-periods/$($period.id)/versions/$($recalled.version.id)/publish" $null $token
Assert-True ($republished.period.status -eq "PUBLISHED") "Recalled draft could not be published again"
Write-Host "[OK] published schedule can be recalled, hidden and republished"

$manualStart = $sourceReadiness.suggestedStartDate
$manualEnd = $sourceReadiness.suggestedEndDate
$manualSource = $sourceRows | Where-Object gradeLevel -eq $sourceGrades[0] | Select-Object -First 1
$manualExamDate = $manualSource.plannedStartDate

$manualPeriod = Invoke-Json POST "/exam-periods" @{
    code = "G5-MANUAL-$stamp"
    name = "Nghiem thu xep lich thu cong $stamp"
    academicYearId = $year.id
    semesterId = $semester.id
    examType = $examType
    gradeLevels = @($sourceGrades[0])
    allowSubjectTeacherProctor = $false
    startDate = $manualStart
    endDate = $manualEnd
} $token
$manualVersion = @(As-Array (Invoke-Json GET "/exam-periods/$($manualPeriod.id)/versions" $null $token)) | Select-Object -First 1
$manualSession = Invoke-Json POST "/exam-periods/$($manualPeriod.id)/versions/$($manualVersion.id)/sessions" @{
    sourceAssessmentPlanId = $manualSource.assessmentPlanId
    examDate = $manualExamDate
    startTime = "10:30"
    scheduleDeviationReason = $null
    notes = "Ca thi xep thu cong"
} $token
Assert-True (-not [string]::IsNullOrWhiteSpace($manualSession.sourceAssessmentPlanId)) "Manual session was not linked to G3"
Assert-True ($manualSession.rooms.Count -gt 0) "Manual session did not allocate rooms"
Assert-True ($manualSession.studentCount -gt 0) "Manual session did not allocate students"
Invoke-Json DELETE "/exam-periods/$($manualPeriod.id)" $null $token | Out-Null
Write-Host "[OK] manual scheduling allocates resources and draft period can be deleted"

$audit = @(As-Array (Invoke-Json GET "/audit-logs?module=academic" $null $token))
$examAudit = @($audit | Where-Object { $_.entityId -eq $period.id -or $_.entityId -eq $version1.id -or $_.entityId -eq $version2.id })
Assert-True ($examAudit.Count -ge 5) "Exam schedule changes were not fully audited"
Write-Host "[OK] RabbitMQ notifications and academic audit trail are present"

$publishedDeleteRejected = $false
try { Invoke-Json DELETE "/exam-periods/$($period.id)" $null $token | Out-Null }
catch { if ($_.Exception.Message -match "\(409\)") { $publishedDeleteRejected = $true } else { throw } }
Assert-True $publishedDeleteRejected "Published exam period was deleted instead of being protected"
$closed = Invoke-Json POST "/exam-periods/$($period.id)/status" @{ status = "CLOSED"; reason = "Ket thuc smoke G5" } $token
Assert-True ($closed.status -eq "CLOSED") "Published exam period could not be closed"
Write-Host "[OK] published history is protected from deletion and the period can be closed"
Write-Host "SSE G5 exam schedule smoke completed successfully."
