package com.sse.app.workcenter;

import com.sse.app.common.PageResponse;
import com.sse.app.security.CurrentUserHolder;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;

import static com.sse.app.workcenter.WorkCenterDtos.*;

@RestController
@RequestMapping("/work-center")
@RequiredArgsConstructor
public class WorkCenterController {
    private final WorkCenterService service;

    private void requireOperationalRole() {
        CurrentUserHolder.requireRole("ADMIN", "ACADEMIC_STAFF", "ACCOUNTANT", "TEACHER");
    }

    @GetMapping("/tasks")
    public PageResponse<TaskSummary> page(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String priority,
            @RequestParam(required = false) String module,
            @RequestParam(required = false) String assignedRole,
            @RequestParam(required = false) String assignedTo,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dueFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dueTo,
            @RequestParam(required = false) Boolean overdue,
            @RequestParam(required = false) Boolean active,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "updatedAt") String sort,
            @RequestParam(defaultValue = "desc") String direction) {
        requireOperationalRole();
        return service.page(q, status, priority, module, assignedRole, assignedTo, dueFrom, dueTo,
                overdue, active, page, size, sort, direction);
    }

    @GetMapping("/tasks/{id}")
    public TaskDetail detail(@PathVariable String id) { requireOperationalRole(); return service.detail(id); }

    @PostMapping("/tasks")
    @ResponseStatus(HttpStatus.CREATED)
    public TaskDetail create(@Valid @RequestBody CreateTaskRequest request) { requireOperationalRole(); return service.create(request); }

    @PutMapping("/tasks/{id}")
    public TaskDetail update(@PathVariable String id, @Valid @RequestBody UpdateTaskRequest request) {
        requireOperationalRole(); return service.update(id, request);
    }

    @PostMapping("/tasks/{id}/transitions")
    public TaskDetail transition(@PathVariable String id, @Valid @RequestBody TransitionRequest request) {
        requireOperationalRole(); return service.transition(id, request);
    }

    @PostMapping("/tasks/{id}/comments")
    @ResponseStatus(HttpStatus.CREATED)
    public OperationTaskComment comment(@PathVariable String id, @Valid @RequestBody AddCommentRequest request) {
        requireOperationalRole(); return service.addComment(id, request);
    }

    @PostMapping("/tasks/{id}/checklist")
    @ResponseStatus(HttpStatus.CREATED)
    public OperationTaskChecklistItem checklist(@PathVariable String id, @Valid @RequestBody AddChecklistRequest request) {
        requireOperationalRole(); return service.addChecklist(id, request);
    }

    @PatchMapping("/tasks/{taskId}/checklist/{itemId}")
    public OperationTaskChecklistItem checklistState(@PathVariable String taskId, @PathVariable String itemId,
                                                       @Valid @RequestBody ChecklistStateRequest request) {
        requireOperationalRole(); return service.setChecklistState(taskId, itemId, request);
    }

    @DeleteMapping("/tasks/{taskId}/checklist/{itemId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteChecklist(@PathVariable String taskId, @PathVariable String itemId) {
        requireOperationalRole(); service.deleteChecklist(taskId, itemId);
    }

    @PostMapping("/tasks/{id}/attachments")
    @ResponseStatus(HttpStatus.CREATED)
    public OperationTaskAttachment attachment(@PathVariable String id, @Valid @RequestBody AddAttachmentRequest request) {
        requireOperationalRole(); return service.addAttachment(id, request);
    }

    @PostMapping("/tasks/{id}/snooze")
    public TaskDetail snooze(@PathVariable String id, @Valid @RequestBody SnoozeRequest request) {
        requireOperationalRole(); return service.snooze(id, request);
    }

    @GetMapping("/stats")
    public WorkCenterStats stats() { requireOperationalRole(); return service.stats(); }

    @GetMapping("/assignees")
    public java.util.List<AssigneeOption> assignees(@RequestParam String role) {
        requireOperationalRole(); return service.assignees(role);
    }

    @GetMapping(value = "/export", produces = "text/csv")
    public ResponseEntity<byte[]> export(@RequestParam(required = false) String status,
                                         @RequestParam(required = false) String module) {
        requireOperationalRole();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=work-center.csv")
                .contentType(new MediaType("text", "csv"))
                .body(service.exportCsv(status, module));
    }
}
