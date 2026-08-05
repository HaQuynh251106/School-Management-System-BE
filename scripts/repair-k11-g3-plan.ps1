param(
    [string]$BaseUrl = "http://127.0.0.1:4000",
    [string]$AdminUsername = "admin",
    [string]$AdminPassword = "admin@123"
)

$ErrorActionPreference = "Stop"

function As-Array([object]$Value) {
    if ($null -eq $Value) { return @() }
    $properties = @($Value.PSObject.Properties.Name)
    if (($properties -contains "value") -and ($properties -contains "Count")) {
        return @($Value.value)
    }
    return @($Value)
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
        $json = $Body | ConvertTo-Json -Depth 12
        $params.Body = [System.Text.Encoding]::UTF8.GetBytes($json)
    }
    try {
        return Invoke-RestMethod @params
    } catch {
        $status = if ($_.Exception.Response) { [int]$_.Exception.Response.StatusCode } else { 0 }
        throw "$Method $Path failed with HTTP $status"
    }
}

function Assessment-Body([object]$Assessment, [string[]]$CurriculumItemIds) {
    return @{
        id = $Assessment.id
        semesterId = $Assessment.semesterId
        classId = $Assessment.classId
        subjectId = $Assessment.subjectId
        assessmentType = $Assessment.assessmentType
        name = $Assessment.name
        assessmentForm = $Assessment.assessmentForm
        curriculumItemIds = $CurriculumItemIds
        resultMethod = $Assessment.resultMethod
        weekNumber = $Assessment.weekNumber
        durationMinutes = $Assessment.durationMinutes
        teacherId = $Assessment.teacherId
        notes = $Assessment.notes
    }
}

Write-Host "Repairing K11 education plan through the versioned G3 workflow"
$login = Invoke-Json Post "/auth/login" @{} @{
    username = $AdminUsername
    password = $AdminPassword
}
$headers = @{ Authorization = "Bearer $($login.accessToken)" }
$plans = As-Array (Invoke-Json Get "/academic/training-plans" $headers)
$source = $plans | Where-Object {
    $_.gradeLevel -eq "K11" -and $_.versionNumber -eq 1
} | Select-Object -First 1
if ($null -eq $source) { throw "K11 v1 was not found" }

$target = $plans | Where-Object {
    $_.gradeLevel -eq "K11" -and $_.versionNumber -eq 2
} | Select-Object -First 1
if ($null -eq $target) {
    $target = Invoke-Json Post "/academic/training-plans/$($source.id)/versions" $headers @{
        name = "Ke hoach K11 - Bo sung giua ky"
    }
}

if ($target.status -in @("PUBLISHED", "LOCKED")) {
    Write-Host "[OK] K11 v2 is already published; running verification only"
} elseif ($target.status -notin @("DRAFT", "REVISION_REQUIRED", "SUBMITTED", "APPROVED")) {
    throw "K11 v2 has unsupported status $($target.status)"
}

if ($target.status -in @("DRAFT", "REVISION_REQUIRED")) {
    $initialized = Invoke-Json Post "/academic/training-plans/$($target.id)/initialize-from-program" $headers @{}
    Write-Host "[OK] Added $($initialized.assessmentsCreated) missing assessment plans"

    $rows = As-Array (Invoke-Json Get "/academic/training-plans/$($target.id)/subjects" $headers)
    $assessments = As-Array (Invoke-Json Get "/academic/training-plans/$($target.id)/assessments" $headers)

    foreach ($row in ($rows | Where-Object { $_.examRequired })) {
        $midterm = $assessments | Where-Object {
            $_.semesterId -eq $row.semesterId -and
            $_.subjectId -eq $row.subjectId -and
            $_.assessmentType -eq "MIDTERM"
        } | Select-Object -First 1
        $final = $assessments | Where-Object {
            $_.semesterId -eq $row.semesterId -and
            $_.subjectId -eq $row.subjectId -and
            $_.assessmentType -eq "FINAL"
        } | Select-Object -First 1
        if ($null -eq $midterm -or $null -eq $final) {
            throw "Missing assessment pair for $($row.semesterId) / $($row.subjectId)"
        }

        $curriculum = As-Array (Invoke-Json Get (
            "/academic/training-plans/$($target.id)/subjects/$($row.id)/curriculum"
        ) $headers)
        $lessons = @($curriculum | Where-Object { $_.itemType -eq "LESSON" } |
            Sort-Object sequence)
        $distributions = As-Array (Invoke-Json Get (
            "/academic/training-plans/$($target.id)/subjects/$($row.id)/distributions"
        ) $headers)

        if ($lessons.Count -eq 1) {
            $before = @($distributions | Where-Object { $_.weekNumber -lt $midterm.weekNumber })
            $after = @($distributions | Where-Object { $_.weekNumber -ge $midterm.weekNumber })
            $beforePeriods = ($before | Measure-Object periods -Sum).Sum
            $afterPeriods = ($after | Measure-Object periods -Sum).Sum
            if ($beforePeriods -le 0 -or $afterPeriods -le 0) {
                throw "Cannot split curriculum around midterm for $($row.subjectId)"
            }

            $first = $lessons[0]
            $first = Invoke-Json Put "/academic/training-plans/$($target.id)/curriculum/$($first.id)" $headers @{
                id = $first.id
                parentId = $first.parentId
                itemType = $first.itemType
                code = "L1"
                title = "Noi dung truoc giua ky"
                sequence = $first.sequence
                plannedPeriods = [int]$beforePeriods
                description = "Pham vi kien thuc hoan thanh truoc kiem tra giua ky"
            }
            $second = Invoke-Json Post (
                "/academic/training-plans/$($target.id)/subjects/$($row.id)/curriculum"
            ) $headers @{
                parentId = $first.parentId
                itemType = "LESSON"
                code = "L2"
                title = "Noi dung sau giua ky"
                sequence = $first.sequence + 1
                plannedPeriods = [int]$afterPeriods
                description = "Pham vi kien thuc con lai cua hoc ky"
            }

            foreach ($distribution in $after) {
                Invoke-Json Put (
                    "/academic/training-plans/$($target.id)/distributions/$($distribution.id)"
                ) $headers @{
                    id = $distribution.id
                    curriculumItemId = $second.id
                    weekNumber = $distribution.weekNumber
                    contentType = $distribution.contentType
                    title = $distribution.title
                    periods = $distribution.periods
                    notes = $distribution.notes
                } | Out-Null
            }
            $lessons = @($first, $second)
        }

        $firstLesson = $lessons | Where-Object { $_.code -eq "L1" } | Select-Object -First 1
        if ($null -eq $firstLesson) { $firstLesson = $lessons[0] }
        $lessonIds = @($lessons | Select-Object -ExpandProperty id)
        Invoke-Json Put (
            "/academic/training-plans/$($target.id)/assessments/$($midterm.id)"
        ) $headers (Assessment-Body $midterm @($firstLesson.id)) | Out-Null
        Invoke-Json Put (
            "/academic/training-plans/$($target.id)/assessments/$($final.id)"
        ) $headers (Assessment-Body $final $lessonIds) | Out-Null
    }
    Write-Host "[OK] Assessment content is linked to the correct teaching period"
}

$validation = Invoke-Json Get "/academic/training-plans/$($target.id)/validation" $headers
if ($validation.errorCount -ne 0) {
    throw "K11 v2 still has $($validation.errorCount) mandatory errors"
}
Write-Host "[OK] K11 v2 validation: 0 errors, $($validation.warningCount) warnings"

$target = (As-Array (Invoke-Json Get "/academic/training-plans" $headers) |
    Where-Object { $_.id -eq $target.id } | Select-Object -First 1)
if ($target.status -in @("DRAFT", "REVISION_REQUIRED")) {
    $target = Invoke-Json Post "/academic/training-plans/$($target.id)/submit" $headers @{
        comment = "Bo sung day du ke hoach giua ky va lien ket noi dung danh gia"
    }
}
if ($target.status -eq "SUBMITTED" -and $null -eq $target.reviewedAt) {
    $target = Invoke-Json Post "/academic/training-plans/$($target.id)/review" $headers @{
        comment = "Da ra soat du 24 ke hoach giua ky cua Khoi 11"
    }
}
if ($target.status -eq "SUBMITTED") {
    $target = Invoke-Json Post "/academic/training-plans/$($target.id)/approve" $headers @{
        comment = "Phe duyet phien ban dieu chinh Khoi 11"
    }
}
if ($target.status -eq "APPROVED") {
    $target = Invoke-Json Post "/academic/training-plans/$($target.id)/publish" $headers @{}
}

$plans = As-Array (Invoke-Json Get "/academic/training-plans" $headers)
$sourceAfter = $plans | Where-Object { $_.id -eq $source.id } | Select-Object -First 1
$targetAfter = $plans | Where-Object { $_.id -eq $target.id } | Select-Object -First 1
$assessmentsAfter = As-Array (Invoke-Json Get (
    "/academic/training-plans/$($target.id)/assessments"
) $headers)
$validationAfter = Invoke-Json Get (
    "/academic/training-plans/$($target.id)/validation"
) $headers

[pscustomobject]@{
    sourcePlanId = $sourceAfter.id
    sourceVersion = $sourceAfter.versionNumber
    sourceStatus = $sourceAfter.status
    targetPlanId = $targetAfter.id
    targetVersion = $targetAfter.versionNumber
    targetStatus = $targetAfter.status
    hk1Midterms = @($assessmentsAfter | Where-Object {
        $_.semesterId -eq "sm-2027-1" -and $_.assessmentType -eq "MIDTERM"
    }).Count
    hk2Midterms = @($assessmentsAfter | Where-Object {
        $_.semesterId -eq "sm-2027-2" -and $_.assessmentType -eq "MIDTERM"
    }).Count
    mandatoryErrors = $validationAfter.errorCount
    warnings = $validationAfter.warningCount
} | Format-List

