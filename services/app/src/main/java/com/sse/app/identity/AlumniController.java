package com.sse.app.identity;

import com.sse.app.audit.AuditService;
import com.sse.app.common.PageResponse;
import com.sse.app.identity.AlumniArchiveDtos.CohortArchiveOverview;
import com.sse.app.identity.AlumniArchiveDtos.CohortArchiveSummary;
import com.sse.app.identity.AlumniArchiveDtos.CohortStudentListItem;
import com.sse.app.identity.AlumniArchiveDtos.StudentArchiveProfile;
import com.sse.app.identity.AlumniDtos.AlumniClassSummary;
import com.sse.app.identity.AlumniDtos.AlumniRecord;
import com.sse.app.security.CurrentUser;
import com.sse.app.security.CurrentUserHolder;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/alumni")
public class AlumniController {
    private final AlumniService alumni;
    private final AlumniArchiveService archive;
    private final AuditService audit;

    public AlumniController(AlumniService alumni, AlumniArchiveService archive, AuditService audit) {
        this.alumni = alumni;
        this.archive = archive;
        this.audit = audit;
    }

    @GetMapping("/cohorts")
    public List<CohortArchiveSummary> cohorts() {
        CurrentUserHolder.requireRole("ADMIN", "ACADEMIC_STAFF");
        return archive.cohorts();
    }

    @GetMapping("/cohorts/{cohortId}/overview")
    public CohortArchiveOverview cohortOverview(@PathVariable String cohortId) {
        CurrentUserHolder.requireRole("ADMIN", "ACADEMIC_STAFF");
        return archive.overview(cohortId);
    }

    @GetMapping("/cohorts/{cohortId}/students")
    public PageResponse<CohortStudentListItem> cohortStudents(
            @PathVariable String cohortId,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String finalClassId,
            @RequestParam(required = false) String graduationAcademicYearId,
            @RequestParam(required = false) String finalYearResult,
            @RequestParam(required = false) String graduationResult,
            @RequestParam(required = false) String academicPerformance,
            @RequestParam(required = false) String conductGrade,
            @RequestParam(required = false) String recordStatus,
            @RequestParam(defaultValue = "fullName") String sort,
            @RequestParam(defaultValue = "asc") String direction,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        CurrentUserHolder.requireRole("ADMIN", "ACADEMIC_STAFF");
        return archive.students(cohortId, q, finalClassId, graduationAcademicYearId, finalYearResult,
                graduationResult, academicPerformance, conductGrade, recordStatus,
                sort, direction, page, size);
    }

    @GetMapping("/cohorts/{cohortId}/students/{studentId}")
    public StudentArchiveProfile cohortStudent(@PathVariable String cohortId, @PathVariable String studentId) {
        CurrentUserHolder.requireRole("ADMIN", "ACADEMIC_STAFF");
        CurrentUser actor = CurrentUserHolder.require();
        StudentArchiveProfile result = archive.profile(cohortId, studentId);
        audit.record(actor.id(), actor.username(), actor.role(), "VIEW_ARCHIVED_STUDENT_PROFILE",
                "academic_archive", "student", studentId,
                "Tra cứu hồ sơ niên khóa " + result.cohortCode());
        return result;
    }

    @GetMapping("/cohorts/{cohortId}/export")
    public ResponseEntity<byte[]> exportCohort(
            @PathVariable String cohortId,
            @RequestParam(defaultValue = "xlsx") String format,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String finalClassId,
            @RequestParam(required = false) String graduationAcademicYearId,
            @RequestParam(required = false) String finalYearResult,
            @RequestParam(required = false) String graduationResult,
            @RequestParam(required = false) String academicPerformance,
            @RequestParam(required = false) String conductGrade,
            @RequestParam(required = false) String recordStatus,
            @RequestParam(defaultValue = "fullName") String sort,
            @RequestParam(defaultValue = "asc") String direction) {
        CurrentUserHolder.requireRole("ADMIN", "ACADEMIC_STAFF");
        CurrentUser actor = CurrentUserHolder.require();
        boolean pdf = "pdf".equalsIgnoreCase(format);
        byte[] bytes = pdf
                ? archive.exportPdf(cohortId, q, finalClassId, graduationAcademicYearId, finalYearResult,
                        graduationResult, academicPerformance, conductGrade, recordStatus, sort, direction)
                : archive.exportExcel(cohortId, q, finalClassId, graduationAcademicYearId, finalYearResult,
                        graduationResult, academicPerformance, conductGrade, recordStatus, sort, direction);
        audit.record(actor.id(), actor.username(), actor.role(), "EXPORT_COHORT_ARCHIVE",
                "academic_archive", "cohort", cohortId,
                "Xuất " + (pdf ? "PDF" : "Excel") + (finalClassId == null ? " toàn niên khóa" : " lớp " + finalClassId));
        String extension = pdf ? "pdf" : "xlsx";
        MediaType mediaType = pdf ? MediaType.APPLICATION_PDF
                : MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        return ResponseEntity.ok().contentType(mediaType)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"cohort-" + cohortId.replaceAll("[^a-zA-Z0-9_-]", "-") + "." + extension + "\"")
                .body(bytes);
    }

    @GetMapping("/page")
    public PageResponse<AlumniRecord> page(@RequestParam(required = false) String q,
                                           @RequestParam(required = false) String cohortId,
                                           @RequestParam(required = false) String graduationAcademicYearId,
                                           @RequestParam(required = false) String graduationClassId,
                                           @RequestParam(defaultValue = "0") int page,
                                           @RequestParam(defaultValue = "10") int size) {
        CurrentUserHolder.requireRole("ADMIN", "ACADEMIC_STAFF");
        return alumni.page(q, cohortId, graduationAcademicYearId, graduationClassId, page, size);
    }

    @GetMapping("/classes")
    public List<AlumniClassSummary> classes() {
        CurrentUserHolder.requireRole("ADMIN", "ACADEMIC_STAFF");
        return alumni.currentClasses();
    }

    @GetMapping("/classes/archive")
    public List<AlumniClassSummary> archivedClasses(@RequestParam(required = false) String cohortId,
                                                    @RequestParam(required = false) String graduationAcademicYearId) {
        CurrentUserHolder.requireRole("ADMIN", "ACADEMIC_STAFF");
        return alumni.archivedClasses(cohortId, graduationAcademicYearId);
    }

    @GetMapping("/{studentId}")
    public AlumniRecord get(@PathVariable String studentId) {
        CurrentUserHolder.requireRole("ADMIN", "ACADEMIC_STAFF");
        return alumni.get(studentId);
    }
}
