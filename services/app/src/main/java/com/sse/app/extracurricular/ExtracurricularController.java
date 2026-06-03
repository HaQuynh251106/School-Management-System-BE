package com.sse.app.extracurricular;

import com.sse.app.common.ApiException;
import com.sse.app.extracurricular.ExtracurricularDtos.*;
import com.sse.app.identity.UserService;
import com.sse.app.security.CurrentUser;
import com.sse.app.security.CurrentUserHolder;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** A5/C6/D5: CLB ngoại khóa & đăng ký. */
@RestController
public class ExtracurricularController {

    private final ExtracurricularService clubs;
    private final UserService users;

    public ExtracurricularController(ExtracurricularService clubs, UserService users) {
        this.clubs = clubs;
        this.users = users;
    }

    @GetMapping("/clubs")
    public List<Club> list() {
        CurrentUserHolder.require();
        return clubs.listClubs();
    }

    @PostMapping("/clubs")
    public Club create(@Valid @RequestBody CreateClubRequest r) {
        CurrentUserHolder.requireRole("ADMIN");
        return clubs.createClub(r);
    }

    @GetMapping("/clubs/{id}/registrations")
    public List<ClubRegistration> registrations(@PathVariable String id) {
        CurrentUserHolder.requireRole("ADMIN");
        return clubs.registrationsOf(id);
    }

    @PostMapping("/clubs/{id}/register")
    public ClubRegistration register(@PathVariable String id,
                                     @RequestBody(required = false) RegisterRequest r) {
        CurrentUser me = CurrentUserHolder.require();
        String studentId;
        if (me.isStudent()) {
            studentId = me.id();
        } else if (me.isParent()) {
            if (r == null || r.studentId() == null) throw ApiException.badRequest("Thiếu studentId (chọn con)");
            users.assertParentOf(me.id(), r.studentId());
            studentId = r.studentId();
        } else {
            CurrentUserHolder.requireRole("ADMIN");
            if (r == null || r.studentId() == null) throw ApiException.badRequest("Thiếu studentId");
            studentId = r.studentId();
        }
        return clubs.register(id, studentId, me.id());
    }

    @GetMapping("/me/club-registrations")
    public List<ClubRegistration> myRegistrations(@RequestParam(required = false) String studentId) {
        CurrentUser me = CurrentUserHolder.require();
        if (me.isStudent()) studentId = me.id();
        else if (me.isParent()) {
            if (studentId == null) throw ApiException.badRequest("Thiếu studentId (chọn con)");
            users.assertParentOf(me.id(), studentId);
        }
        return clubs.registrationsByStudent(studentId);
    }

    @PostMapping("/club-registrations/{id}/cancel")
    public ClubRegistration cancel(@PathVariable String id) {
        CurrentUserHolder.require();
        return clubs.cancel(id);
    }
}
