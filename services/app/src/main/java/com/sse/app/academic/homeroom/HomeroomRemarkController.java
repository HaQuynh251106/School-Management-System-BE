package com.sse.app.academic.homeroom;

import com.sse.app.audit.AuditService;
import com.sse.app.security.CurrentUser;
import com.sse.app.security.CurrentUserHolder;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

import static com.sse.app.academic.homeroom.HomeroomRemarkDtos.*;

@RestController
@RequestMapping("/students/{studentId}/homeroom-remarks")
public class HomeroomRemarkController {
    private final HomeroomRemarkService remarks;
    private final AuditService audit;

    public HomeroomRemarkController(HomeroomRemarkService remarks, AuditService audit) {
        this.remarks = remarks;
        this.audit = audit;
    }

    @GetMapping
    public List<RemarkResponse> list(@PathVariable String studentId) {
        return remarks.list(CurrentUserHolder.require(), studentId);
    }

    @PutMapping
    public RemarkResponse save(@PathVariable String studentId,
                               @Valid @RequestBody SaveRemarkRequest request) {
        CurrentUser actor = CurrentUserHolder.require();
        RemarkResponse saved = remarks.save(actor, studentId, request);
        audit.record(actor.id(), actor.username(), actor.role(),
                request.publish() ? "HOMEROOM_REMARK_PUBLISH" : "HOMEROOM_REMARK_SAVE_DRAFT",
                "academic", "homeroom_remark", saved.id(),
                "Student=" + studentId + "; semester=" + request.semesterId());
        return saved;
    }
}
