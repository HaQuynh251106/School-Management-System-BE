param(
    [string]$BaseUrl = "http://127.0.0.1:4000",
    [string]$AdminUsername = "admin",
    [string]$AdminPassword = "admin@123",
    [string]$TeacherUsername = "gv.toan",
    [string]$TeacherPassword = "teacher@123"
)

$ErrorActionPreference = "Stop"

function Assert-True([bool]$Condition, [string]$Message) {
    if (-not $Condition) { throw $Message }
}

function As-Array([object]$Value) {
    if ($null -eq $Value) { return @() }
    $properties = @($Value.PSObject.Properties.Name)
    if (($properties -contains "value") -and ($properties -contains "Count")) {
        return @($Value.value)
    }
    return @($Value)
}

function Login([string]$Username, [string]$Password) {
    return Invoke-RestMethod -Method Post -Uri "$BaseUrl/auth/login" `
        -ContentType "application/json" `
        -Body (@{ username = $Username; password = $Password } | ConvertTo-Json)
}

function Invoke-Json(
    [string]$Method,
    [string]$Path,
    [hashtable]$Headers,
    [object]$Body = $null
) {
    $params = @{
        Method = $Method
        Uri = "$BaseUrl$Path"
        Headers = $Headers
        ContentType = "application/json; charset=utf-8"
    }
    if ($null -ne $Body) {
        $params.Body = $Body | ConvertTo-Json -Depth 12
    }
    try {
        return Invoke-RestMethod @params
    } catch {
        $status = if ($_.Exception.Response) { [int]$_.Exception.Response.StatusCode } else { 0 }
        throw "$Method $Path failed with HTTP $status"
    }
}

function Invoke-Status(
    [string]$Method,
    [string]$Path,
    [hashtable]$Headers
) {
    try {
        Invoke-WebRequest -Method $Method -Uri "$BaseUrl$Path" `
            -Headers $Headers -UseBasicParsing | Out-Null
        return 200
    } catch {
        if ($null -eq $_.Exception.Response) { throw }
        return [int]$_.Exception.Response.StatusCode
    }
}

function Invoke-JsonStatus(
    [string]$Method,
    [string]$Path,
    [hashtable]$Headers,
    [object]$Body
) {
    try {
        Invoke-WebRequest -Method $Method -Uri "$BaseUrl$Path" `
            -Headers $Headers -ContentType "application/json; charset=utf-8" `
            -Body ($Body | ConvertTo-Json -Depth 12) -UseBasicParsing | Out-Null
        return 200
    } catch {
        if ($null -eq $_.Exception.Response) { throw }
        return [int]$_.Exception.Response.StatusCode
    }
}

Write-Host "SSE complete academic G3 smoke against $BaseUrl"
$admin = Login $AdminUsername $AdminPassword
$adminHeaders = @{ Authorization = "Bearer $($admin.accessToken)" }

$programs = As-Array (Invoke-Json Get "/academic/education-planning/programs" $adminHeaders)
$activeProgram = $programs | Where-Object { $_.status -eq "ACTIVE" } | Select-Object -First 1
Assert-True ($null -ne $activeProgram) "No active education program"

$years = As-Array (Invoke-Json Get "/academic-years" $adminHeaders)
$activeYear = $years | Where-Object { $_.status -eq "ACTIVE" } | Select-Object -First 1
Assert-True ($null -ne $activeYear) "No active academic year"

$allPlans = As-Array (Invoke-Json Get "/academic/training-plans" $adminHeaders)
$grade = @("K11", "K12", "K10") | Where-Object {
    $candidate = $_
    -not ($allPlans | Where-Object {
        $_.academicYearId -eq $activeYear.id -and $_.gradeLevel -eq $candidate
    })
} | Select-Object -First 1

if (-not $grade) {
    foreach ($existingGrade in @("K10", "K11", "K12")) {
        $published = $allPlans | Where-Object {
            $_.academicYearId -eq $activeYear.id -and
            $_.gradeLevel -eq $existingGrade -and
            $_.status -in @("PUBLISHED", "LOCKED")
        } | Sort-Object versionNumber -Descending | Select-Object -First 1
        Assert-True ($null -ne $published) "$existingGrade has no published training plan"
        $existingValidation = Invoke-Json Get "/academic/training-plans/$($published.id)/validation" $adminHeaders
        Assert-True ($existingValidation.errorCount -eq 0) `
            "$existingGrade published plan v$($published.versionNumber) has $($existingValidation.errorCount) mandatory validation errors"
        $immutableStatus = Invoke-JsonStatus Put "/academic/training-plans/$($published.id)" `
            $adminHeaders @{
                name = $published.name
                maxProgressGapDays = $published.maxProgressGapDays
                programId = $published.programId
                description = $published.description
            }
        Assert-True ($immutableStatus -eq 409) `
            "$existingGrade published plan v$($published.versionNumber) can still be edited directly"
        Write-Host "[OK] $existingGrade published plan v$($published.versionNumber): 0 mandatory errors, $($existingValidation.warningCount) warnings"
    }
    $archivedK11 = $allPlans | Where-Object {
        $_.academicYearId -eq $activeYear.id -and $_.gradeLevel -eq "K11" -and
        $_.status -eq "ARCHIVED"
    } | Sort-Object versionNumber -Descending | Select-Object -First 1
    Assert-True ($null -ne $archivedK11) "K11 has no archived predecessor after publishing v2"
    $archivedStatus = Invoke-JsonStatus Put "/academic/training-plans/$($archivedK11.id)" `
        $adminHeaders @{
            name = $archivedK11.name
            maxProgressGapDays = $archivedK11.maxProgressGapDays
            programId = $archivedK11.programId
            description = $archivedK11.description
        }
    Assert-True ($archivedStatus -eq 409) "Archived K11 plan can still be edited directly"
    Write-Host "[OK] published and archived plan versions are immutable"
    Write-Host "SSE complete academic G3 smoke completed against existing published plans."
    exit 0
}

$programSubjects = As-Array (Invoke-Json Get (
    "/academic/education-planning/programs/$($activeProgram.id)/subjects?gradeLevel=$grade"
) $adminHeaders)
$combinations = As-Array (Invoke-Json Get (
    "/academic/education-planning/combinations?academicYearId=$($activeYear.id)&gradeLevel=$grade"
) $adminHeaders)
Assert-True ($programSubjects.Count -gt 0) "Program has no subjects for $grade"
Assert-True ($combinations.Count -gt 0) "Grade has no subject combinations"
Write-Host "[OK] program subjects and subject combinations"

$planId = "plan-g3-smoke-$([guid]::NewGuid().ToString('N').Substring(0, 8))"
$excelPath = Join-Path $env:TEMP "$planId.xlsx"
$pdfPath = Join-Path $env:TEMP "$planId.pdf"

try {
    $plan = Invoke-Json Post "/academic/training-plans" $adminHeaders @{
        id = $planId
        academicYearId = $activeYear.id
        gradeLevel = $grade
        name = "Kiem thu G3"
        programId = $activeProgram.id
        maxProgressGapDays = 2
        description = "Du lieu tam, script se tu xoa"
    }
    Assert-True ($plan.status -eq "DRAFT") "New plan must be DRAFT"

    $initialized = Invoke-Json Post (
        "/academic/training-plans/$planId/initialize-from-program"
    ) $adminHeaders
    Assert-True ($initialized.subjectRowsCreated -gt 0) "Program initialization created no subject rows"

    $summary = As-Array (Invoke-Json Get "/academic/training-plans/$planId/annual-summary" $adminHeaders)
    $validation = Invoke-Json Get "/academic/training-plans/$planId/validation" $adminHeaders
    Assert-True ($summary.Count -eq $programSubjects.Count) "Annual summary does not match program subjects"
    Assert-True ($validation.errorCount -eq 0) "Initialized plan has mandatory validation errors"
    Write-Host "[OK] initialize, annual periods and validation"

    $earlyPublish = Invoke-Status Post "/academic/training-plans/$planId/publish" $adminHeaders
    Assert-True ($earlyPublish -eq 409) "DRAFT plan must not be published before approval"
    Write-Host "[OK] workflow blocks publish before approval"

    & curl.exe -sS -H "Authorization: Bearer $($admin.accessToken)" `
        -o $excelPath "$BaseUrl/academic/training-plans/$planId/export.xlsx"
    & curl.exe -sS -H "Authorization: Bearer $($admin.accessToken)" `
        -o $pdfPath "$BaseUrl/academic/training-plans/$planId/export.pdf"
    Assert-True ((Get-Item $excelPath).Length -gt 1000) "Excel export is empty"
    Assert-True ((Get-Item $pdfPath).Length -gt 1000) "PDF export is empty"
    Write-Host "[OK] Excel and PDF exports"

    $teacher = Login $TeacherUsername $TeacherPassword
    $teacherHeaders = @{ Authorization = "Bearer $($teacher.accessToken)" }
    Assert-True ($teacher.user.permissions -contains "ACADEMIC_PLAN_CONTENT_MANAGE") `
        "Teacher content permission is missing"
    Assert-True (-not ($teacher.user.permissions -contains "ACADEMIC_PLAN_MANAGE")) `
        "Legacy broad teacher plan permission was not revoked"

    $capabilities = As-Array (Invoke-Json Get (
        "/academic/education-planning/teachers/$($teacher.user.id)/subjects"
    ) $teacherHeaders)
    $rows = As-Array (Invoke-Json Get "/academic/training-plans/$planId/subjects" $teacherHeaders)
    $ownRow = $rows | Where-Object { $_.subjectId -in $capabilities.subjectId } | Select-Object -First 1
    $otherRow = $rows | Where-Object { $_.subjectId -notin $capabilities.subjectId } | Select-Object -First 1
    Assert-True ($null -ne $ownRow -and $null -ne $otherRow) "Cannot prepare teacher scope check"

    $ownDistributions = As-Array (Invoke-Json Get (
        "/academic/training-plans/$planId/subjects/$($ownRow.id)/distributions"
    ) $teacherHeaders)
    Invoke-Json Delete (
        "/academic/training-plans/$planId/distributions/$($ownDistributions[0].id)"
    ) $teacherHeaders | Out-Null

    $otherDistributions = As-Array (Invoke-Json Get (
        "/academic/training-plans/$planId/subjects/$($otherRow.id)/distributions"
    ) $teacherHeaders)
    $wrongSubjectStatus = Invoke-Status Delete (
        "/academic/training-plans/$planId/distributions/$($otherDistributions[0].id)"
    ) $teacherHeaders
    Assert-True ($wrongSubjectStatus -eq 403) "Teacher edited a subject outside declared expertise"
    Write-Host "[OK] teacher edits own subject and receives 403 for another subject"
}
finally {
    try { Invoke-Json Delete "/academic/training-plans/$planId" $adminHeaders | Out-Null } catch {}
    Remove-Item -LiteralPath $excelPath -Force -ErrorAction SilentlyContinue
    Remove-Item -LiteralPath $pdfPath -Force -ErrorAction SilentlyContinue
}

Write-Host "SSE complete academic G3 smoke completed successfully."
