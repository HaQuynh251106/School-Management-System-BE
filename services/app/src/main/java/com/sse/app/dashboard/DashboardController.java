package com.sse.app.dashboard;

import com.sse.app.dashboard.DashboardDtos.DashboardResponse;
import com.sse.app.security.CurrentUserHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/dashboard")
public class DashboardController {
    private final DashboardService dashboard;

    public DashboardController(DashboardService dashboard) {
        this.dashboard = dashboard;
    }

    @GetMapping
    public DashboardResponse currentDashboard() {
        return dashboard.forCurrentUser(CurrentUserHolder.require());
    }
}
