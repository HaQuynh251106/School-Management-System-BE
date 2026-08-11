package com.sse.app.club;

import com.sse.app.club.ClubDtos.CancelClubRegistrationRequest;
import com.sse.app.club.ClubDtos.ClubRegistrationView;
import com.sse.app.club.ClubDtos.ClubView;
import com.sse.app.club.ClubDtos.CreateClubRequest;
import com.sse.app.club.ClubDtos.RegisterClubRequest;
import com.sse.app.club.ClubDtos.RegistrationDecisionRequest;
import com.sse.app.security.CurrentUser;
import com.sse.app.security.CurrentUserHolder;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping
public class ClubController {
    private final ClubService clubs;

    public ClubController(ClubService clubs) {
        this.clubs = clubs;
    }

    @GetMapping("/clubs")
    public List<ClubView> clubs() {
        CurrentUserHolder.require();
        return clubs.list();
    }

    @PostMapping("/clubs")
    public ClubView create(@Valid @RequestBody CreateClubRequest request) {
        CurrentUserHolder.requireRole("ADMIN");
        return clubs.create(request);
    }

    @PostMapping("/clubs/{clubId}/registrations")
    public ClubRegistrationView register(@PathVariable String clubId,
                                         @RequestBody(required = false) RegisterClubRequest request) {
        CurrentUser me = CurrentUserHolder.require();
        CurrentUserHolder.requireRole("STUDENT", "PARENT");
        return clubs.register(clubId, request == null ? null : request.studentId(), me);
    }

    @GetMapping("/me/club-registrations")
    public List<ClubRegistrationView> mine() {
        CurrentUser me = CurrentUserHolder.require();
        CurrentUserHolder.requireRole("STUDENT");
        return clubs.registrationsOfStudent(me.id());
    }

    @GetMapping("/children/{studentId}/club-registrations")
    public List<ClubRegistrationView> childRegistrations(@PathVariable String studentId) {
        CurrentUser me = CurrentUserHolder.require();
        CurrentUserHolder.requireRole("PARENT");
        return clubs.registrationsOfChild(me.id(), studentId);
    }

    @GetMapping("/admin/club-registrations")
    public List<ClubRegistrationView> registrations(
            @RequestParam(required = false) String clubId,
            @RequestParam(required = false) String status) {
        CurrentUserHolder.requireRole("ADMIN");
        return clubs.registrations(clubId, status);
    }

    @PostMapping("/club-registrations/{id}/approve")
    public ClubRegistrationView approve(@PathVariable String id,
                                        @RequestBody(required = false) RegistrationDecisionRequest request) {
        CurrentUserHolder.requireRole("ADMIN");
        return clubs.approve(id, request == null ? null : request.note());
    }

    @PostMapping("/club-registrations/{id}/reject")
    public ClubRegistrationView reject(@PathVariable String id,
                                       @RequestBody(required = false) RegistrationDecisionRequest request) {
        CurrentUserHolder.requireRole("ADMIN");
        return clubs.reject(id, request == null ? null : request.note());
    }

    @PostMapping("/club-registrations/{id}/cancel")
    public ClubRegistrationView cancel(@PathVariable String id,
                                       @RequestBody(required = false) CancelClubRegistrationRequest request) {
        return clubs.cancel(id, request == null ? null : request.reason(), CurrentUserHolder.require());
    }
}
