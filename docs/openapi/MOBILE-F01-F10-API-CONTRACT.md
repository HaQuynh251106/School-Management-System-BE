# Mobile F01–F10 API Contract

Status: locked for Web and Mobile. Do not create aliases for the endpoints below.

Base URL for local development: `http://localhost:4000`.

## Data provenance rule

- Mobile F01–F10 must render persisted backend responses; local mock lists, fake counters and fallback demo results are forbidden.
- Loading, empty and error are explicit UI states. An API error must never be replaced with plausible-looking sample data.
- Client-side preview is allowed only when the flow explicitly defines a deterministic preview from API-loaded inputs; commit still uses the canonical backend resource.
- Web and Mobile share the endpoint and persisted entity. Platform-specific aliases or shadow storage are forbidden.

## Contract registry

| Flow | Canonical API | Writer | Notes |
|---|---|---|---|
| F01 | `POST /auth/login`, `/auth/refresh`, `/auth/logout`, `/auth/forgot-password`, `/auth/reset-password`; `POST /users/{id}/reset-password` | Identity | Admin reset resolves `authType` on BE. Never return a reusable password. |
| F02 | `/academicYears`, `/semesters`, `/classes`, `/subjects`, `/rooms`; `PUT /classes/{id}/homeroom-teacher`; `/intake-class-placement/{candidates,preview,apply}` | Academic | One enrollment per student/year; preview before apply. |
| F03 | `GET /users/import-template`; `POST /users/import/preview`; `POST /users/import/commit` | Identity | Commit requires preview token and the same file checksum. |
| F04 | `GET /curriculum-requirements?semesterId=`; `PUT /curriculum-requirements`; `DELETE /curriculum-requirements/{id}` | Academic | This is the single curriculum-plan resource for this release. |
| F05 | `POST /timetableSlots/auto-plan`; `GET/POST /timetable-versions`; `POST /timetable-versions/{id}/publish` | Academic | Always call preview (`apply=false`) before apply. |
| F06 | `GET /teaching-progress`; `PUT /teaching-progress/{id}/makeup` | Academic | Exception/makeup is part of teaching progress, not a second resource. |
| F07 | `GET /teaching-progress`; `PUT /teaching-progress` | Teacher | Teacher may only update an assigned timetable slot. |
| F08 | `/exam-periods`; `/exam-periods/{id}/schedules`; `/exam-schedules/{id}/rooms`; `/exam-rooms/{id}/allocate`; `/exam-schedules/{id}/eligible-graders`; `PUT /exam-schedules/{id}/graders`; `POST /exam-periods/{id}/publish-schedule` | Academic | Mobile proposal uses these existing exam resources; no duplicate `auto-exam` API. |
| F09 | `GET /attendance`; `GET /attendance/session-status`; `POST /attendance/unlock`; `POST /attendance/bulk` | Teacher | BE verifies slot ownership, timetable date/time and late-unlock reason; bulk upserts by slot/date/student. |
| F10 | `GET /me/gradebook-context`; `GET /grades`; `POST /grades/bulk`; `GET /grades/{id}/change-logs` | Teacher | Existing-score changes require a reason; Web and Mobile read the same audit log. |

## User creation, system-owned codes, and teacher subject ownership

`POST /users` is the only endpoint used by Web and Mobile to create an individual account.

- `email` and `phone` are mandatory for every role. Clients must validate both before submit; backend remains authoritative.
- Clients must omit every user-code field. Backend allocates one immutable, unique `userCode` by role: `AD000001`, `GV000001`, `HS000001`, or `PH000001`.
- `teacherCode` and `studentCode` remain response aliases for compatibility. A client-supplied value is ignored and neither field may appear as an editable create/update input.
- The allocator serializes concurrent requests through a per-role database counter and `users.user_code` has a unique index.
- For `role=TEACHER`, `mainSubjectId` is mandatory and must reference an item returned by the existing `GET /subjects` endpoint. Backend resolves `mainSubject`; clients must not send arbitrary free text.
- The same rules apply to Excel import. Its template contains no user-code column; teacher rows use a subject code from the school's subject catalog.

## F01 reset response

`POST /users/{id}/reset-password` accepts no client-selected authentication type or password.

LOCAL response:

```json
{
  "ok": true,
  "authType": "LOCAL",
  "action": "RESET_LINK_SENT",
  "mustChangePassword": true,
  "message": "Link đặt lại mật khẩu một lần đã được gửi; mọi phiên cũ đã bị thu hồi."
}
```

SSO response:

```json
{
  "ok": true,
  "authType": "SSO",
  "action": "CONTACT_SSO_ADMIN",
  "mustChangePassword": false,
  "message": "Tài khoản do IdP quản lý; hãy gửi yêu cầu reset/invite tại hệ thống SSO."
}
```

In the demo profile only, `devResetToken` may be present for a LOCAL account. It is a one-time token, not a password. Reset revokes all existing refresh sessions in both branches.

## F03 import contract

1. Download `GET /users/import-template`.
2. Upload the `.xlsx` file as multipart field `file` to `POST /users/import/preview`.
3. Review `totalRows`, `validRows`, `invalidRows`, and every item in `rows`.
4. Commit the exact same file to `POST /users/import/commit?token={previewToken}&strategy=ALL_OR_NOTHING|SKIP_ERRORS`.

Do not call the legacy direct-import route from new clients.

## F04 curriculum requirement

`PUT /curriculum-requirements` upserts the unique tuple `semesterId + gradeLevel + subjectId`.

```json
{
  "semesterId": "sem-1",
  "gradeLevel": "10",
  "subjectId": "sub-math",
  "weeklyPeriods": 4,
  "totalPeriods": 72,
  "startDate": "2026-08-17",
  "endDate": "2026-12-20",
  "examWindowStart": "2026-12-21",
  "examWindowEnd": "2026-12-27",
  "milestone": "Complete semester topics before the exam window"
}
```

Dates must be inside the semester; `startDate <= endDate`; the exam window must be valid. Mobile computes and displays the cross-class actual-progress gap; a gap above two days is a warning requiring makeup review.

## F05 timetable generation

`POST /timetableSlots/auto-plan`:

```json
{ "semesterId": "sem-1", "apply": false, "allowPartial": false }
```

The response is a proposal with conflicts/warnings. Only after review may the same request use `apply=true`. Applied slots remain draft until a timetable version is created and published. `GET /me/timetable` returns only slots whose `publishedPlanId` is present, so Teacher/Student never see the Admin workspace draft.

Parent reads a selected child's published timetable through `GET /children/{studentId}/timetable`. This endpoint requires role `PARENT`, verifies the parent-child relation, and never returns draft slots. Mobile must not infer authorization from `classId` or call an Admin list endpoint to render this screen.

For each occurrence of the same subject in classes of the same grade, the planner keeps weekday distance at no more than two days. Candidate slots are ranked by peer-day gap, current class day load, same-subject day load, then stable slot order. If no candidate satisfies the threshold, the API returns `UNSCHEDULED` with the pacing blocker instead of silently creating an unbalanced timetable.

## F06/F07 teaching progress

Teacher upsert:

```json
{
  "timetableSlotId": "tt-1",
  "lessonDate": "2026-09-02",
  "completedPeriods": 1,
  "topic": "Linear equations",
  "status": "COMPLETED",
  "reason": null,
  "makeupDate": null
}
```

For `CANCELLED`, `completedPeriods` is saved as `0`, `reason` is required, and `makeupDate`—if supplied—must be after `lessonDate`.

Academic review:

```json
PUT /teaching-progress/{id}/makeup
{ "status": "APPROVED", "reviewNote": "Room A101 confirmed" }
```

Allowed review statuses are `APPROVED` and `REJECTED`. A reviewed source log cannot be silently rewritten.

Progress comparison is scoped by `gradeLevel + subjectId`. A group is balanced only when every active class has an actual-progress log, the latest lesson dates differ by at most two days, and completed periods differ by at most one. Missing or lagging classes must be shown with a concrete makeup suggestion.

## F08 exam scheduling rule

Mobile builds a reviewable draft from the selected fixed subjects and the period date window, then persists it through the existing schedule endpoints. It automatically assigns one adequate physical room per non-empty class, one conflict-free primary proctor, real candidates from that class, and one eligible subject grader. A second proctor is optional; when supplied, it must differ from the primary proctor. Academic Staff only adjusts exceptional date/room/proctor choices.

The server blocks publish until every schedule has classes, rooms, a primary proctor, candidates for every participating class, and an eligible grader for every class. Student/Parent and assigned teachers receive agenda items only after `POST /exam-periods/{id}/publish-schedule` succeeds. Grade comparison accepts both `10` and `K10` as the same grade; clients must not create a second grade-normalization API.

## F09 attendance session rule

Mobile must read `GET /attendance/session-status?slotId={id}&date=YYYY-MM-DD` before enabling save. A late session is unlocked only through `POST /attendance/unlock` with a reason of at least 10 characters. Every non-`PRESENT` mark carries a note; the server remains the authority for timetable ownership, holidays, future sessions and lock windows.

## F10 grade audit rule

Teacher first resolves editable subject scope through `/me/gradebook-context`, then writes through `/grades/bulk`. Updating an existing score requires `reason` and `expectedVersion`; clients must not create a separate publish or mobile-grade endpoint. The “Log sửa điểm” tab reads `/grades/{id}/change-logs` and never synthesizes audit entries locally.

## Ownership rule

- Web and Mobile are consumers of this contract; they must not invent a platform-specific API.
- Any new field is added to the canonical request/response first.
- Any mutation must preserve role checks, audit behavior, and idempotent/upsert rules already enforced by BE.
- A new endpoint is allowed only when no resource above can express the business operation.

## Cross-role linkage

| Producer | Persisted result | Consumer |
|---|---|---|
| Admin/Academic Staff F02–F03 | User, student, year, semester, class, enrollment, subject, room, GVCN | F04–F10 scope selectors and teacher/student relationships |
| Academic Staff F04 | Curriculum requirement and fixed date/exam window | F05 timetable planning, F07 progress comparison, F08 exam planning |
| Academic Staff F05 | Published timetable version and slots | Teacher timetable, F07 teaching progress and F09 attendance |
| Teacher F06–F07 | Actual lesson progress, cancellation and makeup request | Academic Staff progress balance and makeup review |
| Academic Staff F08 | Published exam period/schedule/room allocation | Assigned teachers and eligible student/parent exam views |
| Teacher F09 | Attendance records | Student/parent attendance history, notifications and Admin dashboard |
| Teacher F10 | Grades and change logs | Student/parent results, notifications, reports and Admin audit |
