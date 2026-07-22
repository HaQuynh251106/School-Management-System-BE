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
    private final YearRolloverService rollover;

    public YearEndController(YearEndService yearEnd, YearRolloverService rollover) {
        this.yearEnd = yearEnd;
        this.rollover = rollover;
    }

    @GetMapping("/{id}/promotion-preview")
    public List<StudentYearlySummary> preview(@PathVariable String id) {
        CurrentUserHolder.requireRole("ADMIN");
        return yearEnd.preview(id);
    }

    @PutMapping("/{id}/students/{studentId}/conduct")
    public StudentYearlySummary conduct(@PathVariable String id, @PathVariable String studentId,
                                        @Valid @RequestBody ConductRequest request) {
        CurrentUserHolder.requireRole("TEACHER");
        return yearEnd.setConduct(id, studentId, request.conductGrade(), CurrentUserHolder.require());
    }

    @GetMapping("/{id}/homeroom-summaries")
    public List<StudentYearlySummary> homeroomSummaries(@PathVariable String id) {
        CurrentUserHolder.requireRole("TEACHER");
        return yearEnd.homeroomPreview(id, CurrentUserHolder.require().id());
    }

    @GetMapping("/{id}/my-summary")
    public StudentYearlySummary mySummary(@PathVariable String id) {
        CurrentUserHolder.requireRole("STUDENT");
        return yearEnd.studentSummary(id, CurrentUserHolder.require().id());
    }

    @GetMapping("/{id}/children/{studentId}/summary")
    public StudentYearlySummary childSummary(@PathVariable String id, @PathVariable String studentId) {
        CurrentUserHolder.requireRole("PARENT");
        var current = CurrentUserHolder.require();
        yearEnd.assertParentOf(current.id(), studentId);
        return yearEnd.studentSummary(id, studentId);
    }

    @PostMapping("/{id}/finalize")
    public List<StudentYearlySummary> finalizeYear(@PathVariable String id) {
        CurrentUserHolder.requireRole("ADMIN");
        var current = CurrentUserHolder.require();
        return yearEnd.finalizeYear(id, current.id());
    }

    @GetMapping("/{id}/rollover-preview")
    public YearEndDtos.RolloverPreview rolloverPreview(@PathVariable String id) {
        CurrentUserHolder.requireRole("ADMIN");
        return rollover.preview(id);
    }

    @PostMapping("/{id}/rollover")
    public YearEndDtos.RolloverResult rollover(@PathVariable String id,
                                                @Valid @RequestBody YearEndDtos.RolloverRequest request) {
        CurrentUserHolder.requireRole("ADMIN");
        return rollover.rollover(id, request, CurrentUserHolder.require().id());
    }
}
