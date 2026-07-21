package com.sse.app.academic.exam;

import com.sse.app.academic.exam.ExamDtos.*;
import com.sse.app.identity.UserService;
import com.sse.app.security.*;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController @RequiredArgsConstructor
public class ExamController {
    private final ExamService exams;
    private final ExamReportService reports;
    private final UserService users;

    @GetMapping("/exam-periods")
    public List<PeriodSummary> periods(@RequestParam(required = false) String academicYearId,
                                       @RequestParam(required = false) String semesterId) {
        CurrentUserHolder.require(); return exams.listPeriods(academicYearId, semesterId);
    }
    @PostMapping("/exam-periods") public ExamPeriod create(@Valid @RequestBody SavePeriodRequest r) {
        CurrentUserHolder.requireRole("ADMIN"); return exams.createPeriod(r, CurrentUserHolder.require().id());
    }
    @PutMapping("/exam-periods/{id}") public ExamPeriod update(@PathVariable String id, @Valid @RequestBody SavePeriodRequest r) {
        CurrentUserHolder.requireRole("ADMIN"); return exams.updatePeriod(id, r);
    }
    @DeleteMapping("/exam-periods/{id}") public void delete(@PathVariable String id) { CurrentUserHolder.requireRole("ADMIN"); exams.deletePeriod(id); }
    @PostMapping("/exam-periods/{id}/lock-scores") public ExamPeriod lock(@PathVariable String id) { CurrentUserHolder.requireRole("ADMIN"); return exams.setScoreLock(id, true, CurrentUserHolder.require().id()); }
    @PostMapping("/exam-periods/{id}/unlock-scores") public ExamPeriod unlock(@PathVariable String id) { CurrentUserHolder.requireRole("ADMIN"); return exams.setScoreLock(id, false, CurrentUserHolder.require().id()); }
    @PostMapping("/exam-periods/{id}/confirm") public ExamPeriod confirm(@PathVariable String id) { CurrentUserHolder.requireRole("ADMIN"); return exams.confirm(id, CurrentUserHolder.require().id()); }
    @PostMapping("/exam-periods/{id}/publish-schedule") public ExamPeriod publishSchedule(@PathVariable String id) { CurrentUserHolder.requireRole("ADMIN"); return exams.publishSchedule(id, CurrentUserHolder.require().id()); }

    @GetMapping("/me/exam-agenda") public List<ExamAgendaItem> agenda(@RequestParam(required = false) String childId) {
        CurrentUserHolder.requireRole("TEACHER", "STUDENT", "PARENT");
        CurrentUser me = CurrentUserHolder.require();
        return exams.agenda(me, childId);
    }
    @GetMapping("/me/exam-grading") public List<TeacherGradingTask> grading() {
        CurrentUserHolder.requireRole("TEACHER");
        return exams.gradingTasks(CurrentUserHolder.require().id());
    }
    @GetMapping("/me/exam-results") public List<StudentExamResultView> myExamResults() {
        CurrentUserHolder.requireRole("STUDENT");
        return exams.studentResults(CurrentUserHolder.require().id());
    }
    @GetMapping("/me/exam-reviews") public List<ExamReviewRequest> myExamReviews(@RequestParam(required = false) String status) {
        CurrentUserHolder.requireRole("TEACHER");
        return exams.teacherReviews(CurrentUserHolder.require().id(), status);
    }

    @GetMapping("/exam-periods/{id}/schedules") public List<ExamSchedule> schedules(@PathVariable String id) { CurrentUserHolder.requireRole("ADMIN", "TEACHER"); return exams.schedules(id); }
    @PostMapping("/exam-periods/{id}/schedules") public ExamSchedule createSchedule(@PathVariable String id, @Valid @RequestBody SaveScheduleRequest r) { CurrentUserHolder.requireRole("ADMIN"); return exams.createSchedule(id, r); }
    @PutMapping("/exam-schedules/{id}") public ExamSchedule updateSchedule(@PathVariable String id, @Valid @RequestBody SaveScheduleRequest r) { CurrentUserHolder.requireRole("ADMIN"); return exams.updateSchedule(id, r); }
    @DeleteMapping("/exam-schedules/{id}") public void deleteSchedule(@PathVariable String id) { CurrentUserHolder.requireRole("ADMIN"); exams.deleteSchedule(id); }

    @GetMapping("/exam-schedules/{id}/rooms") public List<ExamRoom> rooms(@PathVariable String id) { CurrentUserHolder.requireRole("ADMIN", "TEACHER"); return exams.rooms(id); }
    @PostMapping("/exam-schedules/{id}/rooms") public ExamRoom room(@PathVariable String id, @Valid @RequestBody SaveRoomRequest r) { CurrentUserHolder.requireRole("ADMIN"); return exams.saveRoom(id, r); }
    @DeleteMapping("/exam-rooms/{id}") public void deleteRoom(@PathVariable String id) { CurrentUserHolder.requireRole("ADMIN"); exams.deleteRoom(id); }
    @PostMapping("/exam-rooms/{id}/allocate") public List<ExamCandidate> allocate(@PathVariable String id, @Valid @RequestBody AllocateCandidatesRequest r) { CurrentUserHolder.requireRole("ADMIN"); return exams.allocate(id, r.classId()); }

    @GetMapping("/exam-periods/{id}/candidates") public List<ExamCandidate> candidates(@PathVariable String id, @RequestParam(required = false) String scheduleId, @RequestParam(required = false) String classId) { CurrentUserHolder.requireRole("ADMIN", "TEACHER"); return exams.candidates(id, scheduleId, classId); }
    @GetMapping("/exam-periods/{id}/results") public List<ExamResult> results(@PathVariable String id, @RequestParam(required = false) String scheduleId, @RequestParam(required = false) String studentId) {
        CurrentUser me = CurrentUserHolder.require();
        if (me.isStudent()) studentId = me.id();
        else if (me.isParent()) {
            if (studentId == null || studentId.isBlank()) throw com.sse.app.common.ApiException.badRequest("Cần chọn học sinh");
            users.assertParentOf(me.id(), studentId);
        }
        return exams.results(id, scheduleId, studentId);
    }
    @PutMapping("/exam-periods/{id}/results") public List<ExamResult> saveResults(@PathVariable String id, @Valid @RequestBody SaveResultsRequest r) { CurrentUserHolder.requireRole("TEACHER"); return exams.saveResults(id, r, CurrentUserHolder.require().id()); }

    @GetMapping("/exam-periods/{id}/reviews") public List<ExamReviewRequest> reviews(@PathVariable String id, @RequestParam(required = false) String status) { CurrentUserHolder.requireRole("TEACHER"); return exams.reviews(id, status, CurrentUserHolder.require().id()); }
    @PostMapping("/exam-periods/{id}/reviews") public ExamReviewRequest requestReview(@PathVariable String id, @Valid @RequestBody CreateReviewRequest r) { CurrentUserHolder.requireRole("STUDENT"); return exams.requestReview(id, r, CurrentUserHolder.require()); }
    @PutMapping("/exam-reviews/{id}/resolve") public ExamReviewRequest resolve(@PathVariable String id, @Valid @RequestBody ResolveReviewRequest r) { CurrentUserHolder.requireRole("TEACHER"); return exams.resolveReview(id, r, CurrentUserHolder.require().id()); }
    @GetMapping("/exam-periods/{id}/adjustments") public List<ExamScoreAdjustment> adjustments(@PathVariable String id) { CurrentUserHolder.requireRole("ADMIN", "TEACHER"); return exams.adjustments(id); }

    @GetMapping(value = "/exam-reports/score-slip", produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> scoreSlip(@RequestParam String examPeriodId, @RequestParam String studentId) {
        assertStudentAccess(studentId); return file(reports.scoreSlip(examPeriodId, studentId), "phieu-diem.pdf", MediaType.APPLICATION_PDF);
    }
    @GetMapping(value = "/exam-reports/report-card", produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> reportCard(@RequestParam String academicYearId, @RequestParam String studentId) {
        assertStudentAccess(studentId); return file(reports.reportCard(academicYearId, studentId), "hoc-ba.pdf", MediaType.APPLICATION_PDF);
    }
    @GetMapping("/exam-reports/export")
    public ResponseEntity<byte[]> export(@RequestParam String examPeriodId, @RequestParam(defaultValue = "YEAR") String scope,
            @RequestParam(required = false) String classId, @RequestParam(required = false) String gradeLevel,
            @RequestParam(defaultValue = "pdf") String format) {
        CurrentUserHolder.requireRole("ADMIN", "TEACHER");
        if ("csv".equalsIgnoreCase(format)) return file(reports.exportCsv(examPeriodId, scope, classId, gradeLevel), "bang-diem.csv", MediaType.parseMediaType("text/csv; charset=UTF-8"));
        return file(reports.exportPdf(examPeriodId, scope, classId, gradeLevel), "bang-diem.pdf", MediaType.APPLICATION_PDF);
    }

    private void assertStudentAccess(String studentId) {
        CurrentUser me = CurrentUserHolder.require();
        if (me.isStudent() && !me.id().equals(studentId)) throw com.sse.app.common.ApiException.forbidden("Không được xem hồ sơ của học sinh khác");
        if (me.isParent()) users.assertParentOf(me.id(), studentId);
    }
    private ResponseEntity<byte[]> file(byte[] body, String name, MediaType type) {
        return ResponseEntity.ok().contentType(type).header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + name + "\"").body(body);
    }
}
