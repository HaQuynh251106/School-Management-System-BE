package com.sse.app.academic.structure;

import com.sse.app.common.PageResponse;
import com.sse.app.security.CurrentUserHolder;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import static com.sse.app.academic.structure.StudentClassTransferDtos.*;

@RestController
@RequestMapping("/student-class-transfers")
public class StudentClassTransferController {
    private final StudentClassTransferService service;

    public StudentClassTransferController(StudentClassTransferService service) {
        this.service = service;
    }

    @GetMapping("/window")
    public TransferWindow window(@RequestParam String academicYearId) {
        CurrentUserHolder.requireRole("ACADEMIC_STAFF");
        return service.window(academicYearId);
    }

    @PostMapping
    public StudentClassTransfer transfer(@Valid @RequestBody TransferRequest request) {
        CurrentUserHolder.requireRole("ACADEMIC_STAFF");
        return service.transfer(request, CurrentUserHolder.require());
    }

    @GetMapping
    public PageResponse<StudentClassTransfer> history(
            @RequestParam(required = false) String academicYearId,
            @RequestParam(required = false) String studentId,
            @RequestParam(defaultValue = "ALL") String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        CurrentUserHolder.requireRole("ACADEMIC_STAFF");
        return service.history(academicYearId, studentId, status, page, size);
    }

    @PostMapping("/{id}/undo")
    public StudentClassTransfer undo(@PathVariable String id, @Valid @RequestBody UndoRequest request) {
        CurrentUserHolder.requireRole("ACADEMIC_STAFF");
        return service.undo(id, request, CurrentUserHolder.require());
    }
}
