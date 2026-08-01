package com.sse.app.academic.structure;

import com.sse.app.academic.structure.RoomAllocationDtos.AllocationPlan;
import com.sse.app.academic.structure.RoomAllocationDtos.PreviewRequest;
import com.sse.app.security.CurrentUserHolder;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/room-allocation-plans")
public class RoomAllocationPlanningController {
    private final RoomAllocationPlanningService planning;

    public RoomAllocationPlanningController(RoomAllocationPlanningService planning) {
        this.planning = planning;
    }

    @PostMapping("/preview")
    public AllocationPlan preview(@Valid @RequestBody PreviewRequest request) {
        CurrentUserHolder.requireRole("ADMIN", "ACADEMIC_STAFF");
        var actor = CurrentUserHolder.require();
        return planning.preview(request, actor.id());
    }

    @GetMapping
    public List<AllocationPlan> list(@RequestParam String academicYearId) {
        CurrentUserHolder.requireRole("ADMIN", "ACADEMIC_STAFF");
        return planning.list(academicYearId);
    }

    @GetMapping("/{id}")
    public AllocationPlan get(@PathVariable String id) {
        CurrentUserHolder.requireRole("ADMIN", "ACADEMIC_STAFF");
        return planning.require(id);
    }

    @PostMapping("/{id}/apply")
    public AllocationPlan apply(@PathVariable String id) {
        CurrentUserHolder.requireRole("ADMIN", "ACADEMIC_STAFF");
        var actor = CurrentUserHolder.require();
        return planning.apply(id, actor.id());
    }

    @PostMapping("/{id}/undo")
    public AllocationPlan undo(@PathVariable String id) {
        CurrentUserHolder.requireRole("ADMIN", "ACADEMIC_STAFF");
        var actor = CurrentUserHolder.require();
        return planning.undo(id, actor.id());
    }
}
