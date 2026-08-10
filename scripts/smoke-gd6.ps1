param(
    [string]$BaseUrl = "http://127.0.0.1:4000"
)

$ErrorActionPreference = "Stop"

function Login([string]$Username, [string]$Password) {
    return Invoke-RestMethod -Method Post -Uri "$BaseUrl/auth/login" `
        -ContentType "application/json" `
        -Body (@{ username = $Username; password = $Password } | ConvertTo-Json)
}

function Headers($Session) {
    return @{ Authorization = "Bearer $($Session.accessToken)" }
}

function Expect-Forbidden([scriptblock]$Action, [string]$Name) {
    try {
        & $Action | Out-Null
        throw "$Name should return 403"
    } catch {
        $status = [int]$_.Exception.Response.StatusCode
        if ($status -ne 403) { throw "$Name returned $status instead of 403" }
    }
}

Write-Host "SSE GD6 smoke against $BaseUrl"

$admin = Login "admin" "admin@123"
$teacher = Login "gv.toan" "teacher@123"
$student = Login "hs.minh" "student@123"
$parent = Login "ph.nguyen" "parent@123"
Write-Host "[OK] login for Admin, Teacher, Student and Parent"

$openApi = Invoke-RestMethod -Uri "$BaseUrl/v3/api-docs"
$requiredPaths = @(
    "/grade-configurations",
    "/attendance/excuse-requests",
    "/submissions/batch-grade",
    "/submissions/{id}/request-resubmission",
    "/clubs/{id}/register",
    "/chat/contacts",
    "/admin/notification-deliveries",
    "/reports/academic/export"
)
foreach ($path in $requiredPaths) {
    if (-not $openApi.paths.PSObject.Properties.Name.Contains($path)) {
        throw "OpenAPI is missing $path"
    }
}
Write-Host "[OK] OpenAPI publishes all GD6 domain endpoints"

foreach ($entry in @(
    @{ Name = "admin"; Session = $admin },
    @{ Name = "teacher"; Session = $teacher },
    @{ Name = "student"; Session = $student },
    @{ Name = "parent"; Session = $parent }
)) {
    $dashboard = Invoke-RestMethod -Uri "$BaseUrl/dashboard" -Headers (Headers $entry.Session)
    if ($null -eq $dashboard.shortcuts) { throw "$($entry.Name) dashboard has no shortcut payload" }
}
Write-Host "[OK] role dashboards expose actionable shortcut data"

$subjects = Invoke-RestMethod -Uri "$BaseUrl/subjects" -Headers (Headers $admin)
$semesters = Invoke-RestMethod -Uri "$BaseUrl/semesters" -Headers (Headers $admin)
$categories = Invoke-RestMethod -Uri "$BaseUrl/exam-categories" -Headers (Headers $admin)
if (-not $subjects.Count -or -not $semesters.Count -or -not $categories.Count) {
    throw "Grade configuration requires seeded subjects, semesters and exam categories"
}
$subject = $subjects | Select-Object -First 1
$semester = $semesters | Select-Object -First 1
$category = $categories | Select-Object -First 1
$existing = @(Invoke-RestMethod -Uri "$BaseUrl/grade-configurations?subjectId=$($subject.id)&semesterId=$($semester.id)" -Headers (Headers $admin)) | Where-Object { $_.categoryCode -eq $category.code } | Select-Object -First 1
$payload = @{
    subjectId = $subject.id
    semesterId = $semester.id
    categoryCode = $category.code
    categoryName = $(if ($existing) { $existing.categoryName } else { $category.name })
    requiredCount = $(if ($existing) { $existing.requiredCount } else { 1 })
    weight = $(if ($existing) { $existing.weight } elseif ($category.weight) { $category.weight } else { 1 })
    active = $(if ($null -ne $existing) { $existing.active } else { $true })
} | ConvertTo-Json
$savedConfig = Invoke-RestMethod -Method Put -Uri "$BaseUrl/grade-configurations" -Headers (Headers $admin) -ContentType "application/json" -Body $payload
if ($savedConfig.subjectId -ne $subject.id) { throw "Grade configuration was not persisted" }
Expect-Forbidden {
    Invoke-RestMethod -Method Put -Uri "$BaseUrl/grade-configurations" -Headers (Headers $teacher) -ContentType "application/json" -Body $payload
} "Teacher grade configuration update"
Write-Host "[OK] grade configuration persists and is Admin-only"

$null = Invoke-RestMethod -Uri "$BaseUrl/attendance/excuse-requests" -Headers (Headers $admin)
$null = Invoke-RestMethod -Uri "$BaseUrl/attendance/excuse-requests" -Headers (Headers $teacher)
$null = Invoke-RestMethod -Uri "$BaseUrl/attendance/excuse-requests" -Headers (Headers $student)
$children = Invoke-RestMethod -Uri "$BaseUrl/me/children" -Headers (Headers $parent)
$firstChild = $children | Select-Object -First 1
if ($null -eq $firstChild) { throw "Parent fixture has no child" }
$null = Invoke-RestMethod -Uri "$BaseUrl/attendance/excuse-requests?studentId=$($firstChild.id)" -Headers (Headers $parent)
Write-Host "[OK] leave/excuse request lists are available with role scoping"

$null = Invoke-RestMethod -Uri "$BaseUrl/assignments" -Headers (Headers $teacher)
$needsGrading = Invoke-RestMethod -Uri "$BaseUrl/assignments?status=NEEDS_GRADING" -Headers (Headers $teacher)
foreach ($assignment in $needsGrading) {
    $assignmentSubmissions = Invoke-RestMethod -Uri "$BaseUrl/assignments/$($assignment.id)/submissions" -Headers (Headers $teacher)
    $pendingSubmissions = @($assignmentSubmissions | Where-Object { $_.status -in @('SUBMITTED', 'LATE') })
    if ($pendingSubmissions.Count -eq 0) { throw "NEEDS_GRADING returned an assignment without pending submissions" }
}
$null = Invoke-RestMethod -Uri "$BaseUrl/me/assignments" -Headers (Headers $student)
Write-Host "[OK] assignment workspaces and actionable pending-grading filter load"

$null = Invoke-RestMethod -Uri "$BaseUrl/clubs" -Headers (Headers $student)
$null = Invoke-RestMethod -Uri "$BaseUrl/me/club-registrations?studentId=$($firstChild.id)" -Headers (Headers $parent)
Write-Host "[OK] extracurricular catalog and scoped registrations load"

$parentContacts = @(Invoke-RestMethod -Uri "$BaseUrl/chat/contacts" -Headers (Headers $parent))
$teacherContacts = @(Invoke-RestMethod -Uri "$BaseUrl/chat/contacts" -Headers (Headers $teacher))
$teacherAnnouncementScopes = Invoke-RestMethod -Uri "$BaseUrl/teacher/announcements/scopes" -Headers (Headers $teacher)
$null = Invoke-RestMethod -Uri "$BaseUrl/teacher/announcements" -Headers (Headers $teacher)
$null = Invoke-RestMethod -Uri "$BaseUrl/chat/threads" -Headers (Headers $parent)
if ($parentContacts.Count -eq 0) { throw "Parent has no homeroom contact" }
if ($teacherAnnouncementScopes.Count -eq 0) { throw "Teacher has no announcement scope" }
Expect-Forbidden {
    Invoke-RestMethod -Method Post -Uri "$BaseUrl/chat/messages" -Headers (Headers $parent) -ContentType "application/json" `
        -Body (@{ toUserId = $admin.user.id; body = "GD6 scope check" } | ConvertTo-Json)
} "Parent chat outside homeroom scope"
Write-Host "[OK] Parent-GVCN chat and Teacher announcement scopes load with role protection"

$summary = Invoke-RestMethod -Uri "$BaseUrl/admin/notification-operations/summary" -Headers (Headers $admin)
$deliveries = @(Invoke-RestMethod -Uri "$BaseUrl/admin/notification-deliveries" -Headers (Headers $admin))
if ($null -eq $summary.totalNotifications) { throw "Notification operation summary is incomplete" }
Write-Host "[OK] notification operation summary and provider delivery logs load"

$academic = Invoke-RestMethod -Uri "$BaseUrl/reports/academic" -Headers (Headers $admin)
if ($null -eq $academic.summary) { throw "Academic report summary is missing" }
Expect-Forbidden {
    Invoke-RestMethod -Uri "$BaseUrl/reports/academic" -Headers (Headers $teacher)
} "Teacher academic report access"
$xlsx = Invoke-WebRequest -UseBasicParsing -Uri "$BaseUrl/reports/academic/export?format=XLSX" -Headers (Headers $admin)
$pdf = Invoke-WebRequest -UseBasicParsing -Uri "$BaseUrl/reports/academic/export?format=PDF" -Headers (Headers $admin)
if ($xlsx.Content[0] -ne 80 -or $xlsx.Content[1] -ne 75) { throw "XLSX does not have a ZIP signature" }
$pdfSignature = [System.Text.Encoding]::ASCII.GetString($pdf.Content[0..3])
if ($pdfSignature -ne "%PDF") { throw "PDF does not have a PDF signature" }
Write-Host "[OK] academic report scope, XLSX export and PDF export"

Write-Host "SSE GD6 smoke completed successfully."
