package com.sse.app.academic.timetable;

import com.sse.app.security.CurrentUserHolder;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** Contract duy nhất cho F06/F07; Web và Mobile phải dùng chung resource này. */
@RestController
@RequestMapping("/teaching-progress")
public class TeachingProgressController {
    private final TeachingProgressService service;

    public TeachingProgressController(TeachingProgressService service) {
        this.service = service;
    }

    @GetMapping
    public List<TeachingProgress> list(@RequestParam(required = false) String classId,
                                       @RequestParam(required = false) String subjectId,
                                       @RequestParam String semesterId) {
        return service.list(classId, subjectId, semesterId, CurrentUserHolder.require());
    }

    @PutMapping
    public TeachingProgress save(@Valid @RequestBody TeachingProgressDtos.SaveProgressRequest request) {
        return service.save(request, CurrentUserHolder.require());
    }

    @PutMapping("/{id}/makeup")
    public TeachingProgress review(@PathVariable String id,
            @Valid @RequestBody TeachingProgressDtos.ReviewMakeupRequest request) {
        return service.review(id, request, CurrentUserHolder.require());
    }
}
