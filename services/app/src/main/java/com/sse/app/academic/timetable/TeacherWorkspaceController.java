package com.sse.app.academic.timetable;

import com.sse.app.security.CurrentUserHolder;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static com.sse.app.academic.timetable.TeacherWorkspaceDtos.WorkspaceContext;

@RestController
@RequiredArgsConstructor
public class TeacherWorkspaceController {
    private final TeacherWorkspaceService workspace;

    @GetMapping("/me/teacher-workspace")
    public WorkspaceContext context() {
        CurrentUserHolder.requireRole("TEACHER");
        return workspace.context(CurrentUserHolder.require().id());
    }
}
