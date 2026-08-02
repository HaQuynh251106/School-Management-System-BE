package com.sse.app.academic.grade;

import com.sse.app.security.CurrentUserHolder;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;

import static com.sse.app.academic.grade.GradebookCompletionDtos.*;

@RestController
@RequestMapping("/gradebook-completions")
public class GradebookCompletionController {
    private final GradebookCompletionService service;
    public GradebookCompletionController(GradebookCompletionService service) { this.service = service; }

    @GetMapping
    public CompletionView status(@RequestParam String semesterId, @RequestParam String classId,
                                 @RequestParam String subjectId) {
        return service.status(semesterId, classId, subjectId, CurrentUserHolder.require());
    }

    @PutMapping
    public CompletionView complete(@RequestParam String semesterId, @RequestParam String classId,
                                   @RequestParam String subjectId, @RequestBody(required = false) CompletionRequest request) {
        return service.complete(semesterId, classId, subjectId, request, CurrentUserHolder.require());
    }

    @PutMapping("/reopen")
    public CompletionView reopen(@RequestParam String semesterId, @RequestParam String classId,
                                 @RequestParam String subjectId, @Valid @RequestBody ReopenRequest request) {
        return service.reopen(semesterId, classId, subjectId, request, CurrentUserHolder.require());
    }

    @GetMapping("/audits")
    public List<CompletionAudit> audits(@RequestParam String semesterId, @RequestParam String classId,
                                       @RequestParam String subjectId) {
        return service.audits(semesterId, classId, subjectId, CurrentUserHolder.require());
    }
}
