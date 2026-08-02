package com.sse.app.academic.timetable;

import com.sse.app.security.CurrentUser;
import com.sse.app.security.CurrentUserHolder;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

import static com.sse.app.academic.timetable.TeachingOperationDtos.*;

@RestController
@RequiredArgsConstructor
public class TeachingOperationController {
    private final TeachingOperationService operations;

    @GetMapping("/me/teaching-operations")
    public TeachingWorkspace workspace(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        CurrentUserHolder.requireRole("TEACHER");
        return operations.workspace(CurrentUserHolder.require().id(), from, to);
    }

    @GetMapping("/me/teaching-operations/substitute-candidates")
    public List<SubstituteCandidate> candidates(
            @RequestParam String slotId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        CurrentUserHolder.requireRole("TEACHER");
        return operations.substituteCandidates(CurrentUserHolder.require().id(), slotId, date);
    }

    @PutMapping("/me/lesson-diaries/{slotId}/{date}")
    public LessonDiary saveDiary(
            @PathVariable String slotId,
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @Valid @RequestBody SaveLessonDiaryRequest request) {
        CurrentUserHolder.requireRole("TEACHER");
        return operations.saveDiary(CurrentUserHolder.require(), slotId, date, request);
    }

    @GetMapping("/me/lesson-diaries/{slotId}/{date}")
    public LessonDiary diary(
            @PathVariable String slotId,
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        CurrentUserHolder.requireRole("TEACHER");
        return operations.diary(CurrentUserHolder.require(), slotId, date);
    }

    @PostMapping("/me/timetable-change-requests")
    public ChangeRequestView create(@Valid @RequestBody ChangeRequestCreate request) {
        CurrentUserHolder.requireRole("TEACHER");
        return operations.createChange(CurrentUserHolder.require(), request);
    }

    @PostMapping("/me/timetable-change-requests/{id}/cancel")
    public ChangeRequestView cancel(@PathVariable String id) {
        CurrentUserHolder.requireRole("TEACHER");
        return operations.cancel(CurrentUserHolder.require(), id);
    }

    @GetMapping("/academic/timetable-change-requests")
    public List<ChangeRequestView> pending() {
        CurrentUserHolder.requireRole("ADMIN", "ACADEMIC_STAFF");
        return operations.pendingChanges();
    }

    @PostMapping("/academic/timetable-change-requests/{id}/decision")
    public ChangeRequestView decide(@PathVariable String id, @Valid @RequestBody ChangeDecision decision) {
        CurrentUserHolder.requireRole("ADMIN", "ACADEMIC_STAFF");
        CurrentUser actor = CurrentUserHolder.require();
        return operations.decide(actor, id, decision);
    }
}
