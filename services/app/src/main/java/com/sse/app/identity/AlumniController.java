package com.sse.app.identity;

import com.sse.app.common.PageResponse;
import com.sse.app.identity.AlumniDtos.AlumniRecord;
import com.sse.app.security.CurrentUserHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/alumni")
public class AlumniController {
    private final AlumniService alumni;

    public AlumniController(AlumniService alumni) {
        this.alumni = alumni;
    }

    @GetMapping("/page")
    public PageResponse<AlumniRecord> page(@RequestParam(required = false) String q,
                                           @RequestParam(required = false) String cohortId,
                                           @RequestParam(required = false) String graduationAcademicYearId,
                                           @RequestParam(defaultValue = "0") int page,
                                           @RequestParam(defaultValue = "10") int size) {
        CurrentUserHolder.requireRole("ADMIN", "ACADEMIC_STAFF");
        return alumni.page(q, cohortId, graduationAcademicYearId, page, size);
    }

    @GetMapping("/{studentId}")
    public AlumniRecord get(@PathVariable String studentId) {
        CurrentUserHolder.requireRole("ADMIN", "ACADEMIC_STAFF");
        return alumni.get(studentId);
    }
}
