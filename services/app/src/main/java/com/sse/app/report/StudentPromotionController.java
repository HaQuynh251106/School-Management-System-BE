package com.sse.app.report;

import com.sse.app.report.StudentPromotionDtos.ExecutePromotionRequest;
import com.sse.app.report.StudentPromotionDtos.PromotionExecutionResponse;
import com.sse.app.report.StudentPromotionDtos.PromotionPlanRequest;
import com.sse.app.report.StudentPromotionDtos.PromotionPreviewResponse;
import com.sse.app.report.StudentPromotionDtos.PromotionUndoResponse;
import com.sse.app.report.StudentPromotionDtos.UndoPromotionRequest;
import com.sse.app.security.CurrentUserHolder;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/student-promotions")
public class StudentPromotionController {
    private final StudentPromotionService promotions;

    public StudentPromotionController(StudentPromotionService promotions) {
        this.promotions = promotions;
    }

    @PostMapping("/preview")
    public PromotionPreviewResponse preview(@Valid @RequestBody PromotionPlanRequest request) {
        CurrentUserHolder.requireRole("ADMIN");
        return promotions.preview(request);
    }

    @PostMapping("/execute")
    public PromotionExecutionResponse execute(
            @Valid @RequestBody ExecutePromotionRequest request) {
        CurrentUserHolder.requireRole("ADMIN");
        return promotions.execute(request, CurrentUserHolder.require());
    }

    @PostMapping("/undo")
    public PromotionUndoResponse undo(@Valid @RequestBody UndoPromotionRequest request) {
        CurrentUserHolder.requireRole("ADMIN");
        return promotions.undo(request, CurrentUserHolder.require());
    }

    @PostMapping("/progression-status")
    public StudentPromotionDtos.ProgressionStatusResponse updateProgressionStatus(
            @Valid @RequestBody
            StudentPromotionDtos.UpdateProgressionStatusRequest request) {
        CurrentUserHolder.requireRole("ADMIN");
        return promotions.updateProgressionStatus(
                request, CurrentUserHolder.require());
    }

    @GetMapping("/enrollments")
    public List<StudentClassEnrollment> enrollments(
            @RequestParam String academicYearId,
            @RequestParam String classId) {
        CurrentUserHolder.requireRole("ADMIN");
        return promotions.listEnrollments(academicYearId, classId);
    }
}
