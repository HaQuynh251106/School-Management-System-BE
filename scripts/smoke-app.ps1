param(
    [string]$BaseUrl = "http://127.0.0.1:4000",
    [string]$PaymentSandboxSecret = "dev-payment-secret-change-me"
)

$ErrorActionPreference = "Stop"

function Invoke-Json {
    param(
        [ValidateSet("GET", "POST", "PUT", "DELETE")]
        [string]$Method,
        [string]$Path,
        [object]$Body = $null,
        [string]$Token = $null,
        [int[]]$Expected = @(200)
    )

    $headers = @{}
    if ($Token) { $headers["Authorization"] = "Bearer $Token" }
    $params = @{
        Method = $Method
        Uri = "$BaseUrl$Path"
        ContentType = "application/json"
        UseBasicParsing = $true
    }
    if ($headers.Count -gt 0) { $params.Headers = $headers }
    if ($null -ne $Body) { $params.Body = ($Body | ConvertTo-Json -Depth 10) }

    try {
        $response = Invoke-WebRequest @params
        if ($Expected -notcontains [int]$response.StatusCode) {
            throw "Expected HTTP $($Expected -join '/') for $Method $Path, got $($response.StatusCode)"
        }
        if ([string]::IsNullOrWhiteSpace($response.Content)) { return $null }
        return $response.Content | ConvertFrom-Json
    } catch {
        $status = $_.Exception.Response.StatusCode.value__
        if ($status -and ($Expected -contains [int]$status)) { return $null }
        throw
    }
}

function Login {
    param([string]$Username, [string]$Password)
    $body = @{ username = $Username; password = $Password }
    $res = Invoke-Json POST "/auth/login" $body
    if (-not $res.accessToken -or -not $res.refreshToken) {
        throw "Login did not return both tokens for $Username"
    }
    return $res
}

function As-Array {
    param([object]$Value)
    if ($null -eq $Value) { return @() }
    $props = @($Value.PSObject.Properties.Name)
    if (($props -contains "value") -and ($props -contains "Count")) {
        return @($Value.value)
    }
    return @($Value)
}

function New-HmacSignature {
    param(
        [System.Collections.IDictionary]$Payload,
        [string]$Secret
    )
    $canonical = (($Payload.GetEnumerator() | Sort-Object Key | ForEach-Object {
        "$($_.Key)=$($_.Value)"
    }) -join "&")
    $key = [System.Text.Encoding]::UTF8.GetBytes($Secret)
    $hmac = [System.Security.Cryptography.HMACSHA256]::new($key)
    try {
        $hash = $hmac.ComputeHash([System.Text.Encoding]::UTF8.GetBytes($canonical))
        return ([System.BitConverter]::ToString($hash)).Replace("-", "").ToLowerInvariant()
    } finally {
        $hmac.Dispose()
    }
}

function Wait-NotificationCount {
    param(
        [string]$Token,
        [int]$MinCount,
        [int]$TimeoutSeconds = 25
    )
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    do {
        $items = As-Array (Invoke-Json GET "/notifications" $null $Token)
        if ($items.Count -ge $MinCount) { return $items }
        Start-Sleep -Milliseconds 500
    } while ((Get-Date) -lt $deadline)
    throw "Expected at least $MinCount notifications after async worker processing"
}

Write-Host "SSE smoke against $BaseUrl"

$admin = Login "admin" "admin@123"
$teacher1 = Login "gv.toan" "teacher@123"
$teacher2 = Login "gv.van" "teacher@123"
$student = Login "hs.minh" "student@123"
$parent = Login "ph.nguyen" "parent@123"
Write-Host "[OK] login for 4 roles"

$refreshed = Invoke-Json POST "/auth/refresh" @{ refreshToken = $admin.refreshToken }
if (-not $refreshed.accessToken -or -not $refreshed.refreshToken) { throw "Refresh did not rotate tokens" }
Invoke-Json POST "/auth/refresh" @{ refreshToken = $admin.refreshToken } $null @(401) | Out-Null
Write-Host "[OK] refresh token rotation rejects old token"

$users = Invoke-Json GET "/users" $null $refreshed.accessToken
if ($users.Count -lt 4) { throw "Expected seeded users" }
Write-Host "[OK] admin/teacher can list users"

Invoke-Json GET "/invoices" $null $teacher1.accessToken @(403) | Out-Null
$financeInvoices = As-Array (Invoke-Json GET "/invoices" $null $refreshed.accessToken)
if ($financeInvoices.Count -lt 1) { throw "Expected finance invoices" }
$financeProbeInvoice = $financeInvoices | Select-Object -First 1
Invoke-Json GET "/payments?invoiceId=$($financeProbeInvoice.id)" $null $null @(401) | Out-Null
Invoke-Json GET "/payments?invoiceId=$($financeProbeInvoice.id)" $null $teacher1.accessToken @(403) | Out-Null
Invoke-Json POST "/fee-periods" @{ code = "hk1-2025"; name = "Duplicate smoke period" } $refreshed.accessToken @(409) | Out-Null
Invoke-Json POST "/fee-periods/fp-hk1/items" @{ name = "Invalid amount"; amount = -1 } $refreshed.accessToken @(400) | Out-Null
$financePeriodsForP0 = As-Array (Invoke-Json GET "/fee-periods" $null $refreshed.accessToken)
$idempotencyPeriod = $financePeriodsForP0 | Where-Object { $_.status -eq "PUBLISHED" } | Select-Object -First 1
if (-not $idempotencyPeriod) {
    $idempotencyPeriod = $financePeriodsForP0 | Where-Object { $_.status -eq "OPEN" } | Select-Object -First 1
}
if ($idempotencyPeriod) {
    $backfilledInvoices = As-Array (Invoke-Json POST "/fee-periods/$($idempotencyPeriod.id)/generate-invoices" $null $refreshed.accessToken)
    $regeneratedInvoices = As-Array (Invoke-Json POST "/fee-periods/$($idempotencyPeriod.id)/generate-invoices" $null $refreshed.accessToken)
    if ($regeneratedInvoices.Count -ne 0) { throw "Second invoice generation created duplicates" }
} else {
    Write-Host "[SKIP] finance generation idempotency: no OPEN/PUBLISHED fee period"
}
$parentFinanceInvoices = As-Array (Invoke-Json GET "/invoices" $null $parent.accessToken)
$studentFinanceInvoices = As-Array (Invoke-Json GET "/invoices" $null $student.accessToken)
$parentInvoiceIds = @($parentFinanceInvoices | ForEach-Object { $_.id })
$studentInvoiceIds = @($studentFinanceInvoices | ForEach-Object { $_.id })
$foreignParentInvoice = $financeInvoices | Where-Object { $parentInvoiceIds -notcontains $_.id } | Select-Object -First 1
$foreignStudentInvoice = $financeInvoices | Where-Object { $studentInvoiceIds -notcontains $_.id } | Select-Object -First 1
if ($parentFinanceInvoices.Count -gt 0) {
    Invoke-Json GET "/payments?invoiceId=$($parentFinanceInvoices[0].id)" $null $parent.accessToken | Out-Null
}
if ($studentFinanceInvoices.Count -gt 0) {
    Invoke-Json GET "/payments?invoiceId=$($studentFinanceInvoices[0].id)" $null $student.accessToken | Out-Null
}
if ($foreignParentInvoice) {
    Invoke-Json GET "/invoices/$($foreignParentInvoice.id)" $null $parent.accessToken @(403) | Out-Null
    Invoke-Json GET "/payments?invoiceId=$($foreignParentInvoice.id)" $null $parent.accessToken @(403) | Out-Null
}
if ($foreignStudentInvoice) {
    Invoke-Json GET "/invoices/$($foreignStudentInvoice.id)" $null $student.accessToken @(403) | Out-Null
    Invoke-Json GET "/payments?invoiceId=$($foreignStudentInvoice.id)" $null $student.accessToken @(403) | Out-Null
}
$unpaidParentInvoice = $parentFinanceInvoices | Where-Object { $_.status -ne "PAID" } | Select-Object -First 1
if ($unpaidParentInvoice) {
    Invoke-Json POST "/payments" @{ invoiceId = $unpaidParentInvoice.id; method = "CASH" } $parent.accessToken @(403) | Out-Null
}
Write-Host "[OK] finance P0 idempotency, validation and ownership access control"

$financePeriods = As-Array (Invoke-Json GET "/fee-periods" $null $refreshed.accessToken)
$p1Period = $financePeriods | Where-Object { $_.id -eq "fp-p1-smoke" } | Select-Object -First 1
if (-not $p1Period) {
    $p1Period = Invoke-Json POST "/fee-periods" @{
        id = "fp-p1-smoke"
        code = "P1-SMOKE"
        name = "Finance P1 smoke fixture"
        targetType = "CLASS"
        targetIds = @("c-11a1")
        dueDate = "2027-05-31"
    } $refreshed.accessToken
}
if ($p1Period.status -ne "DRAFT") {
    Write-Host "[SKIP] finance P1 fixture is no longer DRAFT; existing user data was left untouched"
} else {
$p1Items = As-Array (Invoke-Json GET "/fee-periods/fp-p1-smoke/items" $null $refreshed.accessToken)
if (-not ($p1Items | Where-Object { $_.id -eq "fpi-p1-common" })) {
    Invoke-Json POST "/fee-periods/fp-p1-smoke/items" @{
        id = "fpi-p1-common"
        name = "P1 common fee"
        amount = 100000
        targetType = "ALL"
        targetIds = @()
    } $refreshed.accessToken | Out-Null
}
if (-not ($p1Items | Where-Object { $_.id -eq "fpi-p1-private" })) {
    Invoke-Json POST "/fee-periods/fp-p1-smoke/items" @{
        id = "fpi-p1-private"
        name = "P1 private fee"
        amount = 50000
        targetType = "STUDENT"
        targetIds = @("u-s-minh")
    } $refreshed.accessToken | Out-Null
}
Invoke-Json POST "/fee-periods/fp-p1-smoke/items" @{
    name = "Invalid class-scoped item"
    amount = 1000
    targetType = "CLASS"
    targetIds = @("c-11a1")
} $refreshed.accessToken @(400) | Out-Null
$deleteProbe = As-Array (Invoke-Json GET "/fee-periods/fp-p1-smoke/items" $null $refreshed.accessToken) |
    Where-Object { $_.id -eq "fpi-p1-delete-probe" } | Select-Object -First 1
if ($deleteProbe) {
    Invoke-Json DELETE "/fee-periods/fp-p1-smoke/items/fpi-p1-delete-probe" $null $refreshed.accessToken | Out-Null
}
Invoke-Json POST "/fee-periods/fp-p1-smoke/items" @{
    id = "fpi-p1-delete-probe"
    name = "Delete probe"
    amount = 1000
    targetType = "ALL"
    targetIds = @()
} $refreshed.accessToken | Out-Null
Invoke-Json DELETE "/fee-periods/fp-p1-smoke/items/fpi-p1-delete-probe" $null $refreshed.accessToken | Out-Null
Invoke-Json GET "/fee-periods/fp-p1-smoke/preview" $null $teacher1.accessToken @(403) | Out-Null
$p1Preview = Invoke-Json GET "/fee-periods/fp-p1-smoke/preview" $null $refreshed.accessToken
if ($p1Preview.targetedStudentCount -lt 1) { throw "P1 class target did not select students" }
if ($p1Preview.billableStudentCount -ne $p1Preview.targetedStudentCount) { throw "P1 common fee did not cover the full target" }
if ($p1Preview.newInvoiceCount -ne $p1Preview.targetedStudentCount) { throw "P1 preview invoice count is incorrect" }
$privateStudentIncluded = @($p1Preview.students | Where-Object { $_.studentId -eq "u-s-minh" }).Count -gt 0
$expectedP1Total = ([long]$p1Preview.targetedStudentCount * 100000) + $(if ($privateStudentIncluded) { 50000 } else { 0 })
if ([long]$p1Preview.newTotalAmount -ne $expectedP1Total) { throw "P1 common/private fee total is incorrect" }
Write-Host "[OK] finance P1 targeting, common/private fees, draft deletion and preview"
}

Invoke-Json POST "/academic/high-school-defaults/ensure" $null $refreshed.accessToken | Out-Null
$teacherAssignments = As-Array (Invoke-Json GET "/me/teacher-class-subjects" $null $teacher1.accessToken)
if ($teacherAssignments.Count -lt 1) { throw "Expected teacher assignments for teacher1" }
$oldSmokeAssignments = As-Array (Invoke-Json GET "/teacher-class-subjects?classId=c-10a1&subjectId=sj-math&semesterId=sm-2026-2" $null $refreshed.accessToken)
foreach ($a in $oldSmokeAssignments) {
    if ($a.id) { Invoke-Json DELETE "/teacher-class-subjects/$($a.id)" $null $refreshed.accessToken | Out-Null }
}
$smokeAssignment = Invoke-Json POST "/teacher-class-subjects" @{
    teacherId = "u-t-math"
    classId = "c-10a1"
    subjectId = "sj-math"
    semesterId = "sm-2026-2"
    weeklyPeriods = 2
} $refreshed.accessToken
Invoke-Json POST "/teacher-class-subjects" @{
    teacherId = "u-t-math"
    classId = "c-10a1"
    subjectId = "sj-math"
    semesterId = "sm-2026-2"
} $refreshed.accessToken @(409) | Out-Null
$teacher1AssignmentsAfter = As-Array (Invoke-Json GET "/me/teacher-class-subjects" $null $teacher1.accessToken)
if (-not (@($teacher1AssignmentsAfter | Where-Object { $_.id -eq $smokeAssignment.id }).Count)) {
    throw "Teacher1 cannot see smoke assignment"
}
Invoke-Json DELETE "/teacher-class-subjects/$($smokeAssignment.id)" $null $refreshed.accessToken | Out-Null
Write-Host "[OK] teacher-class-subject assignments"

try {
    Invoke-Json POST "/users/u-t-lit/lock" $null $refreshed.accessToken | Out-Null
    Invoke-Json POST "/auth/login" @{ username = "gv.van"; password = "teacher@123" } $null @(403) | Out-Null
    Write-Host "[OK] locked user cannot login"
} finally {
    Invoke-Json POST "/users/u-t-lit/unlock" $null $refreshed.accessToken | Out-Null
    # Locking invalidates every access/refresh session immediately in Identity P1.
    $teacher2 = Login "gv.van" "teacher@123"
}

$children = Invoke-Json GET "/me/children" $null $parent.accessToken
if ($children.Count -lt 1) { throw "Parent children list is empty" }
Invoke-Json GET "/grades?studentId=u-admin" $null $parent.accessToken @(403) | Out-Null
Write-Host "[OK] parent child access control"

$firstChild = (As-Array $children | Select-Object -First 1)
$studentSlots = As-Array (Invoke-Json GET "/students/u-s-minh/timetable" $null $student.accessToken)
if ($studentSlots.Count -lt 1) { throw "Expected student timetable slots" }
$studentWorkSlot = @($studentSlots | Where-Object {
    $_.teacherId -ne "u-t-lit" -and $_.subjectId -notin @("sj-flag", "sj-homeroom")
}) | Select-Object -First 1
if (-not $studentWorkSlot) { throw "Expected a timetable slot with an assigned teacher" }
$assignedTeacherUser = Invoke-Json GET "/users/$($studentWorkSlot.teacherId)" $null $refreshed.accessToken
$assignedTeacher = Invoke-Json POST "/auth/login" @{
    username = $assignedTeacherUser.username
    password = "teacher@123"
}
$childSlots = As-Array (Invoke-Json GET "/students/$($firstChild.id)/timetable" $null $parent.accessToken)
if ($childSlots.Count -lt 1) { throw "Expected child timetable slots for parent" }
Invoke-Json GET "/students/u-admin/timetable" $null $parent.accessToken @(403) | Out-Null
Invoke-Json GET "/timetableSlots?classId=c-10a1" $null $parent.accessToken @(403) | Out-Null
Write-Host "[OK] student/parent timetable access control"

$conflictSlot = @{
    classId = $studentWorkSlot.classId
    subjectId = $studentWorkSlot.subjectId
    teacherId = $studentWorkSlot.teacherId
    roomCode = $studentWorkSlot.roomCode
    dayOfWeek = $studentWorkSlot.dayOfWeek
    periodNo = $studentWorkSlot.periodNo
    startTime = $studentWorkSlot.startTime
    endTime = $studentWorkSlot.endTime
    semesterId = $studentWorkSlot.semesterId
}
Invoke-Json POST "/timetableSlots" $conflictSlot $refreshed.accessToken @(409) | Out-Null
Write-Host "[OK] timetable conflict returns 409"

$badGrade = @{
    subjectId = $studentWorkSlot.subjectId
    semesterId = $studentWorkSlot.semesterId
    category = "FINAL"
    reason = "smoke unauthorized"
    entries = @(@{ studentId = "u-s-minh"; score = 9.1; note = "should be forbidden" })
}
Invoke-Json POST "/grades/bulk" $badGrade $teacher2.accessToken @(403) | Out-Null
Write-Host "[OK] teacher cannot grade unassigned class/subject"

$goodGrade = @{
    subjectId = $studentWorkSlot.subjectId
    semesterId = $studentWorkSlot.semesterId
    category = "FINAL"
    reason = "smoke authorized"
    entries = @(@{ studentId = "u-s-minh"; score = 8.8; note = "smoke" })
}
Invoke-Json POST "/grades/bulk" $goodGrade $assignedTeacher.accessToken | Out-Null
Write-Host "[OK] assigned teacher can upsert grade"

$assignmentSubmissions = As-Array (Invoke-Json GET "/assignments/asg-11a1-math/submissions" $null $teacher1.accessToken)
$gradedSubmission = @($assignmentSubmissions | Where-Object { $_.status -eq "GRADED" }) | Select-Object -First 1
if (-not $gradedSubmission) { throw "Expected a graded assignment submission" }
$oldAssignmentScore = [double]$gradedSubmission.score
$newAssignmentScore = if ($oldAssignmentScore -lt 9.9) { [Math]::Round($oldAssignmentScore + 0.1, 1) } else { [Math]::Round($oldAssignmentScore - 0.1, 1) }
Invoke-Json POST "/submissions/$($gradedSubmission.id)/grade" @{
    score = $newAssignmentScore
    feedback = $gradedSubmission.feedback
} $teacher1.accessToken @(400) | Out-Null
$assignmentCorrectionReason = "smoke assignment grade correction"
Invoke-Json POST "/submissions/$($gradedSubmission.id)/grade" @{
    score = $newAssignmentScore
    feedback = $gradedSubmission.feedback
    reason = $assignmentCorrectionReason
} $teacher1.accessToken | Out-Null
$assignmentAudit = As-Array (Invoke-Json GET "/audit-logs?module=academic" $null $refreshed.accessToken)
$assignmentAuditEntry = @($assignmentAudit | Where-Object {
    $_.action -eq "UPDATE" -and $_.entityType -eq "assignment_submission" -and
    $_.entityId -eq $gradedSubmission.id -and $_.detail -like "*$assignmentCorrectionReason*"
}) | Select-Object -First 1
if (-not $assignmentAuditEntry) { throw "Expected assignment grade correction audit entry" }
Invoke-Json POST "/submissions/$($gradedSubmission.id)/grade" @{
    score = $oldAssignmentScore
    feedback = $gradedSubmission.feedback
    reason = "restore after smoke test"
} $teacher1.accessToken | Out-Null
Write-Host "[OK] assignment grade correction requires reason and is audited"

$parentNotificationsBeforeAttendance = (As-Array (Invoke-Json GET "/notifications" $null $parent.accessToken)).Count
$attendance = @{
    slotId = $studentWorkSlot.id
    date = (Get-Date -Format "yyyy-MM-dd")
    marks = @(@{ studentId = "u-s-minh"; status = "ABSENT_UNEXCUSED"; note = "smoke" })
}
Invoke-Json POST "/attendance/bulk" $attendance $assignedTeacher.accessToken | Out-Null
Write-Host "[OK] attendance bulk mark"
Wait-NotificationCount $parent.accessToken ($parentNotificationsBeforeAttendance + 1) | Out-Null
Write-Host "[OK] async notification delivered attendance event"

$invoices = As-Array (Invoke-Json GET "/invoices" $null $parent.accessToken)
$pending = @($invoices | Where-Object { @("PENDING", "OVERDUE", "PARTIAL") -contains $_.status }) | Select-Object -First 1
if ($pending) {
    $paidAmountBefore = [long]$pending.paidAmount
    $initiated = Invoke-Json POST "/payments" @{ invoiceId = $pending.id; method = "VNPAY" } $parent.accessToken
    if ($initiated.payment.status -ne "PENDING") { throw "New payment was not PENDING" }
    if ([long]$initiated.invoice.paidAmount -ne $paidAmountBefore) { throw "Payment creation changed invoice amount" }

    $browserReturn = Invoke-Json GET "/payments/vnpay/return?paymentId=$($initiated.payment.id)"
    if ($browserReturn.status -ne "PENDING") { throw "Browser return changed payment status" }

    $callback = [ordered]@{
        provider = "VNPAY"
        txnRef = [string]$initiated.payment.txnRef
        amount = [string]$initiated.payment.amount
        status = "FAILED"
        providerTransactionId = "SMOKE-$([guid]::NewGuid().ToString('N'))"
        responseCode = "24"
    }
    $invalidCallback = [ordered]@{}
    foreach ($entry in $callback.GetEnumerator()) { $invalidCallback[$entry.Key] = $entry.Value }
    $invalidCallback["signature"] = "invalid"
    $invalidResult = Invoke-Json POST "/payments/vnpay/ipn" $invalidCallback
    if ($invalidResult.accepted -or $invalidResult.processed) { throw "Invalid signature was accepted" }
    $stillPending = Invoke-Json GET "/payments/$($initiated.payment.id)" $null $parent.accessToken
    if ($stillPending.status -ne "PENDING") { throw "Invalid signature changed payment status" }

    $callback["signature"] = New-HmacSignature $callback $PaymentSandboxSecret
    $failedResult = Invoke-Json POST "/payments/vnpay/ipn" $callback
    if (-not $failedResult.accepted -or -not $failedResult.processed -or $failedResult.paymentStatus -ne "FAILED") {
        throw "Signed gateway failure was not processed"
    }
    $replayed = Invoke-Json POST "/payments/vnpay/ipn" $callback
    if (-not $replayed.accepted -or $replayed.processed -or $replayed.callbackCount -ne 3) {
        throw "Repeated callback was not idempotent"
    }
    $invoiceAfterCallback = Invoke-Json GET "/invoices/$($pending.id)" $null $parent.accessToken
    if ([long]$invoiceAfterCallback.invoice.paidAmount -ne $paidAmountBefore) {
        throw "Failed callback changed invoice amount"
    }
    Invoke-Json GET "/payments/$($initiated.payment.id)/gateway-transactions" $null $parent.accessToken @(403) | Out-Null
    $gatewayLogs = @(As-Array (Invoke-Json GET "/payments/$($initiated.payment.id)/gateway-transactions" $null $refreshed.accessToken))
    if ($gatewayLogs.Count -ne 1 -or $gatewayLogs[0].callbackCount -ne 3) {
        throw "Gateway callback log did not retain callback count"
    }
    Write-Host "[OK] finance P2 pending intent, read-only return, signed IPN and callback idempotency"
} else {
    Write-Host "[SKIP] finance P2 payment: no unpaid seeded invoice"
}

$notis = As-Array (Invoke-Json GET "/notifications" $null $parent.accessToken)
if ($notis.Count -lt 1) { throw "Expected parent notifications after smoke events" }
Write-Host "[OK] notification inbox has events"

Write-Host "SSE smoke completed successfully."
