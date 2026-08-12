package com.sse.app.academic.homeroom;

import com.sse.app.academic.structure.SchoolClass;
import com.sse.app.academic.structure.Semester;
import com.sse.app.academic.structure.StructureService;
import com.sse.app.common.ApiException;
import com.sse.app.common.Ids;
import com.sse.app.event.DomainEventPublisher;
import com.sse.app.identity.UserDto;
import com.sse.app.identity.UserService;
import com.sse.app.security.CurrentUser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

import static com.sse.app.academic.homeroom.HomeroomRemarkDtos.*;

@Service
public class HomeroomRemarkService {
    private final HomeroomRemarkRepository remarks;
    private final UserService users;
    private final StructureService structure;
    private final DomainEventPublisher events;

    public HomeroomRemarkService(HomeroomRemarkRepository remarks, UserService users,
                                 StructureService structure, DomainEventPublisher events) {
        this.remarks = remarks;
        this.users = users;
        this.structure = structure;
        this.events = events;
    }

    public List<RemarkResponse> list(CurrentUser actor, String studentId) {
        UserDto student = requireStudent(studentId);
        SchoolClass schoolClass = requireStudentClass(student);
        boolean staffView = canManage(actor, schoolClass);
        if (!staffView) assertStudentOrParent(actor, studentId);
        return remarks.findByStudentIdOrderByUpdatedAtDesc(studentId).stream()
                .filter(row -> staffView || "PUBLISHED".equals(row.getStatus()))
                .map(row -> response(row, student, schoolClass)).toList();
    }

    @Transactional
    public RemarkResponse save(CurrentUser actor, String studentId, SaveRemarkRequest request) {
        UserDto student = requireStudent(studentId);
        SchoolClass schoolClass = requireStudentClass(student);
        if (!canManage(actor, schoolClass)) {
            throw ApiException.forbidden("Chỉ GVCN của lớp hoặc quản trị viên được ghi nhận xét");
        }
        Semester semester = structure.getSemester(request.semesterId());
        if (!schoolClass.getAcademicYearId().equals(semester.getAcademicYearId())) {
            throw ApiException.badRequest("Học kỳ không thuộc năm học của lớp hiện tại");
        }
        Instant now = Instant.now();
        HomeroomRemark row = remarks.findByStudentIdAndSemesterId(studentId, semester.getId())
                .orElseGet(() -> HomeroomRemark.builder()
                        .id(Ids.gen("hrm"))
                        .studentId(studentId)
                        .classId(schoolClass.getId())
                        .academicYearId(schoolClass.getAcademicYearId())
                        .semesterId(semester.getId())
                        .teacherId(actor.id())
                        .createdAt(now)
                        .build());
        row.setClassId(schoolClass.getId());
        row.setAcademicYearId(schoolClass.getAcademicYearId());
        row.setTeacherId(actor.id());
        row.setBody(request.body().trim());
        row.setStatus(request.publish() ? "PUBLISHED" : "DRAFT");
        row.setPublishedAt(request.publish() ? now : null);
        row.setUpdatedAt(now);
        HomeroomRemark saved = remarks.save(row);
        if (request.publish()) {
            events.publish("academic.homeroom_remark.published", actor.id(),
                    "homeroom_remark", saved.getId(), java.util.Map.of(
                            "studentId", studentId,
                            "message", "GVCN đã công bố nhận xét " + semester.getName() + "."));
        }
        return response(saved, student, schoolClass);
    }

    private UserDto requireStudent(String studentId) {
        UserDto student = users.dtoById(studentId);
        if (!"STUDENT".equals(student.role())) throw ApiException.badRequest("Tài khoản không phải học sinh");
        return student;
    }

    private SchoolClass requireStudentClass(UserDto student) {
        if (student.classId() == null || student.classId().isBlank()) {
            throw ApiException.badRequest("Học sinh chưa được phân lớp");
        }
        return structure.getClass(student.classId());
    }

    private boolean canManage(CurrentUser actor, SchoolClass schoolClass) {
        return actor.isAdmin() || (actor.isTeacher()
                && actor.id().equals(schoolClass.getHomeroomTeacherId()));
    }

    private void assertStudentOrParent(CurrentUser actor, String studentId) {
        if (actor.isStudent() && actor.id().equals(studentId)) return;
        if (actor.isParent()) {
            users.assertParentOf(actor.id(), studentId);
            return;
        }
        throw ApiException.forbidden("Không có quyền xem nhận xét của học sinh này");
    }

    private RemarkResponse response(HomeroomRemark row, UserDto student, SchoolClass schoolClass) {
        Semester semester = structure.getSemester(row.getSemesterId());
        return new RemarkResponse(row.getId(), row.getStudentId(), student.fullName(),
                row.getClassId(), schoolClass.getCode(), row.getAcademicYearId(),
                row.getSemesterId(), semester.getName(), row.getTeacherId(),
                users.fullNameOf(row.getTeacherId()), row.getBody(), row.getStatus(),
                row.getPublishedAt(), row.getUpdatedAt());
    }
}
