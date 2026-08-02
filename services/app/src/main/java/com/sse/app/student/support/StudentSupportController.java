package com.sse.app.student.support;

import com.sse.app.common.PageResponse;
import com.sse.app.security.CurrentUser;
import com.sse.app.security.CurrentUserHolder;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import static com.sse.app.student.support.StudentSupportDtos.*;

@RestController
@RequestMapping("/me/student-support")
@RequiredArgsConstructor
public class StudentSupportController {
    private final StudentSupportService support;

    @GetMapping
    public PageResponse<InterventionView> page(
            @RequestParam String classId,
            @RequestParam(required = false) String studentId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String severity,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return support.page(teacher(), classId, studentId, status, severity, q, page, size);
    }

    @GetMapping("/{id}")
    public InterventionView get(@PathVariable String id) {
        return support.get(teacher(), id);
    }

    @PostMapping
    public InterventionView create(@Valid @RequestBody SaveInterventionRequest request) {
        return support.create(teacher(), request);
    }

    @PutMapping("/{id}")
    public InterventionView update(@PathVariable String id,
                                   @Valid @RequestBody UpdateInterventionRequest request) {
        return support.update(teacher(), id, request);
    }

    @PostMapping("/{id}/contact-family")
    public FamilyContactResult contactFamily(@PathVariable String id,
                                             @Valid @RequestBody FamilyContactRequest request) {
        return support.contactFamily(teacher(), id, request);
    }

    private CurrentUser teacher() {
        CurrentUserHolder.requireRole("TEACHER");
        return CurrentUserHolder.require();
    }
}
