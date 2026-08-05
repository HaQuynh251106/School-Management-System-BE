package com.sse.app.academic.timetable;

import com.sse.app.academic.planning.AcademicCurriculumItem;
import com.sse.app.academic.timetable.TimetableDtos.LessonProgressRequest;
import com.sse.app.academic.timetable.TimetableDtos.MakeupProposalRequest;
import com.sse.app.academic.timetable.TimetableDtos.ProgressComparison;
import com.sse.app.academic.timetable.TimetableDtos.ReviewMakeupRequest;
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

import java.util.List;

@RestController
@RequestMapping("/academic/progress")
public class TimetableProgressController {
    private final LessonProgressService service;

    public TimetableProgressController(LessonProgressService service) {
        this.service = service;
    }

    @GetMapping("/curriculum")
    public List<AcademicCurriculumItem> curriculum(
            @RequestParam String classId,
            @RequestParam String semesterId,
            @RequestParam String subjectId) {
        CurrentUser actor = CurrentUserHolder.require();
        CurrentUserHolder.requirePermission("ACADEMIC_PROGRESS_READ");
        return service.curriculum(classId, semesterId, subjectId, actor);
    }

    @GetMapping("/class")
    public List<ClassLessonProgress> classProgress(
            @RequestParam String classId,
            @RequestParam String semesterId) {
        CurrentUser actor = CurrentUserHolder.require();
        CurrentUserHolder.requirePermission("ACADEMIC_PROGRESS_READ");
        return service.classProgress(classId, semesterId, actor);
    }

    @PostMapping
    public ClassLessonProgress save(
            @Valid @RequestBody LessonProgressRequest request) {
        CurrentUser actor = CurrentUserHolder.require();
        CurrentUserHolder.requirePermission("ACADEMIC_PROGRESS_UPDATE");
        return service.save(request, actor);
    }

    @GetMapping("/comparison")
    public ProgressComparison compare(
            @RequestParam String academicYearId,
            @RequestParam String semesterId,
            @RequestParam String gradeLevel,
            @RequestParam String subjectId) {
        CurrentUserHolder.requirePermission("ACADEMIC_PROGRESS_READ");
        return service.compare(academicYearId, semesterId, gradeLevel, subjectId);
    }

    @PostMapping("/schedules/{scheduleId}/makeup/generate")
    public List<TimetableMakeupProposal> generateMakeup(
            @PathVariable String scheduleId,
            @Valid @RequestBody MakeupProposalRequest request) {
        CurrentUserHolder.requirePermission("ACADEMIC_TIMETABLE_MANAGE");
        return service.generateMakeup(scheduleId, request);
    }

    @GetMapping("/schedules/{scheduleId}/makeup")
    public List<TimetableMakeupProposal> makeup(
            @PathVariable String scheduleId) {
        CurrentUserHolder.requirePermission("ACADEMIC_PROGRESS_READ");
        return service.listMakeup(scheduleId);
    }

    @PutMapping("/makeup/{id}")
    public TimetableMakeupProposal review(
            @PathVariable String id,
            @Valid @RequestBody ReviewMakeupRequest request) {
        CurrentUser actor = CurrentUserHolder.require();
        CurrentUserHolder.requirePermission("ACADEMIC_TIMETABLE_PUBLISH");
        return service.reviewMakeup(id, request.status(), request.reason(), actor.id());
    }
}
