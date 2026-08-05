package com.sse.app.report;

import com.sse.app.academic.structure.AcademicYear;
import com.sse.app.report.YearReviewDtos.ChangeAcademicYearStatusRequest;
import com.sse.app.report.YearReviewDtos.FinalizeYearReviewRequest;
import com.sse.app.report.YearReviewDtos.PromotionPolicy;
import com.sse.app.report.YearReviewDtos.ReopenYearReviewRequest;
import com.sse.app.report.YearReviewDtos.SaveYearDecisionRequest;
import com.sse.app.report.YearReviewDtos.UpdatePromotionPolicyRequest;
import com.sse.app.report.YearReviewDtos.YearReviewResponse;
import com.sse.app.security.CurrentUser;
import com.sse.app.security.CurrentUserHolder;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/academic-year-summaries")
public class YearReviewController {
    private final YearReviewService reviews;

    public YearReviewController(YearReviewService reviews) {
        this.reviews = reviews;
    }

    @GetMapping("/preview")
    public YearReviewResponse preview(@RequestParam String academicYearId,
                                      @RequestParam String classId) {
        CurrentUserHolder.requireRole("ADMIN", "TEACHER");
        return reviews.review(academicYearId, classId, CurrentUserHolder.require());
    }

    @PutMapping("/{academicYearId}/classes/{classId}/students/{studentId}")
    public YearReviewResponse saveDecision(@PathVariable String academicYearId,
                                           @PathVariable String classId,
                                           @PathVariable String studentId,
                                           @Valid @RequestBody SaveYearDecisionRequest request) {
        CurrentUserHolder.requireRole("ADMIN", "TEACHER");
        CurrentUser actor = CurrentUserHolder.require();
        return reviews.saveDecision(academicYearId, classId, studentId,
                request.result(), request.conductGrade(), request.reason(), actor);
    }

    @PostMapping("/{academicYearId}/classes/{classId}/finalize")
    public YearReviewResponse finalizeClass(@PathVariable String academicYearId,
                                            @PathVariable String classId,
                                            @RequestBody FinalizeYearReviewRequest request) {
        CurrentUserHolder.requireRole("ADMIN");
        return reviews.finalizeClass(academicYearId, classId,
                request.confirmed(), CurrentUserHolder.require());
    }

    @PostMapping("/{academicYearId}/classes/{classId}/reopen")
    public YearReviewResponse reopenClass(@PathVariable String academicYearId,
                                          @PathVariable String classId,
                                          @Valid @RequestBody ReopenYearReviewRequest request) {
        CurrentUserHolder.requireRole("ADMIN");
        return reviews.reopenClass(academicYearId, classId, request.reason(),
                request.confirmed(), CurrentUserHolder.require());
    }

    @PutMapping("/{academicYearId}/status")
    public AcademicYear changeYearStatus(@PathVariable String academicYearId,
                                         @Valid @RequestBody ChangeAcademicYearStatusRequest request) {
        CurrentUserHolder.requireRole("ADMIN");
        return reviews.changeYearStatus(academicYearId, request.status(), request.reason(),
                request.confirmed(), CurrentUserHolder.require());
    }

    @GetMapping("/{academicYearId}/policy")
    public PromotionPolicy getPolicy(@PathVariable String academicYearId) {
        CurrentUserHolder.requireRole("ADMIN", "TEACHER");
        return reviews.getPolicy(academicYearId);
    }

    @PutMapping("/{academicYearId}/policy")
    public PromotionPolicy updatePolicy(@PathVariable String academicYearId,
                                        @RequestBody UpdatePromotionPolicyRequest request) {
        CurrentUserHolder.requireRole("ADMIN");
        return reviews.updatePolicy(academicYearId, request, CurrentUserHolder.require());
    }
}
