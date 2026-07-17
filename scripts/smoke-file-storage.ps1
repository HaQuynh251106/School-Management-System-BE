param(
    [string]$BaseUrl = "http://127.0.0.1:4000",
    [string]$TeacherUsername = "gv.toan",
    [string]$TeacherPassword = "teacher@123",
    [string]$StudentUsername = "hs.minh",
    [string]$StudentPassword = "student@123"
)

$ErrorActionPreference = "Stop"

function Login {
    param([string]$Username, [string]$Password)
    $body = @{ username = $Username; password = $Password } | ConvertTo-Json
    Invoke-RestMethod -Method Post -Uri "$BaseUrl/auth/login" -ContentType "application/json" -Body $body
}

function Expect-Status {
    param([scriptblock]$Action, [int]$Status)
    try {
        & $Action
        throw "Expected HTTP $Status"
    } catch {
        $actual = $_.Exception.Response.StatusCode.value__
        if ($actual -ne $Status) { throw }
    }
}

$workDir = Join-Path $env:TEMP "sse-file-storage-smoke"
$pdfPath = Join-Path $workDir "assignment-test.pdf"
$downloadPath = Join-Path $workDir "downloaded-assignment-test.pdf"
New-Item -ItemType Directory -Force -Path $workDir | Out-Null
[System.IO.File]::WriteAllText($pdfPath, "%PDF-1.4`n1 0 obj`n<< /Type /Catalog >>`nendobj`ntrailer`n<< /Root 1 0 R >>`n%%EOF`n", [System.Text.Encoding]::ASCII)

Write-Host "SSE file-storage smoke against $BaseUrl"
$teacher = Login $TeacherUsername $TeacherPassword
$student = Login $StudentUsername $StudentPassword
$teacherHeaders = @{ Authorization = "Bearer $($teacher.accessToken)" }
$studentHeaders = @{ Authorization = "Bearer $($student.accessToken)" }
$sizeBytes = (Get-Item -LiteralPath $pdfPath).Length

$presigned = Invoke-RestMethod -Method Post -Uri "$BaseUrl/files/presigned-upload" -Headers $teacherHeaders `
    -ContentType "application/json" -Body (@{
        scope = "ASSIGNMENT"
        fileName = "assignment-test.pdf"
        contentType = "application/pdf"
        sizeBytes = $sizeBytes
    } | ConvertTo-Json)
Write-Host "[OK] teacher received presigned upload URL"

$put = Invoke-WebRequest -Method Put -Uri $presigned.uploadUrl -InFile $pdfPath -ContentType "application/pdf" -UseBasicParsing
if ($put.StatusCode -ne 200) { throw "Presigned PUT did not return 200" }
$ready = Invoke-RestMethod -Method Post -Uri "$BaseUrl/files/$($presigned.id)/complete" -Headers $teacherHeaders -UseBasicParsing
if ($ready.status -ne "READY") { throw "Expected READY, got $($ready.status)" }
Write-Host "[OK] uploaded file was verified by MinIO"

$download = Invoke-RestMethod -Method Post -Uri "$BaseUrl/files/$($presigned.id)/presigned-download" -Headers $teacherHeaders -UseBasicParsing
Invoke-WebRequest -Uri $download.downloadUrl -OutFile $downloadPath -UseBasicParsing
if ((Get-Item -LiteralPath $downloadPath).Length -ne $sizeBytes) { throw "Downloaded bytes do not match upload" }
Write-Host "[OK] owner can download verified file"

Expect-Status { Invoke-WebRequest -Method Post -Uri "$BaseUrl/files/$($presigned.id)/presigned-download" -Headers $studentHeaders -UseBasicParsing } 403
Expect-Status { Invoke-WebRequest -Method Post -Uri "$BaseUrl/files/presigned-upload" -Headers $teacherHeaders -ContentType "application/json" `
    -Body (@{ scope = "ASSIGNMENT"; fileName = "bad.txt"; contentType = "text/plain"; sizeBytes = 12 } | ConvertTo-Json) -UseBasicParsing } 400
Expect-Status { Invoke-WebRequest -Method Post -Uri "$BaseUrl/files/presigned-upload" -Headers $teacherHeaders -ContentType "application/json" `
    -Body (@{ scope = "ASSIGNMENT"; fileName = "large.pdf"; contentType = "application/pdf"; sizeBytes = (5MB + 1) } | ConvertTo-Json) -UseBasicParsing } 400
Write-Host "[OK] cross-user download, invalid type and oversized file are rejected"

Write-Host "SSE file-storage smoke completed successfully."
