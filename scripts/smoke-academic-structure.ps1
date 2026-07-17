param(
    [string]$BaseUrl = "http://127.0.0.1:4000",
    [string]$YearCode = "2026-2027"
)

$ErrorActionPreference = "Stop"

function Invoke-SseJson {
    param(
        [ValidateSet("GET", "POST", "PUT")]
        [string]$Method,
        [string]$Path,
        [object]$Body = $null,
        [string]$Token = $null,
        [int[]]$Expected = @(200)
    )

    $headers = @{}
    if ($Token) { $headers.Authorization = "Bearer $Token" }
    $params = @{ Method = $Method; Uri = "$BaseUrl$Path"; Headers = $headers; UseBasicParsing = $true }
    if ($null -ne $Body) {
        $params.ContentType = "application/json; charset=utf-8"
        $params.Body = [System.Text.Encoding]::UTF8.GetBytes(($Body | ConvertTo-Json -Depth 10 -Compress))
    }

    try {
        $response = Invoke-WebRequest @params
        if ($Expected -notcontains [int]$response.StatusCode) {
            throw "Expected HTTP $($Expected -join '/') for $Method $Path, got $($response.StatusCode)"
        }
        return $(if ($response.Content) { $response.Content | ConvertFrom-Json } else { $null })
    } catch {
        $status = $_.Exception.Response.StatusCode.value__
        if ($status -and ($Expected -contains [int]$status)) { return $null }
        throw
    }
}

if ($YearCode -notmatch '^(\d{4})-(\d{4})$' -or ([int]$Matches[2] -ne [int]$Matches[1] + 1)) {
    throw "YearCode must have the form YYYY-YYYY, for example 2026-2027"
}

$startYear = [int]$Matches[1]
$endYear = [int]$Matches[2]
$login = Invoke-SseJson POST "/auth/login" @{ username = "admin"; password = "admin@123" }
$token = $login.accessToken

$year = @(Invoke-SseJson GET "/academic-years" $null $token | Where-Object { $_.code -eq $YearCode } | Select-Object -First 1)
if ($year.Count -eq 0) {
    $year = @(Invoke-SseJson POST "/academic-years" @{
        code = $YearCode
        name = "School year $YearCode"
        startDate = "$startYear-09-05"
        endDate = "$endYear-05-31"
        status = "PLANNED"
    } $token)
}
$year = $year[0]

$semesterSpecs = @(
    @{ code = "HK1"; name = "Semester 1"; startDate = "$startYear-09-05"; endDate = "$endYear-01-15" },
    @{ code = "HK2"; name = "Semester 2"; startDate = "$endYear-01-20"; endDate = "$endYear-05-31" }
)
$existingSemesters = @(Invoke-SseJson GET "/semesters?academicYearId=$($year.id)" $null $token)
foreach ($semester in $semesterSpecs) {
    if (-not (@($existingSemesters | Where-Object { $_.code -eq $semester.code }).Count)) {
        Invoke-SseJson POST "/semesters" (@{
            academicYearId = $year.id
            code = $semester.code
            name = $semester.name
            startDate = $semester.startDate
            endDate = $semester.endDate
            status = "PLANNED"
        }) $token | Out-Null
    }
}

Invoke-SseJson POST "/academic/high-school-defaults/ensure?academicYearId=$($year.id)" $null $token | Out-Null
$classes = @(Invoke-SseJson GET "/classes?academicYearId=$($year.id)" $null $token)
if ($classes.Count -ne 30) { throw "Expected 30 classes for $YearCode, got $($classes.Count)" }

$teacher = @(Invoke-SseJson GET "/users?role=TEACHER" $null $token | Where-Object { $_.status -eq "ACTIVE" } | Select-Object -First 1)
if ($teacher.Count -ne 1) { throw "No active teacher is available for the GVCN check" }
$class10A1 = @($classes | Where-Object { $_.code -eq "10A1" } | Select-Object -First 1)[0]
Invoke-SseJson PUT "/classes/$($class10A1.id)/homeroom-teacher" @{ homeroomTeacherId = $teacher[0].id } $token | Out-Null

Invoke-SseJson POST "/classes" @{
    code = "10A1"; name = "Duplicate test"; gradeLevel = "K10"; academicYearId = $year.id
} $token @(409) | Out-Null

Write-Host "[OK] ${YearCode}: 2 semesters, 30 classes, and GVCN assignment are valid. Duplicate class returns 409."
