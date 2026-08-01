package com.sse.app.academic.structure;

import com.sse.app.common.ApiException;
import com.sse.app.common.Ids;
import com.sse.app.academic.structure.StructureDtos.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/** A2: Quản trị cơ cấu đào tạo. Là điểm truy cập chéo-domain cho academic.structure. */
@Service
public class StructureService {

    private final AcademicYearRepository years;
    private final SemesterRepository semesters;
    private final SchoolClassRepository classes;
    private final SubjectRepository subjects;
    private final RoomRepository rooms;
    private final ClassEnrollmentRepository enrollments;
    private final CohortRepository cohorts;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public StructureService(AcademicYearRepository years, SemesterRepository semesters,
                            SchoolClassRepository classes, SubjectRepository subjects,
                            RoomRepository rooms,
                            ClassEnrollmentRepository enrollments, CohortRepository cohorts, JdbcTemplate jdbc,
                            Clock clock) {
        this.years = years;
        this.semesters = semesters;
        this.classes = classes;
        this.subjects = subjects;
        this.rooms = rooms;
        this.enrollments = enrollments;
        this.cohorts = cohorts;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    // ---------- Năm học ----------
    public List<AcademicYear> listYears() {
        return years.findAll().stream()
                .sorted(Comparator.comparing(AcademicYear::getStartDate,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
    }

    public AcademicYear getYear(String id) {
        return years.findById(id).orElseThrow(() -> ApiException.notFound("Năm học"));
    }

    @Transactional
    public AcademicYear createYear(CreateAcademicYearRequest r) {
        return createYear(r, true);
    }

    @Transactional
    public AcademicYear createYear(CreateAcademicYearRequest r, boolean createDefaultSemesters) {
        String code = normalizeCode(r.code());
        if (years.findByCode(code).isPresent()) throw ApiException.conflict("Mã năm học đã tồn tại");
        validatePeriod(r.startDate(), r.endDate(), "năm học");
        if (createDefaultSemesters && ChronoUnit.DAYS.between(r.startDate(), r.endDate()) < 1) {
            throw ApiException.badRequest("Năm học phải có ít nhất hai ngày để tự động tạo hai học kỳ");
        }
        assertYearPeriodAvailable(null, r.startDate(), r.endDate());
        if (r.status() != null && !"PLANNED".equals(r.status())) {
            throw ApiException.badRequest("Năm học mới phải ở trạng thái dự kiến trước khi kích hoạt");
        }
        AcademicYear year = years.save(AcademicYear.builder()
                .id(orGen(r.id(), "ay")).code(code).name(defaultName(r.name(), code))
                .startDate(r.startDate()).endDate(r.endDate()).status("PLANNED").build());
        if (createDefaultSemesters) createDefaultSemesters(year);
        return year;
    }

    @Transactional
    public AcademicYear updateYear(String id, UpdateAcademicYearRequest r) {
        AcademicYear year = getYear(id);
        requireNotClosed(year.getStatus(), "Năm học đã đóng không thể chỉnh sửa");
        String code = normalizeCode(r.code());
        years.findByCode(code).filter(item -> !item.getId().equals(id))
                .ifPresent(item -> { throw ApiException.conflict("Mã năm học đã tồn tại"); });
        validatePeriod(r.startDate(), r.endDate(), "năm học");
        assertYearPeriodAvailable(id, r.startDate(), r.endDate());
        for (Semester semester : semesters.findByAcademicYearId(id)) {
            if (!within(semester.getStartDate(), semester.getEndDate(), r.startDate(), r.endDate())) {
                throw ApiException.badRequest("Thời gian năm học phải bao phủ toàn bộ các học kỳ hiện có");
            }
        }
        year.setCode(code);
        year.setName(defaultName(r.name(), code));
        year.setStartDate(r.startDate());
        year.setEndDate(r.endDate());
        return years.save(year);
    }

    @Transactional
    public AcademicYear changeYearStatus(String id, String requestedStatus) {
        AcademicYear year = getYear(id);
        String target = normalizeStatus(requestedStatus);
        if (target.equals(year.getStatus())) return year;
        if ("CLOSED".equals(year.getStatus())) {
            throw ApiException.badRequest("Năm học đã đóng không thể mở lại");
        }
        if ("ACTIVE".equals(target)) {
            if (!"PLANNED".equals(year.getStatus())) throw ApiException.badRequest("Chỉ năm học dự kiến mới có thể kích hoạt");
            if (year.getStartDate() != null && today().isBefore(year.getStartDate())) {
                throw ApiException.badRequest("Chưa đến ngày bắt đầu năm học " + year.getStartDate());
            }
            if (semesters.findByAcademicYearId(id).isEmpty()) {
                throw ApiException.badRequest("Cần tạo ít nhất một học kỳ trước khi kích hoạt năm học");
            }
            for (AcademicYear active : years.findByStatus("ACTIVE")) {
                if (!active.getId().equals(id)) {
                    throw ApiException.conflict("Năm học " + active.getCode()
                            + " vẫn đang hoạt động. Hãy hoàn tất quy trình chuyển năm học thay vì kích hoạt trực tiếp.");
                }
            }
            year.setStatus("ACTIVE");
        } else if ("CLOSED".equals(target)) {
            if (countStudentsWithoutFinalizedSummary(id) > 0) {
                throw ApiException.conflict("Năm học còn học sinh chưa được tổng kết. Hãy dùng chức năng Chuyển năm học để tránh mất bước xét lên lớp.");
            }
            year.setStatus("CLOSED");
            closeSemestersOfYear(id);
        } else {
            throw ApiException.badRequest("Không thể chuyển năm học về trạng thái dự kiến");
        }
        return years.save(year);
    }

    @Transactional
    public void deleteYear(String id) {
        AcademicYear year = getYear(id);
        requirePlanned(year.getStatus(), "Chỉ được xóa năm học đang ở trạng thái dự kiến");
        List<Semester> yearSemesters = semesters.findByAcademicYearId(id);
        boolean semesterInUse = yearSemesters.stream().anyMatch(semester ->
                !"PLANNED".equals(semester.getStatus())
                        || hasReference(semester.getId(), "grades", "semester_id")
                        || hasReference(semester.getId(), "timetable_slots", "semester_id")
                        || hasReference(semester.getId(), "teaching_assignments", "semester_id"));
        if (semesterInUse || !classes.findByAcademicYearId(id).isEmpty()
                || hasReference(id, "fee_periods", "academic_year_id")
                || hasReference(id, "class_enrollments", "academic_year_id")
                || hasReference(id, "student_yearly_summaries", "academic_year_id")) {
            throw ApiException.badRequest("Không thể xóa năm học đang có học kỳ, lớp hoặc dữ liệu nghiệp vụ");
        }
        semesters.deleteAll(yearSemesters);
        years.delete(year);
    }

    private void createDefaultSemesters(AcademicYear year) {
        long inclusiveDays = ChronoUnit.DAYS.between(year.getStartDate(), year.getEndDate()) + 1;
        LocalDate firstSemesterEnd = year.getStartDate().plusDays(inclusiveDays / 2 - 1);
        LocalDate secondSemesterStart = firstSemesterEnd.plusDays(1);
        semesters.saveAll(List.of(
                Semester.builder()
                        .id(Ids.gen("sm")).academicYearId(year.getId())
                        .code("HK1").name("Học kỳ 1").sequence(1)
                        .startDate(year.getStartDate()).endDate(firstSemesterEnd)
                        .status("PLANNED").build(),
                Semester.builder()
                        .id(Ids.gen("sm")).academicYearId(year.getId())
                        .code("HK2").name("Học kỳ 2").sequence(2)
                        .startDate(secondSemesterStart).endDate(year.getEndDate())
                        .status("PLANNED").build()
        ));
    }

    // ---------- Học kỳ ----------
    public List<Semester> listSemesters(String academicYearId) {
        List<Semester> result = academicYearId == null ? semesters.findAll() : semesters.findByAcademicYearId(academicYearId);
        return result.stream().sorted(Comparator.comparing(Semester::getAcademicYearId)
                .thenComparingInt(Semester::getSequence)).toList();
    }

    public Semester getSemester(String id) {
        return semesters.findById(id).orElseThrow(() -> ApiException.notFound("Học kỳ"));
    }

    @Transactional
    public Semester createSemester(CreateSemesterRequest r) {
        AcademicYear year = getYear(r.academicYearId());
        requireNotClosed(year.getStatus(), "Không thể thêm học kỳ vào năm học đã đóng");
        String code = normalizeCode(r.code());
        int sequence = r.sequence() == null ? 1 : r.sequence();
        validateSemester(null, year, code, sequence, r.startDate(), r.endDate());
        if (r.status() != null && !"PLANNED".equals(r.status())) {
            throw ApiException.badRequest("Học kỳ mới phải ở trạng thái dự kiến");
        }
        return semesters.save(Semester.builder()
                .id(orGen(r.id(), "sm")).academicYearId(year.getId()).code(code)
                .name(defaultName(r.name(), code)).sequence(sequence)
                .startDate(r.startDate()).endDate(r.endDate()).status("PLANNED").build());
    }

    @Transactional
    public Semester updateSemester(String id, UpdateSemesterRequest r) {
        Semester semester = getSemester(id);
        requirePlanned(semester.getStatus(), "Chỉ học kỳ dự kiến mới có thể chỉnh sửa");
        AcademicYear year = getYear(r.academicYearId());
        requireNotClosed(year.getStatus(), "Không thể chuyển học kỳ vào năm học đã đóng");
        String code = normalizeCode(r.code());
        int sequence = r.sequence() == null ? 1 : r.sequence();
        validateSemester(id, year, code, sequence, r.startDate(), r.endDate());
        semester.setAcademicYearId(year.getId());
        semester.setCode(code);
        semester.setName(defaultName(r.name(), code));
        semester.setSequence(sequence);
        semester.setStartDate(r.startDate());
        semester.setEndDate(r.endDate());
        return semesters.save(semester);
    }

    @Transactional
    public Semester changeSemesterStatus(String id, String requestedStatus) {
        Semester semester = getSemester(id);
        String target = normalizeStatus(requestedStatus);
        if (target.equals(semester.getStatus())) return semester;
        if ("CLOSED".equals(semester.getStatus())) throw ApiException.badRequest("Học kỳ đã đóng không thể mở lại");
        if ("ACTIVE".equals(target)) {
            AcademicYear year = getYear(semester.getAcademicYearId());
            if (semester.getStartDate() != null && today().isBefore(semester.getStartDate())) {
                throw ApiException.badRequest("Chưa đến ngày bắt đầu học kỳ " + semester.getStartDate());
            }
            if (!"ACTIVE".equals(year.getStatus())) {
                throw ApiException.badRequest("Phải kích hoạt năm học trước khi kích hoạt học kỳ");
            }
            for (Semester active : semesters.findByStatus("ACTIVE")) {
                if (!active.getId().equals(id)) {
                    active.setStatus("CLOSED");
                    semesters.save(active);
                }
            }
            semester.setStatus("ACTIVE");
        } else if ("CLOSED".equals(target)) {
            semester.setStatus("CLOSED");
        } else {
            throw ApiException.badRequest("Không thể chuyển học kỳ về trạng thái dự kiến");
        }
        return semesters.save(semester);
    }

    @Transactional
    public void deleteSemester(String id) {
        Semester semester = getSemester(id);
        requirePlanned(semester.getStatus(), "Chỉ được xóa học kỳ đang ở trạng thái dự kiến");
        if (hasReference(id, "grades", "semester_id") || hasReference(id, "timetable_slots", "semester_id")
                || hasReference(id, "teaching_assignments", "semester_id")) {
            throw ApiException.badRequest("Không thể xóa học kỳ đang có điểm, thời khóa biểu hoặc phân công giảng dạy");
        }
        semesters.delete(semester);
    }

    // ---------- Lớp ----------
    public List<SchoolClass> listClasses(String academicYearId, String gradeLevel) {
        List<SchoolClass> result;
        if (academicYearId != null) result = classes.findByAcademicYearId(academicYearId);
        else if (gradeLevel != null) result = classes.findByGradeLevel(gradeLevel);
        else result = classes.findAll();
        return result.stream().sorted(Comparator.comparing(SchoolClass::getCode)).toList();
    }

    public SchoolClass getClass(String id) {
        return classes.findById(id).orElseThrow(() -> ApiException.notFound("Lớp"));
    }

    public List<Cohort> listCohorts(String status) {
        List<Cohort> result = status == null || status.isBlank()
                ? cohorts.findAll() : cohorts.findByStatus(status.trim().toUpperCase(Locale.ROOT));
        return result.stream().sorted(Comparator.comparingInt(Cohort::getEntryYear).reversed()).toList();
    }

    public Cohort getCohort(String id) {
        return cohorts.findById(id).orElseThrow(() -> ApiException.notFound("Niên khóa"));
    }

    public String cohortIdForClass(String classId) {
        return classId == null ? null : classes.findById(classId).map(SchoolClass::getCohortId).orElse(null);
    }

    @Transactional
    public SchoolClass createClass(CreateClassRequest r) {
        String code = normalizeCode(r.code());
        AcademicYear year = getYear(r.academicYearId());
        String studyShift = normalizeStudyShift(r.studyShift());
        int capacity = r.capacity() == null ? 45 : r.capacity();
        requireNotClosed(year.getStatus(), "Không thể tạo lớp trong năm học đã đóng");
        if (classes.findByAcademicYearIdAndCode(year.getId(), code).isPresent()) {
            throw ApiException.conflict("Mã lớp đã tồn tại trong năm học " + year.getCode());
        }
        Room room = validateRoomAssignment(null, year.getId(), studyShift, r.roomId(), capacity);
        Cohort cohort = ensureCohort(year, r.gradeLevel(), "system");
        return classes.save(SchoolClass.builder()
                .id(orGen(r.id(), "c")).code(code)
                .name(defaultName(r.name(), "Lớp " + code))
                .gradeLevel(normalizeCode(r.gradeLevel())).academicYearId(year.getId())
                .cohortId(cohort == null ? null : cohort.getId())
                .studyShift(studyShift)
                .roomId(room == null ? null : room.getId())
                .roomCode(room == null ? null : room.getCode())
                .capacity(capacity)
                .studentCount(0).build());
    }

    @Transactional
    public SchoolClass createClass(CreateClassRequest r, String teacherId,
                                   String teacherName, String assignedBy) {
        SchoolClass created = createClass(r);
        if (teacherId == null || teacherId.isBlank()) return created;
        requireHomeroomTeacherAvailable(created.getAcademicYearId(), teacherId, created.getId());
        created.setHomeroomTeacherId(teacherId);
        created.setHomeroomTeacherName(teacherName);
        created.setHomeroomAssignedAt(Instant.now());
        created.setHomeroomAssignedBy(assignedBy);
        return classes.save(created);
    }

    @Transactional
    public SchoolClass updateClass(String id, UpdateClassRequest r) {
        SchoolClass schoolClass = getClass(id);
        String code = normalizeCode(r.code());
        AcademicYear year = getYear(r.academicYearId());
        requireNotClosed(year.getStatus(), "Không thể chuyển lớp vào năm học đã đóng");
        classes.findByAcademicYearIdAndCode(year.getId(), code).filter(item -> !item.getId().equals(id))
                .ifPresent(item -> { throw ApiException.conflict("Mã lớp đã tồn tại trong năm học " + year.getCode()); });
        int capacity = r.capacity() == null ? 45 : r.capacity();
        long studentCount = referenceCount("users", "class_id", id);
        if (capacity < studentCount) {
            throw ApiException.badRequest("Sức chứa không thể nhỏ hơn sĩ số hiện tại (" + studentCount + ")");
        }
        String studyShift = r.studyShift() == null || r.studyShift().isBlank()
                ? schoolClass.getStudyShift() : normalizeStudyShift(r.studyShift());
        Room room = validateRoomAssignment(id, year.getId(), studyShift, r.roomId(), capacity);
        if (schoolClass.getHomeroomTeacherId() != null && !schoolClass.getHomeroomTeacherId().isBlank()) {
            requireHomeroomTeacherAvailable(
                    year.getId(), schoolClass.getHomeroomTeacherId(), schoolClass.getId());
        }
        schoolClass.setCode(code);
        schoolClass.setName(defaultName(r.name(), "Lớp " + code));
        schoolClass.setGradeLevel(normalizeCode(r.gradeLevel()));
        schoolClass.setAcademicYearId(year.getId());
        Cohort cohort = ensureCohort(year, r.gradeLevel(), "system");
        schoolClass.setCohortId(cohort == null ? null : cohort.getId());
        schoolClass.setStudyShift(studyShift);
        schoolClass.setRoomId(room == null ? null : room.getId());
        schoolClass.setRoomCode(room == null ? null : room.getCode());
        schoolClass.setCapacity(capacity);
        return classes.save(schoolClass);
    }

    @Transactional
    public void deleteClass(String id) {
        SchoolClass schoolClass = getClass(id);
        if (hasReference(id, "users", "class_id") || hasReference(id, "class_enrollments", "class_id")
                || hasReference(id, "timetable_slots", "class_id") || hasReference(id, "attendance_records", "class_id")
                || hasReference(id, "assignments", "class_id") || hasReference(id, "teaching_assignments", "class_id")
                || hasReference(id, "student_yearly_summaries", "class_id")
                || hasReference(id, "student_yearly_summaries", "next_class_id")) {
            throw ApiException.badRequest("Không thể xóa lớp đang có học sinh hoặc dữ liệu giảng dạy");
        }
        classes.delete(schoolClass);
    }

    @Transactional
    public SchoolClass assignHomeroomTeacher(String classId, String teacherId,
                                             String teacherName, String assignedBy) {
        SchoolClass schoolClass = getClass(classId);
        AcademicYear year = getYear(schoolClass.getAcademicYearId());
        requireNotClosed(year.getStatus(), "Không thể thay đổi giáo viên chủ nhiệm của năm học đã đóng");
        requireHomeroomTeacherAvailable(year.getId(), teacherId, schoolClass.getId());
        schoolClass.setHomeroomTeacherId(teacherId);
        schoolClass.setHomeroomTeacherName(teacherName);
        schoolClass.setHomeroomAssignedAt(Instant.now());
        schoolClass.setHomeroomAssignedBy(assignedBy);
        return classes.save(schoolClass);
    }

    @Transactional
    public SchoolClass clearHomeroomTeacher(String classId) {
        SchoolClass schoolClass = getClass(classId);
        schoolClass.setHomeroomTeacherId(null);
        schoolClass.setHomeroomTeacherName(null);
        schoolClass.setHomeroomAssignedAt(null);
        schoolClass.setHomeroomAssignedBy(null);
        return classes.save(schoolClass);
    }

    public List<SchoolClass> classesOfHomeroom(String teacherId) {
        return classes.findByHomeroomTeacherId(teacherId);
    }

    private void requireHomeroomTeacherAvailable(
            String academicYearId, String teacherId, String excludedClassId) {
        classes.findFirstByAcademicYearIdAndHomeroomTeacherIdAndIdNot(
                        academicYearId, teacherId, excludedClassId)
                .ifPresent(existing -> {
                    AcademicYear year = getYear(academicYearId);
                    throw ApiException.conflict(
                            "Giáo viên đã chủ nhiệm lớp " + existing.getCode()
                                    + " trong năm học " + year.getCode()
                                    + ". Mỗi giáo viên chỉ được chủ nhiệm một lớp trong cùng năm học.");
                });
    }

    @Transactional
    public ClassEnrollment recordEnrollment(String studentId, String classId) {
        SchoolClass schoolClass = getClass(classId);
        String yearId = schoolClass.getAcademicYearId();
        ClassEnrollment enrollment = enrollments.findByAcademicYearIdAndClassIdAndStudentId(yearId, classId, studentId)
                .orElseGet(() -> ClassEnrollment.builder().id(Ids.gen("ce")).studentId(studentId)
                        .classId(classId).academicYearId(yearId).cohortId(schoolClass.getCohortId())
                        .enrolledAt(Instant.now()).build());
        List<ClassEnrollment> active = enrollments.findByStudentIdAndStatus(studentId, "ACTIVE");
        active.stream().filter(item -> !item.getId().equals(enrollment.getId())).forEach(item -> {
            item.setStatus("TRANSFERRED");
            item.setEndedAt(Instant.now());
        });
        enrollments.saveAll(active);
        enrollment.setStatus("ACTIVE");
        enrollment.setCohortId(schoolClass.getCohortId());
        enrollment.setEndedAt(null);
        return enrollments.save(enrollment);
    }

    @Transactional
    public void closeEnrollmentForGraduation(String studentId, Instant graduatedAt) {
        List<ClassEnrollment> active = enrollments.findByStudentIdAndStatus(studentId, "ACTIVE");
        active.forEach(item -> {
            item.setStatus("GRADUATED");
            item.setEndedAt(graduatedAt);
        });
        enrollments.saveAll(active);
    }

    @Transactional
    public void completeCohortIfEligible(String cohortId, Instant completedAt) {
        if (cohortId == null || cohortId.isBlank()) return;
        Cohort cohort = cohorts.findById(cohortId).orElse(null);
        if (cohort == null) return;
        Long total = jdbc.queryForObject(
                "SELECT COUNT(*) FROM users WHERE role='STUDENT' AND cohort_id=?", Long.class, cohortId);
        Long unfinished = jdbc.queryForObject(
                "SELECT COUNT(*) FROM users WHERE role='STUDENT' AND cohort_id=? AND COALESCE(student_status,'ENROLLED')<>'GRADUATED'",
                Long.class, cohortId);
        if (total != null && total > 0 && unfinished != null && unfinished == 0) {
            cohort.setStatus("COMPLETED");
            cohort.setCompletedAt(completedAt);
            cohorts.save(cohort);
        }
    }

    public List<ClassEnrollment> enrollmentHistory(String studentId) {
        return enrollments.findAll().stream().filter(item -> studentId.equals(item.getStudentId()))
                .sorted(Comparator.comparing(ClassEnrollment::getEnrolledAt).reversed()).toList();
    }

    // ---------- Môn ----------
    public List<Subject> listSubjects() {
        return subjects.findAll().stream().sorted(Comparator.comparing(Subject::getCode)).toList();
    }

    @Transactional
    public Subject createSubject(CreateSubjectRequest r) {
        String code = normalizeCode(r.code());
        if (subjects.findByCode(code).isPresent()) throw ApiException.conflict("Mã môn học đã tồn tại");
        return subjects.save(Subject.builder().id(orGen(r.id(), "sj")).code(code).name(r.name().trim())
                .coefficient(validCoefficient(r.coefficient())).build());
    }

    @Transactional
    public Subject updateSubject(String id, UpdateSubjectRequest r) {
        Subject subject = subjects.findById(id).orElseThrow(() -> ApiException.notFound("Môn học"));
        String code = r.code() == null || r.code().isBlank() ? subject.getCode() : normalizeCode(r.code());
        subjects.findByCode(code).filter(item -> !item.getId().equals(id))
                .ifPresent(item -> { throw ApiException.conflict("Mã môn học đã tồn tại"); });
        subject.setCode(code);
        subject.setName(r.name().trim());
        subject.setCoefficient(validCoefficient(r.coefficient()));
        return subjects.save(subject);
    }

    @Transactional
    public void deleteSubject(String id) {
        Subject subject = subjects.findById(id).orElseThrow(() -> ApiException.notFound("Môn học"));
        if (hasReference(id, "grades", "subject_id") || hasReference(id, "timetable_slots", "subject_id")
                || hasReference(id, "assignments", "subject_id")
                || hasReference(id, "teaching_assignments", "subject_id")) {
            throw ApiException.badRequest("Không thể xóa môn học đang có điểm, bài tập, thời khóa biểu hoặc phân công");
        }
        subjects.delete(subject);
    }

    public String subjectName(String subjectId) {
        if (subjectId == null) return null;
        return subjects.findById(subjectId).map(Subject::getName).orElse(null);
    }

    public String requireSubjectName(String subjectId) {
        return subjects.findById(subjectId).map(Subject::getName)
                .orElseThrow(() -> ApiException.notFound("Môn học"));
    }

    public double subjectCoefficient(String subjectId) {
        return subjects.findById(subjectId).map(Subject::getCoefficient).orElse(1.0);
    }

    public List<String> semesterIdsOfYear(String academicYearId) {
        return semesters.findByAcademicYearId(academicYearId).stream().map(Semester::getId).toList();
    }

    public Optional<SchoolClass> classByCode(String code) {
        if (code == null || code.isBlank()) return Optional.empty();
        String normalized = normalizeCode(code);
        Optional<AcademicYear> active = years.findByStatus("ACTIVE").stream().findFirst();
        if (active.isPresent()) {
            Optional<SchoolClass> current = classes.findByAcademicYearIdAndCode(active.get().getId(), normalized);
            if (current.isPresent()) return current;
        }
        return classes.findAll().stream().filter(item -> normalized.equalsIgnoreCase(item.getCode()))
                .max(Comparator.comparing(item -> getYear(item.getAcademicYearId()).getStartDate(),
                        Comparator.nullsLast(Comparator.naturalOrder())));
    }

    public Optional<SchoolClass> findNextClass(String academicYearId, SchoolClass currentClass) {
        int nextGrade = parseGrade(currentClass.getGradeLevel()) + 1;
        if (nextGrade > 12) return Optional.empty();
        return findClassInNextYear(academicYearId, currentClass, nextGrade);
    }

    public Optional<SchoolClass> findRetainedClass(String academicYearId, SchoolClass currentClass) {
        return findClassInNextYear(academicYearId, currentClass, parseGrade(currentClass.getGradeLevel()));
    }

    private Optional<SchoolClass> findClassInNextYear(String academicYearId, SchoolClass currentClass,
                                                       int targetGrade) {
        AcademicYear current = years.findById(academicYearId).orElseThrow(() -> ApiException.notFound("Năm học"));
        Optional<AcademicYear> nextYear = years.findAll().stream()
                .filter(y -> y.getStartDate() != null && current.getStartDate() != null
                        && y.getStartDate().isAfter(current.getStartDate()))
                .min(Comparator.comparing(AcademicYear::getStartDate));
        if (nextYear.isEmpty()) return Optional.empty();
        String wanted = "K" + targetGrade;
        String section = currentClass.getCode() == null ? "" : currentClass.getCode().replaceFirst("^\\d+", "");
        String expectedCode = targetGrade + section;
        return classes.findByAcademicYearId(nextYear.get().getId()).stream()
                .filter(c -> wanted.equalsIgnoreCase(c.getGradeLevel())
                        || String.valueOf(targetGrade).equalsIgnoreCase(c.getGradeLevel()))
                .filter(c -> expectedCode.equalsIgnoreCase(c.getCode()))
                .findFirst();
    }

    @Transactional
    public AcademicYear closeYear(String id) {
        AcademicYear year = getYear(id);
        if (!"CLOSED".equals(year.getStatus())) {
            year.setStatus("CLOSED");
            closeSemestersOfYear(id);
        }
        return years.save(year);
    }

    public void assertSemesterExists(String semesterId) {
        if (!semesters.existsById(semesterId)) throw ApiException.notFound("Học kỳ");
    }

    public void assertSemesterWritable(String semesterId) {
        Semester semester = getSemester(semesterId);
        AcademicYear year = getYear(semester.getAcademicYearId());
        if ("CLOSED".equals(semester.getStatus()) || "CLOSED".equals(year.getStatus())) {
            throw ApiException.conflict("Học kỳ thuộc năm học đã khóa; không thể thay đổi dữ liệu lịch sử");
        }
    }

    public void assertClassWritable(String classId) {
        SchoolClass schoolClass = getClass(classId);
        if ("CLOSED".equals(getYear(schoolClass.getAcademicYearId()).getStatus())) {
            throw ApiException.conflict("Lớp thuộc năm học đã khóa; không thể thay đổi dữ liệu lịch sử");
        }
    }

    /** Cross-domain (finance): bậc khối của lớp, null-safe. */
    public String gradeLevelOf(String classId) {
        if (classId == null) return null;
        return classes.findById(classId).map(SchoolClass::getGradeLevel).orElse(null);
    }

    // ---------- Phòng ----------
    public List<Room> listRooms() {
        return rooms.findAll().stream().sorted(Comparator.comparing(Room::getCode)).toList();
    }

    @Transactional
    public Room createRoom(CreateRoomRequest r) {
        String code = normalizeCode(r.code());
        boolean supportsMorning = r.supportsMorning() == null || r.supportsMorning();
        boolean supportsAfternoon = r.supportsAfternoon() == null || r.supportsAfternoon();
        String roomType = normalizeRoomType(r.roomType());
        validateRoomShifts(supportsMorning, supportsAfternoon);
        if (rooms.findByCode(code).isPresent()) throw ApiException.conflict("Mã phòng đã tồn tại");
        return rooms.save(Room.builder().id(orGen(r.id(), "rm")).code(code)
                .name(defaultName(r.name(), "Phòng " + code)).capacity(r.capacity())
                .supportsMorning(supportsMorning).supportsAfternoon(supportsAfternoon)
                .roomType(roomType).equipmentTags(trimToNull(r.equipmentTags()))
                .status(normalizeRoomStatus(r.status()))
                .homeRoomEligible(r.homeRoomEligible() == null ? "GENERAL".equals(roomType) : r.homeRoomEligible())
                .notes(trimToNull(r.notes())).build());
    }

    @Transactional
    public Room updateRoom(String id, UpdateRoomRequest r) {
        Room room = rooms.findById(id).orElseThrow(() -> ApiException.notFound("Phòng học"));
        String code = normalizeCode(r.code());
        boolean supportsMorning = r.supportsMorning() == null || r.supportsMorning();
        boolean supportsAfternoon = r.supportsAfternoon() == null || r.supportsAfternoon();
        String roomType = normalizeRoomType(r.roomType());
        String status = normalizeRoomStatus(r.status());
        validateRoomShifts(supportsMorning, supportsAfternoon);
        List<SchoolClass> assignedClasses = classes.findByRoomId(id);
        if (!supportsMorning && assignedClasses.stream().anyMatch(item -> "MORNING".equals(item.getStudyShift()))) {
            throw ApiException.conflict("Phòng đang được giao cho lớp ca sáng");
        }
        if (!supportsAfternoon && assignedClasses.stream().anyMatch(item -> "AFTERNOON".equals(item.getStudyShift()))) {
            throw ApiException.conflict("Phòng đang được giao cho lớp ca chiều");
        }
        rooms.findByCode(code).filter(item -> !item.getId().equals(id))
                .ifPresent(item -> { throw ApiException.conflict("Mã phòng đã tồn tại"); });
        if (!code.equals(room.getCode()) && hasReference(room.getCode(), "timetable_slots", "room_code")) {
            throw ApiException.badRequest("Không thể đổi mã phòng đang được sử dụng trong thời khóa biểu");
        }
        room.setCode(code);
        room.setName(defaultName(r.name(), "Phòng " + code));
        room.setCapacity(r.capacity());
        room.setSupportsMorning(supportsMorning);
        room.setSupportsAfternoon(supportsAfternoon);
        room.setRoomType(roomType);
        room.setEquipmentTags(trimToNull(r.equipmentTags()));
        room.setStatus(status);
        room.setHomeRoomEligible(r.homeRoomEligible() == null ? room.isHomeRoomEligible() : r.homeRoomEligible());
        room.setNotes(trimToNull(r.notes()));
        Room saved = rooms.save(room);
        assignedClasses.forEach(item -> item.setRoomCode(saved.getCode()));
        classes.saveAll(assignedClasses);
        return saved;
    }

    @Transactional
    public void deleteRoom(String id) {
        Room room = rooms.findById(id).orElseThrow(() -> ApiException.notFound("Phòng học"));
        if (!classes.findByRoomId(id).isEmpty() || hasReference(room.getCode(), "timetable_slots", "room_code")) {
            throw ApiException.badRequest("Không thể xóa phòng đang được sử dụng trong thời khóa biểu");
        }
        rooms.delete(room);
    }

    public Room requireRoomForClass(String roomCode, String classId) {
        if (roomCode == null || roomCode.isBlank()) return null;
        Room room = rooms.findByCode(normalizeCode(roomCode))
                .orElseThrow(() -> ApiException.badRequest("Phòng học không tồn tại"));
        SchoolClass schoolClass = getClass(classId);
        if (!"ACTIVE".equalsIgnoreCase(room.getStatus())) {
            throw ApiException.badRequest("Phòng " + room.getCode() + " hiện không sẵn sàng sử dụng");
        }
        validateRoomSupportsShift(room, schoolClass.getStudyShift());
        classes.findByAcademicYearIdAndStudyShiftAndRoomId(
                        schoolClass.getAcademicYearId(), schoolClass.getStudyShift(), room.getId())
                .filter(item -> !item.getId().equals(classId))
                .ifPresent(item -> {
                    throw ApiException.conflict("Phòng " + room.getCode() + " đã được giao cho lớp "
                            + item.getCode() + " trong cùng ca học");
                });
        if (room.getCapacity() != null && room.getCapacity() < schoolClass.getStudentCount()) {
            throw ApiException.badRequest("Sức chứa phòng nhỏ hơn sĩ số lớp");
        }
        return room;
    }

    private Room validateRoomAssignment(String currentClassId, String academicYearId,
                                        String studyShift, String roomId, int classCapacity) {
        if (roomId == null || roomId.isBlank()) return null;
        Room room = rooms.findById(roomId).orElseThrow(() -> ApiException.notFound("Phòng học"));
        if (!"ACTIVE".equalsIgnoreCase(room.getStatus())) {
            throw ApiException.badRequest("Phòng " + room.getCode() + " hiện không sẵn sàng sử dụng");
        }
        if (!room.isHomeRoomEligible() || !"GENERAL".equalsIgnoreCase(room.getRoomType())) {
            throw ApiException.badRequest("Phòng " + room.getCode()
                    + " là phòng chức năng, không thể dùng làm phòng chủ nhiệm cố định");
        }
        validateRoomSupportsShift(room, studyShift);
        classes.findByAcademicYearIdAndStudyShiftAndRoomId(academicYearId, studyShift, roomId)
                .filter(item -> currentClassId == null || !item.getId().equals(currentClassId))
                .ifPresent(item -> {
                    throw ApiException.conflict("Phòng " + room.getCode() + " đã được giao cho lớp "
                            + item.getCode() + " trong cùng ca học");
                });
        if (room.getCapacity() != null && room.getCapacity() < classCapacity) {
            throw ApiException.badRequest("Sức chứa phòng " + room.getCode()
                    + " nhỏ hơn sức chứa dự kiến của lớp");
        }
        return room;
    }

    private void validateRoomSupportsShift(Room room, String studyShift) {
        if ("MORNING".equals(studyShift) && !room.isSupportsMorning()) {
            throw ApiException.badRequest("Phòng " + room.getCode() + " không phục vụ ca sáng");
        }
        if ("AFTERNOON".equals(studyShift) && !room.isSupportsAfternoon()) {
            throw ApiException.badRequest("Phòng " + room.getCode() + " không phục vụ ca chiều");
        }
    }

    private void validateRoomShifts(boolean supportsMorning, boolean supportsAfternoon) {
        if (!supportsMorning && !supportsAfternoon) {
            throw ApiException.badRequest("Phòng phải phục vụ ít nhất một ca học");
        }
    }

    // ---------- Seed (raw insert, dùng bởi DataSeeder) ----------
    public void seedAll(List<AcademicYear> y, List<Semester> s, List<SchoolClass> c,
                        List<Subject> sj, List<Room> rm) {
        years.saveAll(y); semesters.saveAll(s); classes.saveAll(c);
        subjects.saveAll(sj); rooms.saveAll(rm);
    }

    // ---------- Helper ----------
    private void validateSemester(String currentId, AcademicYear year, String code, int sequence,
                                  LocalDate startDate, LocalDate endDate) {
        validatePeriod(startDate, endDate, "học kỳ");
        if (!within(startDate, endDate, year.getStartDate(), year.getEndDate())) {
            throw ApiException.badRequest("Thời gian học kỳ phải nằm trong thời gian năm học");
        }
        semesters.findByAcademicYearIdAndCode(year.getId(), code)
                .filter(item -> currentId == null || !item.getId().equals(currentId))
                .ifPresent(item -> { throw ApiException.conflict("Mã học kỳ đã tồn tại trong năm học"); });
        semesters.findByAcademicYearIdAndSequence(year.getId(), sequence)
                .filter(item -> currentId == null || !item.getId().equals(currentId))
                .ifPresent(item -> { throw ApiException.conflict("Thứ tự học kỳ đã tồn tại trong năm học"); });
        for (Semester item : semesters.findByAcademicYearId(year.getId())) {
            if ((currentId == null || !item.getId().equals(currentId))
                    && overlaps(startDate, endDate, item.getStartDate(), item.getEndDate())) {
                throw ApiException.badRequest("Thời gian học kỳ bị trùng với " + item.getName());
            }
        }
    }

    private void validatePeriod(LocalDate startDate, LocalDate endDate, String label) {
        if (startDate == null || endDate == null) {
            throw ApiException.badRequest("Cần nhập đầy đủ ngày bắt đầu và kết thúc " + label);
        }
        if (endDate.isBefore(startDate)) {
            throw ApiException.badRequest("Ngày kết thúc " + label + " phải từ ngày bắt đầu trở đi");
        }
    }

    private void assertYearPeriodAvailable(String currentId, LocalDate startDate, LocalDate endDate) {
        for (AcademicYear item : years.findAll()) {
            if ((currentId == null || !item.getId().equals(currentId))
                    && overlaps(startDate, endDate, item.getStartDate(), item.getEndDate())) {
                throw ApiException.badRequest("Thời gian năm học bị trùng với " + item.getName());
            }
        }
    }

    private boolean within(LocalDate start, LocalDate end, LocalDate outerStart, LocalDate outerEnd) {
        return start != null && end != null && outerStart != null && outerEnd != null
                && !start.isBefore(outerStart) && !end.isAfter(outerEnd);
    }

    private boolean overlaps(LocalDate startA, LocalDate endA, LocalDate startB, LocalDate endB) {
        return startA != null && endA != null && startB != null && endB != null
                && !endA.isBefore(startB) && !endB.isBefore(startA);
    }

    private void closeSemestersOfYear(String yearId) {
        List<Semester> list = semesters.findByAcademicYearId(yearId);
        list.forEach(item -> item.setStatus("CLOSED"));
        semesters.saveAll(list);
    }

    private String normalizeStatus(String status) {
        if (status == null) throw ApiException.badRequest("Thiếu trạng thái");
        String normalized = status.trim().toUpperCase(Locale.ROOT);
        if (!List.of("PLANNED", "ACTIVE", "CLOSED").contains(normalized)) {
            throw ApiException.badRequest("Trạng thái không hợp lệ");
        }
        return normalized;
    }

    private String normalizeCode(String code) {
        if (code == null || code.isBlank()) throw ApiException.badRequest("Mã không được để trống");
        return code.trim().toUpperCase(Locale.ROOT);
    }

    private String normalizeStudyShift(String studyShift) {
        if (studyShift == null || studyShift.isBlank()) return "MORNING";
        String normalized = studyShift.trim().toUpperCase(Locale.ROOT);
        if (!List.of("MORNING", "AFTERNOON").contains(normalized)) {
            throw ApiException.badRequest("Ca học chỉ nhận giá trị MORNING hoặc AFTERNOON");
        }
        return normalized;
    }

    private String defaultName(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private void requirePlanned(String status, String message) {
        if (!"PLANNED".equals(status)) throw ApiException.badRequest(message);
    }

    private void requireNotClosed(String status, String message) {
        if ("CLOSED".equals(status)) throw ApiException.badRequest(message);
    }

    private boolean hasReference(Object value, String table, String column) {
        return referenceCount(table, column, value) > 0;
    }

    private long referenceCount(String table, String column, Object value) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE " + column + " = ?", Long.class, value);
        return count == null ? 0 : count;
    }

    private long countStudentsWithoutFinalizedSummary(String academicYearId) {
        Long count = jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM users u
                JOIN classes c ON c.id = u.class_id
                WHERE u.role = 'STUDENT'
                  AND c.academic_year_id = ?
                  AND NOT EXISTS (
                    SELECT 1 FROM student_yearly_summaries s
                    WHERE s.academic_year_id = ? AND s.student_id = u.id AND s.finalized_at IS NOT NULL
                  )
                """, Long.class, academicYearId, academicYearId);
        return count == null ? 0 : count;
    }

    private Cohort ensureCohort(AcademicYear year, String gradeLevel, String actorId) {
        int grade = parseGrade(gradeLevel);
        if (grade < 10 || grade > 12 || year.getStartDate() == null) return null;
        int entryYear = year.getStartDate().getYear() - (grade - 10);
        int graduationYear = entryYear + 3;
        String code = entryYear + "-" + graduationYear;
        return cohorts.findByCode(code).orElseGet(() -> cohorts.save(Cohort.builder()
                .id("cohort-" + code)
                .code(code)
                .name("Niên khóa " + code)
                .entryYear(entryYear)
                .graduationYear(graduationYear)
                .durationYears(3)
                .status("ACTIVE")
                .entryAcademicYearId(grade == 10 ? year.getId() : null)
                .createdAt(Instant.now())
                .createdBy(actorId)
                .build()));
    }

    private String orGen(String id, String prefix) {
        return (id == null || id.isBlank()) ? Ids.gen(prefix) : id;
    }

    private double validCoefficient(Double coefficient) {
        double value = coefficient == null ? 1.0 : coefficient;
        if (!Double.isFinite(value) || value <= 0 || value > 10) {
            throw ApiException.badRequest("Hệ số môn phải lớn hơn 0 và không vượt quá 10");
        }
        return value;
    }

    private int parseGrade(String gradeLevel) {
        if (gradeLevel == null) return 0;
        try { return Integer.parseInt(gradeLevel.replaceAll("\\D", "")); }
        catch (NumberFormatException ignored) { return 0; }
    }

    private String normalizeRoomType(String value) {
        String normalized = value == null || value.isBlank() ? "GENERAL" : value.trim().toUpperCase(Locale.ROOT);
        if (!Set.of("GENERAL", "LAB", "COMPUTER", "LANGUAGE", "SPORT", "ART", "LIBRARY", "MULTIPURPOSE", "OTHER").contains(normalized)) {
            throw ApiException.badRequest("Loại phòng không hợp lệ");
        }
        return normalized;
    }

    private String normalizeRoomStatus(String value) {
        String normalized = value == null || value.isBlank() ? "ACTIVE" : value.trim().toUpperCase(Locale.ROOT);
        if (!Set.of("ACTIVE", "MAINTENANCE", "INACTIVE").contains(normalized)) {
            throw ApiException.badRequest("Trạng thái phòng không hợp lệ");
        }
        return normalized;
    }

    private LocalDate today() {
        return LocalDate.now(clock.withZone(ZoneId.of("Asia/Ho_Chi_Minh")));
    }
}
