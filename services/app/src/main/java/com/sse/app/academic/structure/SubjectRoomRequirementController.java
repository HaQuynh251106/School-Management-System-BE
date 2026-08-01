package com.sse.app.academic.structure;

import com.sse.app.academic.structure.SubjectRoomRequirementDtos.*;
import com.sse.app.security.CurrentUserHolder;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/subject-room-requirements")
public class SubjectRoomRequirementController {
    private final SubjectRoomRequirementService service;

    public SubjectRoomRequirementController(SubjectRoomRequirementService service) {
        this.service = service;
    }

    @GetMapping
    public List<View> list(@RequestParam(required = false) String subjectId) {
        CurrentUserHolder.require();
        return service.list(subjectId);
    }

    @PostMapping
    public View save(@Valid @RequestBody SaveRequest request) {
        CurrentUserHolder.requireRole("ADMIN", "ACADEMIC_STAFF");
        return service.save(request);
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable String id) {
        CurrentUserHolder.requireRole("ADMIN", "ACADEMIC_STAFF");
        service.delete(id);
    }
}
