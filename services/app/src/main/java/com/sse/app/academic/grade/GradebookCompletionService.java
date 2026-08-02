package com.sse.app.academic.grade;

import com.sse.app.academic.structure.StructureService;
import com.sse.app.academic.timetable.TeachingAssignmentService;
import com.sse.app.common.ApiException;
import com.sse.app.common.Ids;
import com.sse.app.identity.UserDto;
import com.sse.app.identity.UserService;
import com.sse.app.security.CurrentUser;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

import static com.sse.app.academic.grade.GradebookCompletionDtos.*;

@Service
@RequiredArgsConstructor
public class GradebookCompletionService {
    private final JdbcTemplate jdbc;
    private final GradeRepository grades;
    private final ExamCategoryRepository categories;
    private final StructureService structure;
    private final TeachingAssignmentService assignments;
    private final UserService users;

    public CompletionView status(String semesterId, String classId, String subjectId, CurrentUser actor) {
        structure.assertSemesterExists(semesterId);
        structure.getClass(classId);
        if (actor.isTeacher()) assertAssignedTeacher(actor.id(), semesterId, classId, subjectId);
        else if (!actor.canManageAcademics()) throw ApiException.forbidden("Không có quyền xem trạng thái hoàn tất sổ điểm");
        return buildView(semesterId, classId, subjectId);
    }

    @Transactional
    public CompletionView complete(String semesterId, String classId, String subjectId,
                                   CompletionRequest request, CurrentUser actor) {
        if (!actor.isTeacher()) throw ApiException.forbidden("Chỉ giáo viên bộ môn được xác nhận hoàn tất sổ điểm");
        assertAssignedTeacher(actor.id(), semesterId, classId, subjectId);
        assertSemesterWritableOrOfficialRevision(semesterId, classId, subjectId);
        CompletionView view = buildView(semesterId, classId, subjectId);
        if (view.missingStudentCount() > 0) {
            throw ApiException.conflict("Còn " + view.missingStudentCount()
                    + " học sinh thiếu đầu điểm: " + String.join("; ", view.missingDetails().stream().limit(5).toList()));
        }
        Instant now = Instant.now();
        List<String> ids = jdbc.query("select id from gradebook_locks where semester_id=? and class_id=? and subject_id=?",
                (rs, rowNum) -> rs.getString(1), semesterId, classId, subjectId);
        if (ids.isEmpty()) {
            jdbc.update("insert into gradebook_locks(id,semester_id,class_id,subject_id,locked,reason,changed_by,changed_at,version) values (?,?,?,?,?,?,?,?,0)",
                    Ids.gen("gradebook-lock"), semesterId, classId, subjectId, true, clean(request == null ? null : request.note()),
                    actor.id(), Timestamp.from(now));
        } else {
            jdbc.update("update gradebook_locks set locked=true,reason=?,changed_by=?,changed_at=?,version=version+1 where id=?",
                    clean(request == null ? null : request.note()), actor.id(), Timestamp.from(now), ids.get(0));
        }
        audit(semesterId, classId, subjectId, "COMPLETED", request == null ? null : request.note(), actor.id());
        return buildView(semesterId, classId, subjectId);
    }

    @Transactional
    public CompletionView reopen(String semesterId, String classId, String subjectId,
                                 ReopenRequest request, CurrentUser actor) {
        return reopenInternal(semesterId, classId, subjectId, request, actor, "REOPENED");
    }

    /** Mở sổ điểm thuộc năm đã đóng trong một hồ sơ điều chỉnh học bạ chính thức. */
    @Transactional
    public CompletionView reopenForOfficialRevision(String semesterId, String classId, String subjectId,
                                                     ReopenRequest request, CurrentUser actor) {
        return reopenInternal(semesterId, classId, subjectId, request, actor, "OFFICIAL_REVISION_REOPENED");
    }

    private CompletionView reopenInternal(String semesterId, String classId, String subjectId,
                                          ReopenRequest request, CurrentUser actor, String action) {
        if (!actor.canManageAcademics()) throw ApiException.forbidden("Chỉ Giáo vụ được mở lại sổ điểm");
        int updated = jdbc.update("update gradebook_locks set locked=false,reason=?,changed_by=?,changed_at=?,version=version+1 where semester_id=? and class_id=? and subject_id=?",
                request.reason().trim(), actor.id(), Timestamp.from(Instant.now()), semesterId, classId, subjectId);
        if (updated == 0) throw ApiException.conflict("Sổ điểm chưa được giáo viên xác nhận hoàn tất nên không cần mở lại");
        audit(semesterId, classId, subjectId, action, request.reason(), actor.id());
        return buildView(semesterId, classId, subjectId);
    }

    /**
     * Năm học đã đóng chỉ được sửa điểm khi Giáo vụ đã mở một phiên điều chỉnh học bạ chính thức
     * cho đúng học kỳ, lớp và môn. Các sổ điểm lịch sử khác vẫn bất biến.
     */
    public void assertSemesterWritableOrOfficialRevision(String semesterId, String classId, String subjectId) {
        var semester = structure.getSemester(semesterId);
        var year = structure.getYear(semester.getAcademicYearId());
        if (!"CLOSED".equals(semester.getStatus()) && !"CLOSED".equals(year.getStatus())) return;
        Integer count = jdbc.queryForObject("""
                select count(*) from gradebook_locks l
                where l.semester_id=? and l.class_id=? and l.subject_id=? and l.locked=false
                  and exists (select 1 from gradebook_completion_audits a
                              where a.semester_id=l.semester_id and a.class_id=l.class_id
                                and a.subject_id=l.subject_id and a.action='OFFICIAL_REVISION_REOPENED')
                """, Integer.class, semesterId, classId, subjectId);
        if (count == null || count == 0) {
            throw ApiException.conflict("Học kỳ thuộc năm học đã khóa. Giáo vụ phải mở bản điều chỉnh học bạ chính thức trước khi sửa điểm.");
        }
    }

    public void assertWritable(String semesterId, String classId, String subjectId) {
        Integer count = jdbc.queryForObject("select count(*) from gradebook_locks where semester_id=? and class_id=? and subject_id=? and locked=true",
                Integer.class, semesterId, classId, subjectId);
        if (count != null && count > 0) {
            throw ApiException.conflict("Sổ điểm môn đã được giáo viên xác nhận hoàn tất. Vui lòng đề nghị Giáo vụ mở lại trước khi điều chỉnh.");
        }
    }

    public boolean isCompleted(String semesterId, String classId, String subjectId) {
        Integer count = jdbc.queryForObject("select count(*) from gradebook_locks where semester_id=? and class_id=? and subject_id=? and locked=true",
                Integer.class, semesterId, classId, subjectId);
        return count != null && count > 0;
    }

    public List<CompletionAudit> audits(String semesterId, String classId, String subjectId, CurrentUser actor) {
        status(semesterId, classId, subjectId, actor);
        return jdbc.query("select id,action,note,actor_id,created_at from gradebook_completion_audits where semester_id=? and class_id=? and subject_id=? order by created_at desc",
                (rs, rowNum) -> new CompletionAudit(rs.getString("id"), rs.getString("action"), rs.getString("note"),
                        rs.getString("actor_id"), rs.getTimestamp("created_at").toInstant().toString()), semesterId, classId, subjectId);
    }

    private CompletionView buildView(String semesterId, String classId, String subjectId) {
        String subjectName = structure.requireSubjectName(subjectId);
        List<UserDto> students = users.list("STUDENT", null, classId);
        Set<String> studentIds = students.stream().map(UserDto::id).collect(Collectors.toSet());
        List<Grade> subjectGrades = grades.findBySubjectIdAndSemesterId(subjectId, semesterId).stream()
                .filter(item -> studentIds.contains(item.getStudentId())).toList();
        List<String> missing = new ArrayList<>();
        for (UserDto student : students) {
            List<Grade> entries = subjectGrades.stream().filter(item -> student.id().equals(item.getStudentId())).toList();
            List<String> parts = new ArrayList<>();
            for (ExamCategory category : categories.findAll()) {
                Set<Integer> indexes = entries.stream().filter(item -> category.getCode().equals(item.getCategory()))
                        .filter(item -> item.getScore() != null)
                        .map(item -> item.getAssessmentIndex() == null ? 1 : item.getAssessmentIndex()).collect(Collectors.toSet());
                if (!java.util.stream.IntStream.rangeClosed(1, Math.max(1, category.getRequiredCount())).allMatch(indexes::contains)) {
                    parts.add(category.getName());
                }
            }
            if (!parts.isEmpty()) missing.add(student.fullName() + " thiếu " + String.join(", ", parts));
        }
        List<LockRow> locks = jdbc.query("select locked,changed_by,changed_at from gradebook_locks where semester_id=? and class_id=? and subject_id=?",
                (rs, rowNum) -> new LockRow(rs.getBoolean("locked"), rs.getString("changed_by"),
                        rs.getTimestamp("changed_at") == null ? null : rs.getTimestamp("changed_at").toInstant()), semesterId, classId, subjectId);
        LockRow lock = locks.stream().filter(LockRow::locked).findFirst().orElse(null);
        return new CompletionView(semesterId, classId, subjectId, subjectName, lock != null,
                lock == null ? null : lock.changedBy(), lock == null || lock.changedAt() == null ? null : lock.changedAt().toString(),
                students.size(), missing.size(), missing.stream().limit(20).toList());
    }

    private void assertAssignedTeacher(String teacherId, String semesterId, String classId, String subjectId) {
        boolean assigned = assignments.assignmentsOfClass(classId, semesterId).stream()
                .anyMatch(item -> teacherId.equals(item.getTeacherId()) && subjectId.equals(item.getSubjectId()));
        if (!assigned) throw ApiException.forbidden("Giáo viên chỉ được hoàn tất sổ điểm đúng môn và lớp được phân công");
    }

    private void audit(String semesterId, String classId, String subjectId, String action, String note, String actorId) {
        jdbc.update("insert into gradebook_completion_audits(id,semester_id,class_id,subject_id,action,note,actor_id,created_at) values (?,?,?,?,?,?,?,?)",
                Ids.gen("gradebook-audit"), semesterId, classId, subjectId, action, clean(note), actorId, Timestamp.from(Instant.now()));
    }

    private String clean(String value) { return value == null || value.isBlank() ? null : value.trim(); }
    private record LockRow(boolean locked, String changedBy, Instant changedAt) {}
}
