package com.sse.app.academic.teaching;

import com.sse.app.academic.teaching.TeachingDtos.*;
import com.sse.app.security.CurrentUser;
import com.sse.app.security.CurrentUserHolder;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
public class TeachingAssignmentController {

    private final TeachingAssignmentService assignments;

    public TeachingAssignmentController(TeachingAssignmentService assignments) {
        this.assignments = assignments;
    }

    @GetMapping({"/teacher-class-subjects", "/academic/teacher-class-subjects"})
    public List<TeachingAssignmentDto> list(@RequestParam(required = false) String teacherId,
                                            @RequestParam(required = false) String classId,
                                            @RequestParam(required = false) String subjectId,
                                            @RequestParam(required = false) String semesterId,
                                            @RequestParam(required = false) String status) {
        CurrentUserHolder.require();
        return assignments.list(teacherId, classId, subjectId, semesterId, status);
    }

    @GetMapping("/me/teacher-class-subjects")
    public List<TeachingAssignmentDto> mine() {
        CurrentUser me = CurrentUserHolder.require();
        CurrentUserHolder.requireRole("TEACHER");
        return assignments.list(me.id(), null, null, null, "ACTIVE");
    }

    @PostMapping({"/teacher-class-subjects", "/academic/teacher-class-subjects"})
    public TeachingAssignmentDto create(@Valid @RequestBody CreateTeachingAssignmentRequest request) {
        CurrentUserHolder.requireRole("ADMIN");
        return assignments.create(request);
    }

    @PutMapping({"/teacher-class-subjects/{id}", "/academic/teacher-class-subjects/{id}"})
    public TeachingAssignmentDto update(@PathVariable String id,
                                        @RequestBody UpdateTeachingAssignmentRequest request) {
        CurrentUserHolder.requireRole("ADMIN");
        return assignments.update(id, request);
    }

    @DeleteMapping({"/teacher-class-subjects/{id}", "/academic/teacher-class-subjects/{id}"})
    public TeachingAssignmentDto deactivate(@PathVariable String id) {
        CurrentUserHolder.requireRole("ADMIN");
        return assignments.deactivate(id);
    }
}
