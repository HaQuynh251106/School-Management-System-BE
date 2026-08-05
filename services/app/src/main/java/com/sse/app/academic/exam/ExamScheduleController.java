package com.sse.app.academic.exam;

import com.sse.app.academic.exam.ExamDtos.AutoGenerateRequest;
import com.sse.app.academic.exam.ExamDtos.ExamPeriodRequest;
import com.sse.app.academic.exam.ExamDtos.ExamPeriodResponse;
import com.sse.app.academic.exam.ExamDtos.ExamRoomResponse;
import com.sse.app.academic.exam.ExamDtos.ExamSessionResponse;
import com.sse.app.academic.exam.ExamDtos.ExamValidationResponse;
import com.sse.app.academic.exam.ExamDtos.ExamVersionDetail;
import com.sse.app.academic.exam.ExamDtos.ExamVersionResponse;
import com.sse.app.academic.exam.ExamDtos.NewVersionRequest;
import com.sse.app.academic.exam.ExamDtos.PublishedExamView;
import com.sse.app.academic.exam.ExamDtos.RecallVersionRequest;
import com.sse.app.academic.exam.ExamDtos.RoomAssignmentRequest;
import com.sse.app.academic.exam.ExamDtos.SessionRequest;
import com.sse.app.academic.exam.ExamDtos.TeacherUnavailabilityRequest;
import com.sse.app.academic.exam.ExamDtos.TeacherUnavailabilityResponse;
import com.sse.app.audit.AuditService;
import com.sse.app.academic.planning.ExamAssessmentSourceService.SourceReadiness;
import com.sse.app.identity.UserRepository;
import com.sse.app.identity.UserService;
import com.sse.app.security.CurrentUser;
import com.sse.app.security.CurrentUserHolder;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/exam-periods")
public class ExamScheduleController {
    private final ExamScheduleService exams;
    private final AuditService audit;
    private final UserService userService;
    private final UserRepository users;

    public ExamScheduleController(
            ExamScheduleService exams, AuditService audit,
            UserService userService, UserRepository users) {
        this.exams = exams;
        this.audit = audit;
        this.userService = userService;
        this.users = users;
    }

    @GetMapping
    public List<ExamPeriodResponse> list(@RequestParam(required = false) String academicYearId) {
        CurrentUserHolder.requirePermission("ACADEMIC_EXAM_SCHEDULE_READ");
        return exams.listPeriods(academicYearId);
    }

    @GetMapping("/assessment-sources")
    public SourceReadiness assessmentSources(
            @RequestParam String academicYearId,
            @RequestParam String semesterId,
            @RequestParam String examType,
            @RequestParam List<String> gradeLevels) {
        requireManager();
        return exams.assessmentSourceReadiness(
                academicYearId, semesterId, examType, gradeLevels);
    }

    @GetMapping("/{id}")
    public ExamPeriodResponse get(@PathVariable String id) {
        CurrentUserHolder.requirePermission("ACADEMIC_EXAM_SCHEDULE_READ");
        return exams.getPeriodResponse(id);
    }

    @PostMapping
    public ExamPeriodResponse create(@Valid @RequestBody ExamPeriodRequest request) {
        CurrentUser actor = requireManager();
        ExamPeriodResponse result = exams.createPeriod(request, actor.id());
        record(actor, "CREATE", "exam_period", result.id(), result.code() + " · " + result.name());
        return result;
    }

    @PutMapping("/{id}")
    public ExamPeriodResponse update(@PathVariable String id, @Valid @RequestBody ExamPeriodRequest request) {
        CurrentUser actor = requireManager();
        ExamPeriodResponse result = exams.updatePeriod(id, request);
        record(actor, "UPDATE", "exam_period", id, result.code() + " · " + result.name());
        return result;
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable String id) {
        CurrentUser actor = requireManager();
        exams.deletePeriod(id);
        record(actor, "DELETE", "exam_period", id, "Xóa đợt thi và toàn bộ lịch liên quan");
    }

    @PostMapping("/{id}/status")
    public ExamPeriodResponse status(@PathVariable String id, @RequestBody Map<String, String> body) {
        CurrentUser actor = requireManager();
        ExamPeriodResponse result = exams.changePeriodStatus(id, body.get("status"));
        record(actor, result.status(), "exam_period", id, body.getOrDefault("reason", result.status()));
        return result;
    }

    @GetMapping("/{id}/versions")
    public List<ExamVersionResponse> versions(@PathVariable String id) {
        requireManager();
        return exams.listVersions(id);
    }

    @PostMapping("/{id}/versions")
    public ExamVersionResponse createVersion(
            @PathVariable String id, @Valid @RequestBody NewVersionRequest request) {
        CurrentUser actor = requireManager();
        ExamVersionResponse result = exams.createVersion(id, request.reason(), actor.id());
        record(actor, "CREATE_VERSION", "exam_schedule_version", result.id(), request.reason());
        return result;
    }

    @GetMapping("/{periodId}/versions/{versionId}")
    public ExamVersionDetail detail(@PathVariable String periodId, @PathVariable String versionId) {
        requireManager();
        ExamVersionDetail result = exams.detail(versionId);
        if (!periodId.equals(result.period().id())) throw com.sse.app.common.ApiException.notFound("Phiên bản lịch thi");
        return result;
    }

    @PostMapping("/{periodId}/versions/{versionId}/generate")
    public ExamVersionDetail generate(
            @PathVariable String periodId, @PathVariable String versionId,
            @Valid @RequestBody AutoGenerateRequest request) {
        CurrentUser actor = requireManager();
        ExamVersionDetail result = exams.generate(versionId, request);
        if (!periodId.equals(result.period().id())) throw com.sse.app.common.ApiException.notFound("Phiên bản lịch thi");
        record(actor, "AUTO_GENERATE", "exam_schedule_version", versionId,
                result.validation().sessionCount() + " môn/khối · " + result.validation().roomCount() + " phòng");
        return result;
    }

    @GetMapping("/{periodId}/versions/{versionId}/validate")
    public ExamValidationResponse validate(@PathVariable String periodId, @PathVariable String versionId) {
        requireManager();
        return exams.validateAndMark(versionId);
    }

    @PostMapping("/{periodId}/versions/{versionId}/publish")
    public ExamVersionDetail publish(@PathVariable String periodId, @PathVariable String versionId) {
        CurrentUser actor = requireManager();
        ExamVersionDetail result = exams.publish(versionId, actor.id());
        if (!periodId.equals(result.period().id())) throw com.sse.app.common.ApiException.notFound("Phiên bản lịch thi");
        record(actor, "PUBLISH", "exam_schedule_version", versionId,
                result.period().name() + " · phiên bản " + result.version().versionNo());
        return result;
    }

    @PostMapping("/{periodId}/recall")
    public ExamVersionDetail recall(
            @PathVariable String periodId, @Valid @RequestBody RecallVersionRequest request) {
        CurrentUser actor = requireManager();
        ExamVersionDetail result = exams.recallPublished(periodId, request.reason(), actor.id());
        record(actor, "RECALL", "exam_schedule_version", result.version().id(),
                request.reason() + " · tạo bản nháp " + result.version().versionNo());
        return result;
    }

    @PostMapping("/{periodId}/versions/{versionId}/sessions")
    public ExamSessionResponse addSession(
            @PathVariable String periodId, @PathVariable String versionId,
            @Valid @RequestBody SessionRequest request) {
        CurrentUser actor = requireManager();
        ExamSessionResponse result = exams.addSession(versionId, request);
        record(actor, "CREATE", "exam_session", result.id(), result.subjectName() + " · " + result.gradeLevel());
        return result;
    }

    @PutMapping("/{periodId}/versions/{versionId}/sessions/{sessionId}")
    public ExamSessionResponse updateSession(
            @PathVariable String periodId, @PathVariable String versionId,
            @PathVariable String sessionId, @Valid @RequestBody SessionRequest request) {
        CurrentUser actor = requireManager();
        ExamSessionResponse result = exams.updateSession(versionId, sessionId, request);
        record(actor, "UPDATE", "exam_session", sessionId,
                result.examDate() + " " + result.startTime() + " · " + result.subjectName());
        return result;
    }

    @DeleteMapping("/{periodId}/versions/{versionId}/sessions/{sessionId}")
    public void deleteSession(
            @PathVariable String periodId, @PathVariable String versionId,
            @PathVariable String sessionId) {
        CurrentUser actor = requireManager();
        exams.deleteSession(versionId, sessionId);
        record(actor, "DELETE", "exam_session", sessionId, "Xóa môn thi khỏi bản nháp");
    }

    @PutMapping("/{periodId}/versions/{versionId}/rooms/{assignmentId}")
    public ExamRoomResponse updateRoom(
            @PathVariable String periodId, @PathVariable String versionId,
            @PathVariable String assignmentId, @Valid @RequestBody RoomAssignmentRequest request) {
        CurrentUser actor = requireManager();
        ExamRoomResponse result = exams.updateRoom(versionId, assignmentId, request);
        record(actor, "UPDATE", "exam_room_assignment", assignmentId,
                result.roomCode() + " · " + result.primaryProctorName() + " / " + result.backupProctorName());
        return result;
    }

    @PostMapping("/{periodId}/teacher-unavailability")
    public TeacherUnavailabilityResponse addUnavailability(
            @PathVariable String periodId,
            @Valid @RequestBody TeacherUnavailabilityRequest request) {
        CurrentUser actor = requireManager();
        TeacherUnavailabilityResponse result = exams.addUnavailability(periodId, request, actor.id());
        record(actor, "CREATE", "exam_teacher_unavailability", result.id(),
                result.teacherName() + " · " + result.unavailableDate() + " đến "
                        + result.endDate() + " · " + result.reason());
        return result;
    }

    @DeleteMapping("/{periodId}/teacher-unavailability/{id}")
    public void deleteUnavailability(@PathVariable String periodId, @PathVariable String id) {
        CurrentUser actor = requireManager();
        exams.deleteUnavailability(periodId, id);
        record(actor, "DELETE", "exam_teacher_unavailability", id, "Xóa lịch bận của giáo viên");
    }

    @PutMapping("/{periodId}/teacher-unavailability/{id}")
    public TeacherUnavailabilityResponse updateUnavailability(
            @PathVariable String periodId, @PathVariable String id,
            @Valid @RequestBody TeacherUnavailabilityRequest request) {
        CurrentUser actor = requireManager();
        TeacherUnavailabilityResponse result = exams.updateUnavailability(periodId, id, request);
        record(actor, "UPDATE", "exam_teacher_unavailability", id,
                result.teacherName() + " · " + result.unavailableDate() + " đến "
                        + result.endDate() + " · " + result.reason());
        return result;
    }

    @GetMapping("/me/schedule")
    public List<PublishedExamView> mySchedule() {
        CurrentUser actor = CurrentUserHolder.require();
        CurrentUserHolder.requirePermission("ACADEMIC_EXAM_SCHEDULE_READ");
        if (actor.isStudent()) return exams.studentExams(actor.id());
        if (actor.isTeacher()) return exams.teacherExams(actor.id());
        throw com.sse.app.common.ApiException.forbidden("Vai trò này cần dùng lịch thi của học sinh được chọn");
    }

    @GetMapping("/students/{studentId}/schedule")
    public List<PublishedExamView> studentSchedule(@PathVariable String studentId) {
        CurrentUser actor = CurrentUserHolder.require();
        CurrentUserHolder.requirePermission("ACADEMIC_EXAM_SCHEDULE_READ");
        if (actor.isParent()) userService.assertParentOf(actor.id(), studentId);
        else if (!actor.isAdmin() && !actor.id().equals(studentId)) {
            throw com.sse.app.common.ApiException.forbidden("Không được xem lịch thi của học sinh này");
        }
        return exams.studentExams(studentId);
    }

    private CurrentUser requireManager() {
        CurrentUserHolder.requirePermission("ACADEMIC_EXAM_SCHEDULE_MANAGE");
        return CurrentUserHolder.require();
    }

    private void record(CurrentUser actor, String action, String entityType, String entityId, String detail) {
        audit.record(actor.id(), users.findById(actor.id()).map(user -> user.getFullName()).orElse(actor.username()),
                actor.role(), action, "academic", entityType, entityId, detail);
    }
}
