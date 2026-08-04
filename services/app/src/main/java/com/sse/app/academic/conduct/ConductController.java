package com.sse.app.academic.conduct;

import com.sse.app.security.CurrentUserHolder;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import static com.sse.app.academic.conduct.ConductDtos.*;

@RestController
@RequestMapping("/conduct")
@RequiredArgsConstructor
public class ConductController {
    private final ConductEvaluationService conduct;

    @GetMapping("/rules")
    public RuleSetView rules(@RequestParam String academicYearId,
                             @RequestParam(required = false) String semesterId) {
        CurrentUserHolder.requireRole("ACADEMIC_STAFF", "TEACHER");
        return conduct.activeRules(academicYearId, semesterId);
    }

    @PutMapping("/rules")
    public RuleSetView replaceRules(@RequestParam String academicYearId,
                                    @Valid @RequestBody SaveRuleSetRequest request) {
        CurrentUserHolder.requireRole("ACADEMIC_STAFF");
        return conduct.replaceRules(academicYearId, request, CurrentUserHolder.require());
    }

    @PostMapping("/evidence")
    public EvidenceView evidence(@Valid @RequestBody SaveEvidenceRequest request) {
        CurrentUserHolder.requireRole("TEACHER");
        return conduct.addEvidence(request, CurrentUserHolder.require());
    }

    @GetMapping("/students/{studentId}")
    public EvaluationView evaluation(@PathVariable String studentId,
                                     @RequestParam String academicYearId,
                                     @RequestParam(required = false) String semesterId) {
        return conduct.evaluate(academicYearId, semesterId, studentId, CurrentUserHolder.require());
    }

    @PutMapping("/students/{studentId}/decision")
    public EvaluationView decide(@PathVariable String studentId,
                                 @RequestParam String academicYearId,
                                 @Valid @RequestBody DecisionRequest request) {
        CurrentUserHolder.requireRole("TEACHER");
        return conduct.decide(academicYearId, studentId, request, CurrentUserHolder.require());
    }
}
