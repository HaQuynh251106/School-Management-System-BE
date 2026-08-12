package com.sse.app.academic.leave;

import com.sse.app.academic.leave.LeaveRequestDtos.CreateLeaveRequest;
import com.sse.app.academic.leave.LeaveRequestDtos.CreateChildLeaveRequest;
import com.sse.app.academic.leave.LeaveRequestDtos.DecisionRequest;
import com.sse.app.security.CurrentUser;
import com.sse.app.security.CurrentUserHolder;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/leave-requests")
public class LeaveRequestController {
    private final LeaveRequestService requests;

    public LeaveRequestController(LeaveRequestService requests) {
        this.requests = requests;
    }

    @GetMapping
    public List<LeaveRequest> list() {
        return requests.list(CurrentUserHolder.require());
    }

    @PostMapping
    public LeaveRequest create(@Valid @RequestBody CreateLeaveRequest input) {
        CurrentUserHolder.requireRole("STUDENT");
        return requests.create(input, CurrentUserHolder.require().id());
    }

    @PostMapping("/children")
    public LeaveRequest createForChild(@Valid @RequestBody CreateChildLeaveRequest input) {
        CurrentUserHolder.requireRole("PARENT");
        CurrentUser parent = CurrentUserHolder.require();
        return requests.createForChild(input, parent.id());
    }

    @PostMapping("/{id}/parent-confirm")
    public LeaveRequest parentConfirm(@PathVariable String id, @RequestBody(required = false) DecisionRequest input) {
        CurrentUserHolder.requireRole("PARENT");
        return requests.parentDecision(id, CurrentUserHolder.require().id(), true, input == null ? null : input.note());
    }

    @PostMapping("/{id}/parent-reject")
    public LeaveRequest parentReject(@PathVariable String id, @RequestBody(required = false) DecisionRequest input) {
        CurrentUserHolder.requireRole("PARENT");
        return requests.parentDecision(id, CurrentUserHolder.require().id(), false, input == null ? null : input.note());
    }

    @PostMapping("/{id}/approve")
    public LeaveRequest approve(@PathVariable String id, @RequestBody(required = false) DecisionRequest input) {
        CurrentUserHolder.requireRole("TEACHER");
        return requests.homeroomDecision(id, CurrentUserHolder.require().id(), true, input == null ? null : input.note());
    }

    @PostMapping("/{id}/reject")
    public LeaveRequest reject(@PathVariable String id, @RequestBody(required = false) DecisionRequest input) {
        CurrentUserHolder.requireRole("TEACHER");
        return requests.homeroomDecision(id, CurrentUserHolder.require().id(), false, input == null ? null : input.note());
    }

    @PostMapping("/{id}/cancel")
    public LeaveRequest cancel(@PathVariable String id) {
        CurrentUserHolder.requireRole("STUDENT");
        return requests.cancel(id, CurrentUserHolder.require().id());
    }
}
