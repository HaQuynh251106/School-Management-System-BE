package com.sse.app.academic.structure;

import com.sse.app.security.CurrentUserHolder;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

import static com.sse.app.academic.structure.IntakePlacementDtos.*;

@RestController
@RequestMapping("/intake-class-placement")
@RequiredArgsConstructor
public class IntakeClassPlacementController {
    private final IntakeClassPlacementService placement;

    @GetMapping("/candidates")
    public List<Candidate> candidates(@RequestParam String academicYearId,
                                      @RequestParam(defaultValue = "K10") String gradeLevel) {
        CurrentUserHolder.requireRole("ADMIN", "ACADEMIC_STAFF");
        return placement.candidates(academicYearId, gradeLevel);
    }

    @PostMapping("/preview")
    public PreviewResponse preview(@Valid @RequestBody PreviewRequest request) {
        CurrentUserHolder.requireRole("ADMIN", "ACADEMIC_STAFF");
        return placement.preview(request);
    }

    @PostMapping("/apply")
    public ApplyResponse apply(@Valid @RequestBody PreviewRequest request) {
        CurrentUserHolder.requireRole("ADMIN", "ACADEMIC_STAFF");
        return placement.apply(request, CurrentUserHolder.require().id());
    }

    @PostMapping("/undo-last")
    public UndoResponse undoLast(@Valid @RequestBody UndoRequest request) {
        CurrentUserHolder.requireRole("ADMIN", "ACADEMIC_STAFF");
        return placement.undoLast(request, CurrentUserHolder.require().id());
    }

    @GetMapping("/history")
    public List<RunSummary> history(@RequestParam String academicYearId,
                                    @RequestParam(defaultValue = "K10") String gradeLevel) {
        CurrentUserHolder.requireRole("ADMIN", "ACADEMIC_STAFF");
        return placement.history(academicYearId, gradeLevel);
    }
}
