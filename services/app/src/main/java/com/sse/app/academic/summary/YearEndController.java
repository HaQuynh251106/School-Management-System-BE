package com.sse.app.academic.summary;

import com.sse.app.academic.summary.YearEndDtos.ConductRequest;
import com.sse.app.security.CurrentUserHolder;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/academic-years")
public class YearEndController {
    private final YearEndService yearEnd;

    public YearEndController(YearEndService yearEnd) {
        this.yearEnd = yearEnd;
    }

    @GetMapping("/{id}/promotion-preview")
    public List<StudentYearlySummary> preview(@PathVariable String id) {
        CurrentUserHolder.requireRole("ADMIN");
        return yearEnd.preview(id);
    }

    @PutMapping("/{id}/students/{studentId}/conduct")
    public StudentYearlySummary conduct(@PathVariable String id, @PathVariable String studentId,
                                        @Valid @RequestBody ConductRequest request) {
        CurrentUserHolder.requireRole("ADMIN", "TEACHER");
        return yearEnd.setConduct(id, studentId, request.conductGrade(), CurrentUserHolder.require());
    }

    @PostMapping("/{id}/finalize")
    public List<StudentYearlySummary> finalizeYear(@PathVariable String id) {
        CurrentUserHolder.requireRole("ADMIN");
        var current = CurrentUserHolder.require();
        return yearEnd.finalizeYear(id, current.id());
    }
}
