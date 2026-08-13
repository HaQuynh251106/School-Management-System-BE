package com.sse.app.academic.timetable;

import com.sse.app.academic.structure.Semester;
import com.sse.app.academic.structure.StructureService;
import com.sse.app.common.ApiException;
import com.sse.app.common.Ids;
import com.sse.app.security.CurrentUser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Service
public class TeachingProgressService {
    private final TeachingProgressRepository progress;
    private final TimetableService timetable;
    private final TeachingAssignmentService assignments;
    private final StructureService structure;
    private final ApplicationEventPublisher events;

    public TeachingProgressService(TeachingProgressRepository progress,
                                   TimetableService timetable,
                                   TeachingAssignmentService assignments,
                                   StructureService structure,
                                   ApplicationEventPublisher events) {
        this.progress = progress;
        this.timetable = timetable;
        this.assignments = assignments;
        this.structure = structure;
        this.events = events;
    }

    public List<TeachingProgress> list(String classId, String subjectId,
                                       String semesterId, CurrentUser actor) {
        if (semesterId == null || semesterId.isBlank()) {
            throw ApiException.badRequest("Vui lòng chọn học kỳ");
        }
        if (!actor.canManageAcademics() && !actor.isTeacher()) {
            throw ApiException.forbidden("Không có quyền xem tiến độ giảng dạy");
        }
        return progress.findBySemesterIdOrderByLessonDateDesc(semesterId).stream()
                .filter(item -> classId == null || classId.equals(item.getClassId()))
                .filter(item -> subjectId == null || subjectId.equals(item.getSubjectId()))
                .filter(item -> !actor.isTeacher() || actor.id().equals(item.getTeacherId()))
                .sorted(Comparator.comparing(TeachingProgress::getLessonDate).reversed()
                        .thenComparing(TeachingProgress::getClassCode))
                .toList();
    }

    @Transactional
    public TeachingProgress save(TeachingProgressDtos.SaveProgressRequest request, CurrentUser actor) {
        if (!actor.isTeacher()) {
            throw ApiException.forbidden("Chỉ giáo viên phụ trách được cập nhật tiến độ thực dạy");
        }
        TimetableSlot slot = timetable.findSlot(request.timetableSlotId());
        if (slot == null) throw ApiException.notFound("Tiết học");
        if (!actor.id().equals(slot.getTeacherId()) || !assignments.isAssigned(
                actor.id(), slot.getClassId(), slot.getSubjectId(), slot.getSemesterId())) {
            throw ApiException.forbidden("Tiết học không thuộc phân công của giáo viên");
        }
        Semester semester = structure.getSemester(slot.getSemesterId());
        if (request.lessonDate().isBefore(semester.getStartDate())
                || request.lessonDate().isAfter(semester.getEndDate())) {
            throw ApiException.badRequest("Ngày dạy phải nằm trong học kỳ");
        }
        String status = request.status().trim().toUpperCase(Locale.ROOT);
        String reason = clean(request.reason());
        if ("COMPLETED".equals(status) && request.completedPeriods() < 1) {
            throw ApiException.badRequest("Tiết đã dạy phải có ít nhất 1 tiết hoàn thành");
        }
        if ("CANCELLED".equals(status) && (reason == null || reason.length() < 5)) {
            throw ApiException.badRequest("Tiết hủy cần ghi lý do ít nhất 5 ký tự");
        }
        if (request.makeupDate() != null && !request.makeupDate().isAfter(request.lessonDate())) {
            throw ApiException.badRequest("Ngày học bù phải sau ngày nghỉ");
        }
        Instant now = Instant.now();
        TeachingProgress item = progress.findByTimetableSlotIdAndLessonDate(
                        slot.getId(), request.lessonDate())
                .orElseGet(() -> TeachingProgress.builder()
                        .id(Ids.gen("tp"))
                        .timetableSlotId(slot.getId())
                        .classId(slot.getClassId()).classCode(slot.getClassCode())
                        .subjectId(slot.getSubjectId()).subjectName(slot.getSubjectName())
                        .semesterId(slot.getSemesterId())
                        .teacherId(slot.getTeacherId()).teacherName(slot.getTeacherName())
                        .lessonDate(request.lessonDate()).createdAt(now).build());
        if ("APPROVED".equals(item.getMakeupStatus()) || "REJECTED".equals(item.getMakeupStatus())) {
            throw ApiException.conflict("Đề xuất học bù đã được duyệt; không thể sửa nhật ký gốc");
        }
        item.setCompletedPeriods("COMPLETED".equals(status) ? request.completedPeriods() : 0);
        item.setTopic(request.topic().trim());
        item.setStatus(status);
        item.setReason(reason);
        item.setMakeupDate(request.makeupDate());
        item.setMakeupStatus(request.makeupDate() == null ? "NONE" : "PROPOSED");
        item.setReviewNote(null);
        item.setReviewedBy(null);
        item.setReviewedAt(null);
        item.setUpdatedAt(now);
        TeachingProgress saved = progress.save(item);
        events.publishEvent(new TeachingProgressChangedEvent(saved.getId(), "UPSERTED"));
        return saved;
    }

    @Transactional
    public TeachingProgress review(String id, TeachingProgressDtos.ReviewMakeupRequest request,
                                   CurrentUser actor) {
        if (!actor.canManageAcademics()) {
            throw ApiException.forbidden("Chỉ Giáo vụ hoặc quản trị viên được duyệt lịch bù");
        }
        TeachingProgress item = progress.findById(id)
                .orElseThrow(() -> ApiException.notFound("Đề xuất học bù"));
        if (!"PROPOSED".equals(item.getMakeupStatus()) || item.getMakeupDate() == null) {
            throw ApiException.conflict("Nhật ký không có đề xuất học bù đang chờ duyệt");
        }
        item.setMakeupStatus(request.status().trim().toUpperCase(Locale.ROOT));
        item.setReviewNote(clean(request.reviewNote()));
        item.setReviewedBy(actor.id());
        item.setReviewedAt(Instant.now());
        item.setUpdatedAt(Instant.now());
        TeachingProgress saved = progress.save(item);
        events.publishEvent(new TeachingProgressChangedEvent(saved.getId(), "MAKEUP_REVIEWED"));
        return saved;
    }

    private String clean(String value) {
        if (value == null || value.isBlank()) return null;
        return value.trim();
    }
}
