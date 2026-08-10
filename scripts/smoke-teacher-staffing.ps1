param(
    [string]$BaseUrl = "http://127.0.0.1:4000",
    [string]$AdminUsername = "admin",
    [string]$AdminPassword = "admin@123",
    [string]$TeacherUsername = "gv.toan",
    [string]$TeacherPassword = "teacher@123"
)

$ErrorActionPreference = "Stop"

function Invoke-Api {
    param(
        [ValidateSet("GET", "POST", "PUT")]
        [string]$Method,
        [string]$Path,
        [object]$Body = $null,
        [string]$Token = $null,
        [int[]]$ExpectedStatus = @(200)
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
    if ($null -ne $Body) { $params.Body = $Body | ConvertTo-Json -Depth 10 }

    try {
        $response = Invoke-WebRequest @params
        $status = [int]$response.StatusCode
        $content = $response.Content
    } catch {
        if (-not $_.Exception.Response) { throw }
        $status = [int]$_.Exception.Response.StatusCode
        $content = $null
        try {
            $reader = [System.IO.StreamReader]::new($_.Exception.Response.GetResponseStream())
            try { $content = $reader.ReadToEnd() } finally { $reader.Dispose() }
        } catch {
            $content = $null
        }
    }

    if ($ExpectedStatus -notcontains $status) {
        throw "API $Method $Path returned HTTP $status. $content"
    }
    if ([string]::IsNullOrWhiteSpace($content)) { return $null }
    $parsed = $content | ConvertFrom-Json
    foreach ($item in @($parsed)) { Write-Output $item }
}

function Login([string]$Username, [string]$Password) {
    return Invoke-Api POST "/auth/login" @{ username = $Username; password = $Password }
}

Write-Host "Teacher staffing smoke against $BaseUrl"
$admin = Login $AdminUsername $AdminPassword
$teacher = Login $TeacherUsername $TeacherPassword

$years = @(Invoke-Api GET "/academic-years" $null $admin.accessToken)
$year = $years | Where-Object { $_.status -eq "ACTIVE" } | Select-Object -First 1
if (-not $year) { throw "No ACTIVE academic year was found" }

$semesters = @(Invoke-Api GET "/semesters?academicYearId=$($year.id)" $null $admin.accessToken)
$semester = $semesters | Sort-Object startDate | Select-Object -First 1
if (-not $semester) { throw "No semester was found for academic year $($year.id)" }

$analysisPath = "/academic/teacher-staffing?academicYearId=$($year.id)&semesterId=$($semester.id)"
$analysis = Invoke-Api GET $analysisPath $null $admin.accessToken

if ($analysis.schoolClassCount -le 0) { throw "Staffing analysis returned no classes" }
if ($analysis.totalAnnualPeriods -le 0) { throw "Staffing analysis returned no annual periods" }
if ($analysis.minimumSubjectTeachersForYear -le 0) { throw "Minimum teacher count was not calculated" }
if (@($analysis.subjects).Count -le 0) { throw "No subject staffing rows were returned" }

$expectedFte = [decimal]$analysis.schoolClassCount * [decimal]$analysis.policy.teacherClassRatio
if ([decimal]$analysis.maximumTeacherFte -ne $expectedFte) {
    throw "Maximum FTE is incorrect: expected $expectedFte, received $($analysis.maximumTeacherFte)"
}
$expectedWhole = [math]::Floor($expectedFte)
if ([int]$analysis.maximumWholeTeachers -ne $expectedWhole) {
    throw "Whole-teacher ceiling is incorrect: expected $expectedWhole, received $($analysis.maximumWholeTeachers)"
}

foreach ($row in @($analysis.subjects)) {
    if ($row.minimumTeachersForYear -lt 0 -or $row.qualifiedTeacherCount -lt 0 -or $row.shortage -lt 0) {
        throw "Invalid staffing values for subject $($row.subjectName)"
    }
}

Invoke-Api GET $analysisPath $null $teacher.accessToken @(403) | Out-Null

$readinessPath = "/timetable/schedules/generation-readiness?academicYearId=$($year.id)&semesterId=$($semester.id)&scopeGradeLevel=ALL"
$readiness = Invoke-Api GET $readinessPath $null $admin.accessToken
$staffingIssues = @($readiness.issues | Where-Object { $_.code -like "TEACHER_*" })

$policyPath = "/academic/teacher-staffing/policy/$($year.id)"
$originalPolicy = Invoke-Api GET $policyPath $null $admin.accessToken
Invoke-Api PUT $policyPath @{
    schoolType = "ETHNIC_BOARDING"
    weeklyTeachingNorm = 15
    teachingWeeks = $originalPolicy.teachingWeeks
} $admin.accessToken @(400) | Out-Null
try {
    Invoke-Api PUT $policyPath @{
        schoolType = $originalPolicy.schoolType
        weeklyTeachingNorm = 1
        teachingWeeks = $originalPolicy.teachingWeeks
    } $admin.accessToken | Out-Null

    $shortageAnalysis = Invoke-Api GET $analysisPath $null $admin.accessToken
    if ($shortageAnalysis.sufficientForTimetable) {
        throw "Artificial teacher shortage did not make staffing analysis fail"
    }
    $blockedReadiness = Invoke-Api GET $readinessPath $null $admin.accessToken
    $shortageIssues = @($blockedReadiness.issues | Where-Object {
        $_.code -eq "TEACHER_STAFFING_SHORTAGE"
    })
    if ($blockedReadiness.ready -or $shortageIssues.Count -eq 0) {
        throw "Artificial teacher shortage did not block automatic timetable readiness"
    }
} finally {
    Invoke-Api PUT $policyPath @{
        schoolType = $originalPolicy.schoolType
        weeklyTeachingNorm = $originalPolicy.weeklyTeachingNorm
        teachingWeeks = $originalPolicy.teachingWeeks
    } $admin.accessToken | Out-Null
}

Write-Host "[OK] admin can analyze staffing and teacher is forbidden"
Write-Host "[OK] maximum headcount formula matches class count x legal ratio"
Write-Host "[OK] non-public school types are rejected"
Write-Host "[OK] staffing analysis is connected to timetable readiness"
Write-Host "[OK] teacher shortage blocks generation and the original policy is restored"
Write-Host ""
Write-Host "Academic year: $($year.name)"
Write-Host "Classes: $($analysis.schoolClassCount)"
Write-Host "Annual class-periods: $($analysis.totalAnnualPeriods)"
Write-Host "Minimum subject teachers: $($analysis.minimumSubjectTeachersForYear)"
Write-Host "Current active teachers: $($analysis.currentActiveTeacherCount)"
Write-Host "Legal ceiling: $($analysis.maximumTeacherFte) FTE / $($analysis.maximumWholeTeachers) whole teachers"
Write-Host "Timetable ready: $($readiness.ready)"
Write-Host "Teacher readiness issues: $($staffingIssues.Count)"
Write-Host "Teacher staffing smoke completed successfully."
