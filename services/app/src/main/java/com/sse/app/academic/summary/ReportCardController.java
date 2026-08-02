package com.sse.app.academic.summary;

import com.sse.app.security.CurrentUserHolder;
import com.sse.app.common.PageResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;

import static com.sse.app.academic.summary.ReportCardDtos.*;

@RestController
@RequestMapping("/report-cards")
public class ReportCardController {
    private final ReportCardWorkflowService service;
    public ReportCardController(ReportCardWorkflowService service) { this.service = service; }

    @GetMapping
    public List<ReportCardListItem> list(@RequestParam String academicYearId,
                                        @RequestParam(required = false) String classId,
                                        @RequestParam(required = false) String gradeLevel,
                                        @RequestParam(required = false) String status,
                                        @RequestParam(required = false) String q) {
        return service.list(academicYearId, classId, gradeLevel, status, q, CurrentUserHolder.require());
    }

    @GetMapping("/overview")
    public ReportCardScopeOverview overview(@RequestParam String academicYearId,
                                            @RequestParam(required = false) String cohortId) {
        return service.overview(academicYearId, cohortId, CurrentUserHolder.require());
    }

    @GetMapping("/classes")
    public List<ReportCardClassSummary> classes(@RequestParam String academicYearId,
                                                @RequestParam(required = false) String cohortId) {
        return service.classSummaries(academicYearId, cohortId, CurrentUserHolder.require());
    }

    @GetMapping("/students")
    public PageResponse<ReportCardListItem> students(@RequestParam String academicYearId,
                                                     @RequestParam String classId,
                                                     @RequestParam(required = false) String status,
                                                     @RequestParam(required = false) String q,
                                                     @RequestParam(defaultValue = "0") int page,
                                                     @RequestParam(defaultValue = "10") int size) {
        return service.studentPage(academicYearId, classId, status, q, page, size, CurrentUserHolder.require());
    }

    @GetMapping("/{studentId}")
    public ReportCardView view(@PathVariable String studentId, @RequestParam String academicYearId) {
        return service.view(academicYearId, studentId, CurrentUserHolder.require());
    }

    @PutMapping("/{studentId}/homeroom")
    public ReportCardView updateHomeroom(@PathVariable String studentId, @RequestParam String academicYearId,
                                         @Valid @RequestBody HomeroomUpdateRequest request) {
        return service.updateHomeroom(academicYearId, studentId, request, CurrentUserHolder.require());
    }

    @PostMapping("/{studentId}/submit")
    public ReportCardView submit(@PathVariable String studentId, @RequestParam String academicYearId,
                                 @RequestBody(required = false) TransitionRequest request) {
        return service.submit(academicYearId, studentId, request, CurrentUserHolder.require());
    }

    @PostMapping("/{studentId}/approve")
    public ReportCardView approve(@PathVariable String studentId, @RequestParam String academicYearId,
                                  @RequestBody(required = false) TransitionRequest request) {
        return service.approve(academicYearId, studentId, request, CurrentUserHolder.require());
    }

    @PostMapping("/{studentId}/lock")
    public ReportCardView lock(@PathVariable String studentId, @RequestParam String academicYearId,
                               @RequestBody(required = false) TransitionRequest request) {
        return service.lock(academicYearId, studentId, request, CurrentUserHolder.require());
    }

    @PostMapping("/{studentId}/publish")
    public ReportCardView publish(@PathVariable String studentId, @RequestParam String academicYearId,
                                  @RequestBody(required = false) TransitionRequest request) {
        return service.publish(academicYearId, studentId, request, CurrentUserHolder.require());
    }

    @PostMapping("/{studentId}/reopen")
    public ReportCardView reopen(@PathVariable String studentId, @RequestParam String academicYearId,
                                 @Valid @RequestBody ReopenRequest request) {
        return service.reopen(academicYearId, studentId, request, CurrentUserHolder.require());
    }
}
