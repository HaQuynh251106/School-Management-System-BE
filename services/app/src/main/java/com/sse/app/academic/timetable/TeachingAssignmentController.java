package com.sse.app.academic.timetable;

import com.sse.app.academic.timetable.TeachingAssignmentDtos.SaveTeachingAssignmentRequest;
import com.sse.app.academic.timetable.TeachingAssignmentDtos.BatchSaveTeachingAssignmentRequest;
import com.sse.app.academic.timetable.TeachingAssignmentDtos.TeachingAssignmentResponse;
import com.sse.app.academic.timetable.TeachingAssignmentDtos.TeacherWorkloadResponse;
import com.sse.app.common.ApiException;
import com.sse.app.security.CurrentUser;
import com.sse.app.security.CurrentUserHolder;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public class TeachingAssignmentController {
    private final TeachingAssignmentService assignments;

    public TeachingAssignmentController(TeachingAssignmentService assignments) {
        this.assignments = assignments;
    }

    @GetMapping("/teaching-assignments")
    public List<TeachingAssignmentResponse> list(
            @RequestParam(required = false) String classId,
            @RequestParam(required = false) String subjectId,
            @RequestParam(required = false) String teacherId,
            @RequestParam(required = false) String semesterId,
            @RequestParam(required = false) String dayOfWeek,
            @RequestParam(required = false) Integer periodNo) {
        CurrentUser current = CurrentUserHolder.require();
        CurrentUserHolder.requireRole("ADMIN", "TEACHER");
        if (current.isTeacher()) {
            if (teacherId != null && !teacherId.equals(current.id())) {
                throw ApiException.forbidden("Giáo viên chỉ được xem phân công của mình");
            }
            teacherId = current.id();
        }
        if ((dayOfWeek == null) != (periodNo == null)) {
            throw ApiException.badRequest("Phải cung cấp đồng thời thứ và tiết để kiểm tra lịch bận");
        }
        return assignments.list(classId, subjectId, teacherId, semesterId, dayOfWeek, periodNo);
    }

    @GetMapping("/me/teaching-assignments")
    public List<TeachingAssignmentResponse> mine(@RequestParam(required = false) String semesterId) {
        CurrentUserHolder.requireRole("TEACHER");
        CurrentUser current = CurrentUserHolder.require();
        return assignments.list(null, null, current.id(), semesterId, null, null);
    }

    @GetMapping("/teaching-assignments/workloads")
    public List<TeacherWorkloadResponse> workloads(@RequestParam(required = false) String semesterId) {
        CurrentUserHolder.requireRole("ADMIN");
        return assignments.teacherWorkloads(semesterId);
    }

    @PostMapping("/teaching-assignments")
    public TeachingAssignmentResponse create(@Valid @RequestBody SaveTeachingAssignmentRequest request) {
        CurrentUserHolder.requireRole("ADMIN");
        return assignments.create(request, CurrentUserHolder.require().id());
    }

    @PostMapping("/teaching-assignments/batch")
    public List<TeachingAssignmentResponse> createBatch(
            @Valid @RequestBody BatchSaveTeachingAssignmentRequest request) {
        CurrentUserHolder.requireRole("ADMIN");
        return assignments.createBatch(request, CurrentUserHolder.require().id());
    }

    @PutMapping("/teaching-assignments/{id}")
    public TeachingAssignmentResponse update(@PathVariable String id,
                                              @Valid @RequestBody SaveTeachingAssignmentRequest request) {
        CurrentUserHolder.requireRole("ADMIN");
        return assignments.update(id, request);
    }

    @DeleteMapping("/teaching-assignments/{id}")
    public void delete(@PathVariable String id) {
        CurrentUserHolder.requireRole("ADMIN");
        assignments.delete(id);
    }
}
