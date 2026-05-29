# academic-service

**Cổng:** 8082
**DB:** `academic_db` (PostgreSQL — DDL §5.2 của plan)
**Owner:** P2 + P3 (chia theo file, xem dưới)

## Cấu trúc package (chuẩn Spring Boot — flat)

```
com.sse.academic
├── AcademicServiceApplication.java
├── config/         # SecurityConfig, JpaConfig, RabbitConfig
├── controller/     # *Controller.java (REST endpoint)
├── service/        # *Service.java (business logic)
├── repository/     # *Repository.java extends JpaRepository
├── entity/         # @Entity classes
├── dto/
│   ├── request/    # *Request.java
│   └── response/   # *Response.java
├── mapper/         # MapStruct: *Mapper.java
├── event/
│   ├── publisher/  # *EventPublisher.java
│   └── listener/   # *EventListener.java
├── exception/
└── util/
```

Chỉ 1 package phẳng — KHÔNG tạo sub-package theo domain.

## Chia file giữa P2 và P3

Cả 2 cùng commit vào service này nhưng **mỗi file chỉ thuộc 1 người sở hữu** — không bao giờ sửa file của người kia.

### P2 sở hữu (prefix tên file)

| Loại | File |
|---|---|
| Entity | `AcademicYear`, `Semester`, `GradeLevel`, `Subject`, `Room`, `SchoolHoliday`, `Class`, `ClassEnrollment`, `TeacherClassSubject`, `TimetableSlot`, `AttendanceRecord` |
| Repository / Service / Controller / Mapper / DTO | tất cả file có prefix tương ứng với entity trên (ví dụ `ClassRepository`, `ClassService`, `ClassController`, `TimetableSlotService`, `AttendanceRecordController`, ...) |
| Event publisher | `TimetableEventPublisher`, `AttendanceEventPublisher` |

### P3 sở hữu (prefix tên file)

| Loại | File |
|---|---|
| Entity | `ExamCategory`, `SubjectScoreConfig`, `Grade`, `GradeChangeLog`, `Assignment`, `AssignmentAttachment`, `AssignmentSubmission`, `SubmissionAttachment`, `ExtracurricularCourse`, `ExtracurricularEnrollment`, `StudentYearlySummary` |
| Repository / Service / Controller / Mapper / DTO | tất cả file có prefix tương ứng (`GradeService`, `AssignmentController`, `ExtracurricularService`, `StudentYearlySummaryService`, ...) |
| Event publisher | `GradeEventPublisher`, `AssignmentEventPublisher`, `ExtracurricularEventPublisher`, `YearlySummaryEventPublisher` |

### Dùng chung (cần thống nhất khi sửa)

| File | Ai sửa |
|---|---|
| `AcademicServiceApplication.java` | P2 tạo, P3 không sửa |
| `config/*` | P2 tạo skeleton; cần sửa → cả 2 cùng approve |
| `exception/GlobalExceptionHandler.java` | P2 tạo; cần sửa → cùng approve |
| `pom.xml` của service | cần sửa → cùng approve |

## Flyway migration chia khoảng version

`src/main/resources/db/migration/`:
- **P2:** `V100__` → `V199__` (ví dụ `V100__create_academic_years.sql`, `V120__create_timetable_slots.sql`)
- **P3:** `V200__` → `V299__` (ví dụ `V200__create_exam_categories.sql`, `V230__create_assignments.sql`)
- **P1 (seed/fix):** `V900__` → `V999__`

## Event publish

P2:
- `academic.timetable.changed`
- `academic.attendance.absent`
- `academic.attendance.recorded`

P3:
- `academic.grade.published`, `.changed`
- `academic.assignment.published`
- `academic.submission.graded`
- `academic.extracurricular.enrolled`
- `academic.year.finalized`
