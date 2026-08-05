package com.sse.app.operations;

import com.sse.app.security.CurrentUserHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin/operations")
public class AdminOperationsController {
    private final AdminOperationsService operations;

    public AdminOperationsController(AdminOperationsService operations) {
        this.operations = operations;
    }

    @GetMapping("/overview")
    public AdminOperationsDtos.Snapshot overview() {
        CurrentUserHolder.requireRole("ADMIN");
        return operations.snapshot();
    }
}
