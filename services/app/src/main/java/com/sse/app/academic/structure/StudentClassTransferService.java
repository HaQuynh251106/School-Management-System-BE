package com.sse.app.academic.structure;

import com.sse.app.audit.AuditService;
import com.sse.app.common.ApiException;
import com.sse.app.common.Ids;
import com.sse.app.common.PageResponse;
import com.sse.app.common.Paging;
import com.sse.app.identity.User;
import com.sse.app.identity.UserRepository;
import com.sse.app.identity.UserService;
import com.sse.app.notification.NotificationService;
import com.sse.app.security.CurrentUser;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.*;
import java.util.*;

import static com.sse.app.academic.structure.StudentClassTransferDtos.*;

@Service
public class StudentClassTransferService {
    private final StudentClassTransferRepository transfers;
    private final SchoolClassRepository classes;
    private final UserRepository userRepository;
    private final UserService users;
    private final StructureService structure;
    private final NotificationService notifications;
    private final AuditService audit;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public StudentClassTransferService(StudentClassTransferRepository transfers,
                                       SchoolClassRepository classes,
                                       UserRepository userRepository,
                                       UserService users,
                                       StructureService structure,
                                       NotificationService notifications,
                                       AuditService audit,
                                       JdbcTemplate jdbc,
                                       Clock clock) {
        this.transfers = transfers;
        this.classes = classes;
        this.userRepository = userRepository;
        this.users = users;
        this.structure = structure;
        this.notifications = notifications;
        this.audit = audit;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public TransferWindow window(String academicYearId) {
        AcademicYear year = structure.getYear(academicYearId);
        List<Semester> semesters = structure.listSemesters(academicYearId);
        LocalDate today = LocalDate.now(clock);
        List<Semester> closed = semesters.stream()
                .filter(item -> "CLOSED".equals(item.getStatus()))
                .filter(item -> item.getEndDate() == null || !item.getEndDate().isAfter(today))
                .sorted(Comparator.comparingInt(Semester::getSequence))
                .toList();
        List<Semester> active = semesters.stream().filter(item -> "ACTIVE".equals(item.getStatus())).toList();
        Semester latest = closed.isEmpty() ? null : closed.get(closed.size() - 1);
        Semester next = latest == null ? null : semesters.stream()
                .filter(item -> item.getSequence() > latest.getSequence())
                .min(Comparator.comparingInt(Semester::getSequence)).orElse(null);

        boolean yearOpen = "ACTIVE".equals(year.getStatus());
        boolean eligible = yearOpen && active.isEmpty() && latest != null;
        String boundary = eligible && closed.size() == semesters.size() ? "YEAR_END"
                : eligible ? "SEMESTER_END" : "NONE";
        String reason;
        if ("CLOSED".equals(year.getStatus())) {
            reason = "Năm học đã đóng; dữ liệu chỉ được tra cứu.";
        } else if (!yearOpen) {
            reason = "Chỉ chuyển lớp trong năm học đang hoạt động.";
        } else if (!active.isEmpty()) {
            reason = "Học kỳ " + active.get(0).getName() + " đang diễn ra. Hãy đóng học kỳ trước khi chuyển lớp.";
        } else if (latest == null) {
            reason = "Chưa có học kỳ nào kết thúc nên chưa mở chuyển lớp.";
        } else {
            reason = boundary.equals("YEAR_END")
                    ? "Đang trong giai đoạn kết thúc năm học; có thể điều chuyển trước khi khóa năm."
                    : "Đang ở khoảng chuyển tiếp sau " + latest.getName() + "; có thể chuyển lớp.";
        }
        return new TransferWindow(year.getId(), year.getCode(), eligible, boundary, reason,
                closed.size(), active.size(), latest == null ? null : latest.getId(),
                latest == null ? null : latest.getName(), latest == null ? null : latest.getEndDate(),
                next == null ? null : next.getId(), next == null ? null : next.getName(),
                next == null ? null : next.getStartDate(), today);
    }

    @Transactional
    public StudentClassTransfer transfer(TransferRequest request, CurrentUser actor) {
        User student = requireStudent(request.studentId());
        if (student.getClassId() == null || student.getClassId().isBlank()) {
            throw ApiException.conflict("Học sinh chưa có lớp hiện tại; hãy dùng chức năng phân lớp đầu cấp");
        }
        SchoolClass source = structure.getClass(student.getClassId());
        SchoolClass target = structure.getClass(request.targetClassId());
        assertTransferAllowed(source, target, request.effectiveDate());
        if (userRepository.countByClassIdAndRole(target.getId(), "STUDENT") >= target.getCapacity()) {
            throw ApiException.conflict("Lớp " + target.getCode() + " đã đủ sĩ số " + target.getCapacity() + " học sinh");
        }

        Instant now = Instant.now(clock);
        Instant effectiveAt = request.effectiveDate().atStartOfDay(ZoneOffset.UTC).toInstant();
        String actorName = Optional.ofNullable(users.fullNameOf(actor.id())).orElse(actor.username());
        StudentClassTransfer transfer = transfers.save(StudentClassTransfer.builder()
                .id(Ids.gen("class-transfer"))
                .academicYearId(source.getAcademicYearId())
                .studentId(student.getId()).studentName(student.getFullName())
                .sourceClassId(source.getId()).sourceClassCode(source.getCode())
                .targetClassId(target.getId()).targetClassCode(target.getCode())
                .effectiveDate(request.effectiveDate()).reason(request.reason().trim())
                .status("APPLIED").createdAt(now).createdBy(actor.id()).createdByName(actorName)
                .build());

        student.setClassId(target.getId());
        student.setClassName(target.getCode());
        student.setCohortId(target.getCohortId());
        student.setStudentStatus("ENROLLED");
        userRepository.save(student);
        structure.recordEnrollment(student.getId(), target.getId(), effectiveAt);
        refreshCount(source);
        refreshCount(target);
        notifyTransfer(transfer, student, source, target, false);
        audit.record(actor.id(), actorName, actor.role(), "STUDENT_CLASS_TRANSFERRED",
                "academic_structure", "student_class_transfer", transfer.getId(),
                student.getFullName() + ": " + source.getCode() + " → " + target.getCode()
                        + " · hiệu lực " + request.effectiveDate() + " · " + request.reason().trim());
        return transfer;
    }

    public PageResponse<StudentClassTransfer> history(String academicYearId, String studentId,
                                                       String status, int page, int size) {
        Specification<StudentClassTransfer> specification = Specification.where(null);
        if (academicYearId != null && !academicYearId.isBlank()) {
            specification = specification.and((root, ignored, cb) ->
                    cb.equal(root.get("academicYearId"), academicYearId));
        }
        if (studentId != null && !studentId.isBlank()) {
            specification = specification.and((root, ignored, cb) -> cb.equal(root.get("studentId"), studentId));
        }
        if (status != null && !status.isBlank() && !"ALL".equalsIgnoreCase(status)) {
            specification = specification.and((root, ignored, cb) ->
                    cb.equal(root.get("status"), status.trim().toUpperCase(Locale.ROOT)));
        }
        return PageResponse.from(transfers.findAll(specification,
                Paging.request(page, size, Sort.by(Sort.Direction.DESC, "createdAt"))));
    }

    @Transactional
    public StudentClassTransfer undo(String transferId, UndoRequest request, CurrentUser actor) {
        StudentClassTransfer transfer = transfers.findById(transferId)
                .orElseThrow(() -> ApiException.notFound("Lần chuyển lớp"));
        if (!"APPLIED".equals(transfer.getStatus())) {
            throw ApiException.conflict("Lần chuyển lớp này đã được hoàn tác");
        }
        TransferWindow window = window(transfer.getAcademicYearId());
        if (!window.eligible()) throw ApiException.conflict(window.reason());
        User student = requireStudent(transfer.getStudentId());
        if (!transfer.getTargetClassId().equals(student.getClassId())) {
            throw ApiException.conflict("Học sinh đã có thay đổi lớp khác sau lần chuyển này");
        }
        if (transfers.existsByStudentIdAndStatusAndCreatedAtAfter(student.getId(), "APPLIED", transfer.getCreatedAt())) {
            throw ApiException.conflict("Không thể hoàn tác vì học sinh đã có lần chuyển lớp mới hơn");
        }
        assertNoDependentData(transfer);

        SchoolClass source = structure.getClass(transfer.getSourceClassId());
        SchoolClass target = structure.getClass(transfer.getTargetClassId());
        Instant now = Instant.now(clock);
        student.setClassId(source.getId());
        student.setClassName(source.getCode());
        student.setCohortId(source.getCohortId());
        userRepository.save(student);
        structure.recordEnrollment(student.getId(), source.getId(), now);
        structure.markEnrollmentRolledBack(student.getId(), target.getId(), now);
        refreshCount(source);
        refreshCount(target);

        transfer.setStatus("ROLLED_BACK");
        transfer.setRolledBackAt(now);
        transfer.setRolledBackBy(actor.id());
        transfer.setRollbackReason(request.reason().trim());
        transfers.save(transfer);
        notifications.removeByReference("CLASS_TRANSFER", transfer.getId());
        notifyTransfer(transfer, student, target, source, true);
        String actorName = Optional.ofNullable(users.fullNameOf(actor.id())).orElse(actor.username());
        audit.record(actor.id(), actorName, actor.role(), "STUDENT_CLASS_TRANSFER_ROLLED_BACK",
                "academic_structure", "student_class_transfer", transfer.getId(),
                student.getFullName() + " trở lại " + source.getCode() + " · " + request.reason().trim());
        return transfer;
    }

    private void assertTransferAllowed(SchoolClass source, SchoolClass target, LocalDate effectiveDate) {
        if (source.getId().equals(target.getId())) throw ApiException.badRequest("Lớp tiếp nhận phải khác lớp hiện tại");
        if (!Objects.equals(source.getAcademicYearId(), target.getAcademicYearId())) {
            throw ApiException.badRequest("Chỉ được chuyển giữa các lớp trong cùng một năm học");
        }
        if (!Objects.equals(source.getGradeLevel(), target.getGradeLevel())) {
            throw ApiException.badRequest("Chuyển lớp cuối học kỳ phải giữ nguyên khối; lên lớp được xử lý ở Tổng kết năm");
        }
        if (source.getCohortId() != null && target.getCohortId() != null
                && !Objects.equals(source.getCohortId(), target.getCohortId())) {
            throw ApiException.badRequest("Lớp tiếp nhận không thuộc cùng niên khóa của học sinh");
        }
        TransferWindow window = window(source.getAcademicYearId());
        if (!window.eligible()) throw ApiException.conflict(window.reason());
        LocalDate today = LocalDate.now(clock);
        if (effectiveDate.isAfter(today)) throw ApiException.badRequest("Ngày hiệu lực không được ở tương lai");
        if (window.latestClosedSemesterEndDate() != null
                && effectiveDate.isBefore(window.latestClosedSemesterEndDate())) {
            throw ApiException.badRequest("Ngày hiệu lực phải từ ngày kết thúc học kỳ "
                    + window.latestClosedSemesterEndDate() + " trở đi");
        }
    }

    private void assertNoDependentData(StudentClassTransfer transfer) {
        Timestamp createdAt = Timestamp.from(transfer.getCreatedAt());
        long attendance = count("select count(*) from attendance_records where student_id=? and class_id=? and date>=?",
                transfer.getStudentId(), transfer.getTargetClassId(), transfer.getEffectiveDate());
        long grades = count("select count(*) from grades where student_id=? and recorded_at>=?",
                transfer.getStudentId(), createdAt);
        long submissions = count("select count(*) from assignment_submissions s join assignments a on a.id=s.assignment_id "
                        + "where s.student_id=? and a.class_id=? and s.submitted_at>=?",
                transfer.getStudentId(), transfer.getTargetClassId(), createdAt);
        long leaveRequests = count("select count(*) from leave_requests where student_id=? and class_id=? and created_at>=?",
                transfer.getStudentId(), transfer.getTargetClassId(), createdAt);
        long total = attendance + grades + submissions + leaveRequests;
        if (total > 0) {
            throw ApiException.conflict("Không thể hoàn tác vì đã phát sinh " + total
                    + " dữ liệu điểm, điểm danh, bài nộp hoặc đơn nghỉ sau khi chuyển lớp");
        }
    }

    private long count(String sql, Object... args) {
        Long value = jdbc.queryForObject(sql, Long.class, args);
        return value == null ? 0 : value;
    }

    private User requireStudent(String studentId) {
        User student = userRepository.findById(studentId).orElseThrow(() -> ApiException.notFound("Học sinh"));
        if (!"STUDENT".equals(student.getRole())) throw ApiException.badRequest("Người dùng không phải học sinh");
        if (!"ACTIVE".equals(student.getStatus())) throw ApiException.conflict("Tài khoản học sinh không hoạt động");
        return student;
    }

    private void refreshCount(SchoolClass schoolClass) {
        schoolClass.setStudentCount((int) userRepository.countByClassIdAndRole(schoolClass.getId(), "STUDENT"));
        classes.save(schoolClass);
    }

    private void notifyTransfer(StudentClassTransfer transfer, User student, SchoolClass from,
                                SchoolClass to, boolean rolledBack) {
        String title = rolledBack ? "Đã hoàn tác chuyển lớp" : "Thông báo chuyển lớp";
        String body = rolledBack
                ? student.getFullName() + " đã trở lại lớp " + to.getCode() + "."
                : student.getFullName() + " được chuyển từ lớp " + from.getCode() + " sang lớp "
                    + to.getCode() + ", hiệu lực từ " + transfer.getEffectiveDate() + ".";
        notifications.notifyUser(student.getId(), "CLASS_TRANSFER", "IMPORTANT", title, body,
                "CLASS_TRANSFER", transfer.getId());
        notifications.notifyParentsOfStudent(student.getId(), "CLASS_TRANSFER", "IMPORTANT", title, body,
                "CLASS_TRANSFER", transfer.getId());
        LinkedHashSet<String> teachers = new LinkedHashSet<>();
        if (from.getHomeroomTeacherId() != null) teachers.add(from.getHomeroomTeacherId());
        if (to.getHomeroomTeacherId() != null) teachers.add(to.getHomeroomTeacherId());
        notifications.notifyUsers(new ArrayList<>(teachers), "CLASS_TRANSFER", "IMPORTANT", title, body,
                "CLASS_TRANSFER", transfer.getId());
    }
}
