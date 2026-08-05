package com.sse.app.report;

import com.sse.app.report.YearResultDtos.PublishYearResultRequest;
import com.sse.app.report.YearResultDtos.PublishYearResultResponse;
import com.sse.app.report.YearResultDtos.StudentYearResult;
import com.sse.app.report.YearResultDtos.YearResultFile;
import com.sse.app.report.YearResultDtos.YearResultPublicationStatus;
import com.sse.app.report.YearResultDtos.WithdrawYearResultRequest;
import com.sse.app.report.YearResultDtos.WithdrawYearResultResponse;
import com.sse.app.report.YearResultDtos.BatchYearResultRequest;
import com.sse.app.report.YearResultDtos.BatchYearResultResponse;
import com.sse.app.security.CurrentUserHolder;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.List;

@RestController
@RequestMapping("/year-results")
public class YearResultController {
    private final YearResultService results;

    public YearResultController(YearResultService results) {
        this.results = results;
    }

    @GetMapping("/publication")
    public YearResultPublicationStatus publication(@RequestParam String academicYearId,
                                                   @RequestParam String classId) {
        CurrentUserHolder.requireRole("ADMIN");
        return results.status(academicYearId, classId);
    }

    @PostMapping("/{academicYearId}/classes/{classId}/publish")
    public PublishYearResultResponse publish(@PathVariable String academicYearId,
                                             @PathVariable String classId,
                                             @RequestBody PublishYearResultRequest request) {
        CurrentUserHolder.requireRole("ADMIN");
        return results.publish(academicYearId, classId, request.confirmed(), request.reason(),
                CurrentUserHolder.require());
    }

    @PostMapping("/{academicYearId}/classes/{classId}/withdraw")
    public WithdrawYearResultResponse withdraw(@PathVariable String academicYearId,
                                               @PathVariable String classId,
                                               @RequestBody WithdrawYearResultRequest request) {
        CurrentUserHolder.requireRole("ADMIN");
        return results.withdraw(academicYearId, classId, request.confirmed(), request.reason(),
                CurrentUserHolder.require());
    }

    @PostMapping("/{academicYearId}/publish-batch")
    public BatchYearResultResponse publishBatch(
            @PathVariable String academicYearId,
            @RequestBody BatchYearResultRequest request) {
        CurrentUserHolder.requireRole("ADMIN");
        return results.publishBatch(
                academicYearId, request, CurrentUserHolder.require());
    }

    @PostMapping("/{academicYearId}/withdraw-batch")
    public BatchYearResultResponse withdrawBatch(
            @PathVariable String academicYearId,
            @RequestBody BatchYearResultRequest request) {
        CurrentUserHolder.requireRole("ADMIN");
        return results.withdrawBatch(
                academicYearId, request, CurrentUserHolder.require());
    }

    @GetMapping("/{academicYearId}/classes/{classId}/history")
    public List<YearResultPublicationHistory> history(
            @PathVariable String academicYearId,
            @PathVariable String classId) {
        CurrentUserHolder.requireRole("ADMIN");
        return results.history(academicYearId, classId);
    }

    @GetMapping("/me")
    public List<StudentYearResult> ownResults() {
        return results.ownResults(CurrentUserHolder.require());
    }

    @GetMapping("/students/{studentId}")
    public List<StudentYearResult> studentResults(@PathVariable String studentId) {
        return results.resultsForStudent(studentId, CurrentUserHolder.require());
    }

    @GetMapping("/students/{studentId}/{academicYearId}/export")
    public ResponseEntity<byte[]> export(@PathVariable String studentId,
                                         @PathVariable String academicYearId,
                                         @RequestParam(defaultValue = "PDF") String format) {
        YearResultFile file = results.export(studentId, academicYearId, format,
                CurrentUserHolder.require());
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(org.springframework.http.MediaType.parseMediaType(file.contentType()));
        headers.setContentDisposition(ContentDisposition.attachment()
                .filename(file.filename(), StandardCharsets.UTF_8).build());
        headers.setCacheControl(CacheControl.noStore());
        return ResponseEntity.ok().headers(headers).body(file.content());
    }
}
